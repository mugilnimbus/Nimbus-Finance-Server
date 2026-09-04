package com.nimbus.finance.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicReference

private val inferenceAdminJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = true
}

@Serializable
internal data class InferenceRuntimeSettings(
    val activeModel: String = "gemma-4-e2b-it",
    val contextTokens: Int = 16_384,
    val maxOutputTokens: Int = 384,
    val reasoningEffort: String = "none",
    val temperature: Double = 0.0,
    val topP: Double = 1.0,
    val topK: Int = 1,
    val seed: Int = 0,
    val parallelSlots: Int = 1,
    val threads: Int = 6,
    val batchThreads: Int = 6,
    val batchSize: Int = 2_048,
    val microBatchSize: Int = 512,
    val gpuLayers: String = "all",
    val splitMode: String = "layer",
    val tensorSplit: String = "",
    val mainGpu: Int = 0,
    val fitToMemory: Boolean = true,
    val fitTargetMiB: Int = 1_024,
    val flashAttention: String = "auto",
    val cacheTypeK: String = "f16",
    val cacheTypeV: String = "f16"
)

@Serializable
internal data class InferenceAdminModel(
    val id: String,
    val status: String,
    val source: String,
    val removable: Boolean,
    val inputModalities: List<String> = listOf("text")
)

@Serializable
internal data class InferenceAdminState(
    val settings: InferenceRuntimeSettings,
    val models: List<InferenceAdminModel>,
    val available: Boolean
)

@Serializable internal data class InferenceModelDownloadRequest(val model: String)
@Serializable internal data class InferenceModelActionRequest(val model: String, val action: String)

internal class InferenceAdminService(
    private val configDirectory: Path,
    private val modelsDirectory: Path,
    private val upstream: OpenAiResponsesUpstream,
    private val sessions: InferenceSessionRegistry
) {
    private val settingsPath = configDirectory.resolve("runtime-settings.json")
    private val presetPath = configDirectory.resolve("models.ini")
    private val current = AtomicReference(loadSettings())

    init {
        persist(current.get())
    }

    fun settings(): InferenceRuntimeSettings = current.get()

    fun activeModel(): String = current.get().activeModel

    fun state(reload: Boolean = false): InferenceAdminState {
        val models = upstream.models(reload)
        return InferenceAdminState(current.get(), models, true)
    }

    fun update(request: InferenceRuntimeSettings): InferenceAdminState {
        val validated = validate(request)
        persist(validated)
        current.set(validated)
        sessions.closeAll()
        val models = upstream.models(reload = true)
        if (models.any { it.id == validated.activeModel }) upstream.loadIfNeeded(validated.activeModel, models)
        return InferenceAdminState(validated, upstream.models(), true)
    }

    fun restoreDefaults(): InferenceAdminState = update(InferenceRuntimeSettings())

    fun download(model: String): InferenceAdminState {
        val normalized = model.trim()
        require(HUGGING_FACE_MODEL.matches(normalized)) {
            "Use a Hugging Face model in owner/repository or owner/repository:quant form"
        }
        upstream.downloadModel(normalized)
        return InferenceAdminState(current.get(), upstream.models(), true)
    }

    fun action(request: InferenceModelActionRequest): InferenceAdminState {
        val model = request.model.trim()
        require(model.isNotBlank() && model.length <= 256) { "Choose a valid model" }
        val models = upstream.models()
        val selected = models.firstOrNull { it.id == model } ?: throw IllegalArgumentException("Model was not found")
        when (request.action.trim().uppercase()) {
            "ACTIVATE" -> {
                val updated = current.get().copy(activeModel = model)
                persist(updated)
                current.set(updated)
                sessions.closeAll()
                upstream.loadIfNeeded(model, models)
            }
            "LOAD" -> upstream.loadIfNeeded(model, models)
            "UNLOAD" -> if (selected.status in RUNNING_MODEL_STATES) upstream.unloadModel(model)
            "REMOVE" -> {
                require(selected.removable) { "Only dashboard-downloaded models can be removed" }
                require(model != current.get().activeModel) { "Activate another model before removing this one" }
                if (selected.status in RUNNING_MODEL_STATES) upstream.unloadModel(model)
                upstream.removeModel(model)
            }
            else -> throw IllegalArgumentException("Unsupported model action")
        }
        return InferenceAdminState(current.get(), upstream.models(), true)
    }

    fun activeModelReady(): Boolean = upstream.modelReady(activeModel())

    fun requestActiveModelLoad() = upstream.loadIfNeeded(activeModel(), upstream.models())

    private fun loadSettings(): InferenceRuntimeSettings = runCatching {
        inferenceAdminJson.decodeFromString<InferenceRuntimeSettings>(Files.readString(settingsPath))
    }.getOrDefault(InferenceRuntimeSettings()).let(::validate)

    private fun validate(value: InferenceRuntimeSettings): InferenceRuntimeSettings {
        require(value.activeModel.isNotBlank() && value.activeModel.length <= 256) { "Choose an active model" }
        require(value.contextTokens in 512..16_384) { "Context must be between 512 and 16384 tokens" }
        require(value.maxOutputTokens in 64..1_024 && value.maxOutputTokens < value.contextTokens) {
            "Output must be 64-1024 tokens and smaller than context"
        }
        require(value.reasoningEffort in setOf("none", "auto")) { "Choose a valid reasoning mode" }
        require(value.temperature in 0.0..2.0 && value.topP in 0.01..1.0 && value.topK in 1..128) {
            "Sampling controls are outside the supported range"
        }
        require(value.parallelSlots in 1..8) { "Parallel slots must be 1-8" }
        require(value.threads in 1..64 && value.batchThreads in 1..64) { "Thread counts must be 1-64" }
        require(value.batchSize in 128..8_192 && value.microBatchSize in 64..value.batchSize) { "Batch sizes are invalid" }
        require(value.gpuLayers == "all" || value.gpuLayers == "auto" || value.gpuLayers.toIntOrNull()?.let { it in 0..999 } == true) {
            "GPU layers must be all, auto, or 0-999"
        }
        require(value.splitMode in setOf("none", "layer", "row", "tensor")) { "Choose a valid split mode" }
        require(value.tensorSplit.isBlank() || TENSOR_SPLIT.matches(value.tensorSplit)) { "Tensor split must be comma-separated positive numbers" }
        require(value.mainGpu in 0..15) { "Main GPU index must be 0-15" }
        require(value.fitTargetMiB in 128..16_384) { "Fit target must be 128-16384 MiB" }
        require(value.flashAttention in setOf("on", "off", "auto")) { "Choose a valid Flash Attention mode" }
        require(value.cacheTypeK in CACHE_TYPES && value.cacheTypeV in CACHE_TYPES) { "Choose a supported KV cache type" }
        return value.copy(
            activeModel = value.activeModel.trim(),
            reasoningEffort = value.reasoningEffort.trim().lowercase(),
            tensorSplit = value.tensorSplit.trim()
        )
    }

    private fun persist(settings: InferenceRuntimeSettings) {
        Files.createDirectories(configDirectory)
        val settingsTemp = settingsPath.resolveSibling("${settingsPath.fileName}.tmp")
        Files.writeString(settingsTemp, inferenceAdminJson.encodeToString(settings), StandardCharsets.UTF_8)
        moveAtomically(settingsTemp, settingsPath)

        val presetTemp = presetPath.resolveSibling("${presetPath.fileName}.tmp")
        Files.writeString(presetTemp, buildPreset(settings), StandardCharsets.UTF_8)
        moveAtomically(presetTemp, presetPath)
    }

    private fun buildPreset(settings: InferenceRuntimeSettings): String = buildString {
        appendLine("version = 1")
        appendLine()
        appendLine("[*]")
        appendLine("ctx-size = ${settings.contextTokens}")
        appendLine("parallel = ${settings.parallelSlots}")
        appendLine("threads = ${settings.threads}")
        appendLine("threads-batch = ${settings.batchThreads}")
        appendLine("batch-size = ${settings.batchSize}")
        appendLine("ubatch-size = ${settings.microBatchSize}")
        appendLine("n-gpu-layers = ${settings.gpuLayers}")
        appendLine("split-mode = ${settings.splitMode}")
        if (settings.tensorSplit.isNotBlank()) appendLine("tensor-split = ${settings.tensorSplit}")
        appendLine("main-gpu = ${settings.mainGpu}")
        appendLine("fit = ${if (settings.fitToMemory) "on" else "off"}")
        appendLine("fit-target = ${settings.fitTargetMiB}")
        appendLine("flash-attn = ${settings.flashAttention}")
        appendLine("cache-type-k = ${settings.cacheTypeK}")
        appendLine("cache-type-v = ${settings.cacheTypeV}")
        appendLine("cont-batching = on")
        appendLine("metrics = on")
        appendLine("webui = off")
        appendLine()
        localModels().forEach { (id, fileName) ->
            appendLine("[$id]")
            appendLine("model = /models/$fileName")
            appendLine("load-on-startup = ${id == settings.activeModel}")
            appendLine()
        }
    }

    private fun localModels(): List<Pair<String, String>> = if (!Files.isDirectory(modelsDirectory)) emptyList() else
        Files.list(modelsDirectory).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".gguf", ignoreCase = true) }
                .map { path ->
                    val fileName = path.fileName.toString()
                    sanitizeModelId(fileName.substringBeforeLast('.')) to fileName
                }
                .sorted(compareBy<Pair<String, String>> { it.first })
                .toList()
        }

    private fun moveAtomically(source: Path, target: Path) {
        runCatching { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            .getOrElse { Files.move(source, target, StandardCopyOption.REPLACE_EXISTING) }
    }

    private fun sanitizeModelId(value: String): String = value.lowercase()
        .replace(Regex("[^a-z0-9._-]+"), "-")
        .trim('-')
        .take(128)
        .ifBlank { "local-model" }

    companion object {
        private val HUGGING_FACE_MODEL = Regex("[A-Za-z0-9._-]{1,100}/[A-Za-z0-9._-]{1,150}(?::[A-Za-z0-9._-]{1,40})?")
        private val TENSOR_SPLIT = Regex("[0-9]+(?:\\.[0-9]+)?(?:,[0-9]+(?:\\.[0-9]+)?)*")
        private val CACHE_TYPES = setOf("f32", "f16", "bf16", "q8_0", "q4_0", "q4_1", "iq4_nl", "q5_0", "q5_1")
        private val RUNNING_MODEL_STATES = setOf("loading", "loaded", "sleeping", "downloading")
    }
}

internal fun Route.inferenceAdminRoutes(
    access: DashboardAccess,
    limiter: AbuseLimiter,
    service: InferenceAdminService
) {
    get("/v1/dashboard/admin/inference") {
        if (!access.isAuthenticated(call.request.cookies["nimbus_dashboard_admin"])) return@get call.respond(
            HttpStatusCode.Unauthorized,
            ApiError("ADMIN_SIGN_IN_REQUIRED", "Open the owner dashboard again or enter the administrator key")
        )
        call.noStoreHeader()
        val reload = call.request.queryParameters["reload"] == "1"
        call.respond(withContext(Dispatchers.IO) { service.state(reload) })
    }
    post("/v1/dashboard/admin/inference/settings") {
        if (!access.isAuthenticated(call.request.cookies["nimbus_dashboard_admin"])) return@post call.respond(
            HttpStatusCode.Unauthorized,
            ApiError("ADMIN_SIGN_IN_REQUIRED", "Open the owner dashboard again or enter the administrator key")
        )
        if (!call.enforceRateLimit(limiter, "dashboard-inference-settings", call.clientAddress(), ADMIN_POLICY)) return@post
        val request = call.receive<InferenceRuntimeSettings>()
        call.noStoreHeader()
        call.respond(withContext(Dispatchers.IO) { service.update(request) })
    }
    post("/v1/dashboard/admin/inference/restore") {
        if (!access.isAuthenticated(call.request.cookies["nimbus_dashboard_admin"])) return@post call.respond(
            HttpStatusCode.Unauthorized,
            ApiError("ADMIN_SIGN_IN_REQUIRED", "Open the owner dashboard again or enter the administrator key")
        )
        if (!call.enforceRateLimit(limiter, "dashboard-inference-restore", call.clientAddress(), ADMIN_POLICY)) return@post
        call.noStoreHeader()
        call.respond(withContext(Dispatchers.IO) { service.restoreDefaults() })
    }
    post("/v1/dashboard/admin/inference/download") {
        if (!access.isAuthenticated(call.request.cookies["nimbus_dashboard_admin"])) return@post call.respond(
            HttpStatusCode.Unauthorized,
            ApiError("ADMIN_SIGN_IN_REQUIRED", "Open the owner dashboard again or enter the administrator key")
        )
        if (!call.enforceRateLimit(limiter, "dashboard-inference-download", call.clientAddress(), ADMIN_POLICY)) return@post
        val request = call.receive<InferenceModelDownloadRequest>()
        call.noStoreHeader()
        call.respond(HttpStatusCode.Accepted, withContext(Dispatchers.IO) { service.download(request.model) })
    }
    post("/v1/dashboard/admin/inference/model-action") {
        if (!access.isAuthenticated(call.request.cookies["nimbus_dashboard_admin"])) return@post call.respond(
            HttpStatusCode.Unauthorized,
            ApiError("ADMIN_SIGN_IN_REQUIRED", "Open the owner dashboard again or enter the administrator key")
        )
        if (!call.enforceRateLimit(limiter, "dashboard-inference-action", call.clientAddress(), ADMIN_POLICY)) return@post
        val request = call.receive<InferenceModelActionRequest>()
        call.noStoreHeader()
        call.respond(withContext(Dispatchers.IO) { service.action(request) })
    }
}
