package com.nimbus.finance.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.Closeable
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private val inferenceJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
private const val MAX_INFERENCE_REQUEST_BYTES = 64 * 1024
private const val MAX_SESSION_PROMPT_CHARS = 32_000
private const val MAX_INPUT_CHARS = 20_000

@Serializable
internal data class InferenceSessionOpenRequest(
    val sessionId: String,
    val systemPrompt: String,
    val model: String = "gemma-4-e2b-it",
    val contextTokens: Int = 16_384,
    val maxOutputTokens: Int = 384,
    val temperature: Double = 0.0,
    val topK: Int = 1,
    val topP: Double = 1.0,
    val seed: Int = 0,
    val responseFormat: Boolean = true
)

@Serializable
internal data class InferenceSessionOpenResponse(
    val sessionId: String,
    val state: String,
    val contextLimit: Int
)

@Serializable
internal data class InferenceStatusResponse(
    val ready: Boolean,
    val inputTokens: Long,
    val cachedInputTokens: Long,
    val outputTokens: Long,
    val totalTokens: Long,
    val contextTokens: Int,
    val contextLimit: Int
)

internal data class InferenceUsage(
    val inputTokens: Long = 0,
    val cachedInputTokens: Long = 0,
    val outputTokens: Long = 0,
    val totalTokens: Long = inputTokens + outputTokens
)

private data class InferenceUsageCounters(
    val requests: AtomicLong = AtomicLong(),
    val input: AtomicLong = AtomicLong(),
    val cachedInput: AtomicLong = AtomicLong(),
    val output: AtomicLong = AtomicLong()
)

internal data class InferenceSession(
    val id: String,
    val ownerUserId: UUID,
    val systemPrompt: String,
    val model: String,
    val contextTokens: Int,
    val maxOutputTokens: Int,
    val reasoningEffort: String,
    val temperature: Double,
    val topK: Int,
    val topP: Double,
    val seed: Int,
    val responseFormat: Boolean,
    val createdAtMillis: Long,
    var lastAccessMillis: Long,
    val history: MutableList<JsonElement> = mutableListOf(),
    val generating: AtomicBoolean = AtomicBoolean(false),
    var activeStream: Closeable? = null
)

internal class InferenceSessionRegistry(
    private val idleTtlMillis: Long,
    private val maxSessionsPerUser: Int,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    private val sessions = ConcurrentHashMap<String, InferenceSession>()
    private val countersByOwner = ConcurrentHashMap<UUID, InferenceUsageCounters>()

    fun open(ownerUserId: UUID, request: InferenceSessionOpenRequest, configuredModel: String, runtime: InferenceRuntimeSettings = InferenceRuntimeSettings()): InferenceSession {
        cleanupExpired()
        UUID.fromString(request.sessionId)
        require(request.systemPrompt.isNotBlank() && request.systemPrompt.length <= MAX_SESSION_PROMPT_CHARS) {
            "System prompt must contain 1-$MAX_SESSION_PROMPT_CHARS characters"
        }
        // The private owner dashboard is the authority for model runtime controls.
        // Phone values are accepted for wire compatibility but cannot silently
        // reduce or alter the server profile.
        val contextTokens = runtime.contextTokens
        val maxOutputTokens = runtime.maxOutputTokens
        require(contextTokens in 512..16_384) { "Context must be between 512 and 16384 tokens" }
        require(maxOutputTokens in 64..1_024) { "Output must be between 64 and 1024 tokens" }
        require(maxOutputTokens < contextTokens) { "Output token limit must be smaller than the context limit" }
        require(request.systemPrompt.length <= (contextTokens - maxOutputTokens) * 4) {
            "System prompt is too large for the selected context and output limits"
        }
        val existing = sessions[request.sessionId]
        require(existing == null || existing.ownerUserId == ownerUserId) { "Session belongs to another user" }
        require(existing?.generating?.get() != true) { "Session is currently generating" }
        val ownedCount = sessions.values.count { it.ownerUserId == ownerUserId && it.id != request.sessionId }
        require(ownedCount < maxSessionsPerUser) { "Close an existing inference session before opening another" }
        existing?.activeStream?.close()
        return InferenceSession(
            id = request.sessionId,
            ownerUserId = ownerUserId,
            systemPrompt = request.systemPrompt,
            model = configuredModel,
            contextTokens = contextTokens,
            maxOutputTokens = maxOutputTokens,
            reasoningEffort = runtime.reasoningEffort,
            temperature = runtime.temperature,
            topK = runtime.topK,
            topP = runtime.topP,
            seed = runtime.seed,
            responseFormat = request.responseFormat,
            createdAtMillis = nowMillis(),
            lastAccessMillis = nowMillis()
        ).also { sessions[request.sessionId] = it }
    }

    fun begin(ownerUserId: UUID, sessionId: String, input: JsonElement): Pair<InferenceSession, List<JsonElement>> {
        cleanupExpired()
        val session = sessions[sessionId] ?: throw IllegalArgumentException("Inference session is missing or expired")
        require(session.ownerUserId == ownerUserId) { "Inference session belongs to another user" }
        require(input.toString().length <= availableHistoryCharacters(session)) { "Input is too large for the remaining session context" }
        require(session.generating.compareAndSet(false, true)) { "Only one response may run in a session at a time" }
        session.lastAccessMillis = nowMillis()
        return session to synchronized(session) { session.history.toList() + input }
    }

    fun attachStream(session: InferenceSession, stream: Closeable) {
        synchronized(session) { session.activeStream = stream }
    }

    fun complete(session: InferenceSession, input: JsonElement, output: List<JsonElement>, usage: InferenceUsage) {
        synchronized(session) {
            session.history += input
            session.history += output
            trimHistory(session)
            session.activeStream = null
            session.lastAccessMillis = nowMillis()
        }
        countersByOwner.computeIfAbsent(session.ownerUserId) { InferenceUsageCounters() }.apply {
            requests.incrementAndGet()
            this.input.addAndGet(usage.inputTokens)
            cachedInput.addAndGet(usage.cachedInputTokens)
            this.output.addAndGet(usage.outputTokens)
        }
        session.generating.set(false)
    }

    fun fail(session: InferenceSession) {
        synchronized(session) { session.activeStream = null; session.lastAccessMillis = nowMillis() }
        session.generating.set(false)
    }

    fun cancel(ownerUserId: UUID, sessionId: String): Boolean {
        val session = sessions[sessionId] ?: return false
        require(session.ownerUserId == ownerUserId) { "Inference session belongs to another user" }
        synchronized(session) { session.activeStream?.close(); session.activeStream = null }
        session.generating.set(false)
        return true
    }

    fun close(ownerUserId: UUID, sessionId: String): Boolean {
        val session = sessions[sessionId] ?: return false
        require(session.ownerUserId == ownerUserId) { "Inference session belongs to another user" }
        sessions.remove(sessionId, session)
        synchronized(session) { session.activeStream?.close(); session.activeStream = null; session.history.clear() }
        session.generating.set(false)
        return true
    }

    fun closeAll() {
        sessions.values.toList().forEach { session ->
            sessions.remove(session.id, session)
            synchronized(session) {
                session.activeStream?.close()
                session.activeStream = null
                session.history.clear()
            }
            session.generating.set(false)
        }
    }

    fun status(ownerUserId: UUID, ready: Boolean, contextLimit: Int): InferenceStatusResponse {
        val counters = countersByOwner[ownerUserId] ?: InferenceUsageCounters()
        val ownerSessions = sessions.values.filter { it.ownerUserId == ownerUserId }
        return InferenceStatusResponse(
            ready = ready,
            inputTokens = counters.input.get(),
            cachedInputTokens = counters.cachedInput.get(),
            outputTokens = counters.output.get(),
            totalTokens = counters.input.get() + counters.output.get(),
            contextTokens = ownerSessions.maxOfOrNull { session ->
                synchronized(session) { (session.systemPrompt.length + session.history.sumOf { it.toString().length }) / 4 }
            } ?: 0,
            contextLimit = contextLimit
        )
    }

    private fun cleanupExpired() {
        val cutoff = nowMillis() - idleTtlMillis
        sessions.values.filter { it.lastAccessMillis < cutoff && !it.generating.get() }.forEach { session ->
            if (sessions.remove(session.id, session)) synchronized(session) { session.history.clear() }
        }
    }

    private fun trimHistory(session: InferenceSession) {
        val maximumCharacters = availableHistoryCharacters(session)
        while (session.history.size > 2 && session.history.sumOf { it.toString().length } > maximumCharacters) {
            session.history.removeAt(0)
            if (session.history.isNotEmpty()) session.history.removeAt(0)
        }
        while (session.history.size > 64) session.history.removeAt(0)
    }

    private fun availableHistoryCharacters(session: InferenceSession): Int =
        ((session.contextTokens - session.maxOutputTokens) * 4 - session.systemPrompt.length).coerceAtLeast(1_024)
}

internal data class UpstreamStream(val statusCode: Int, val body: InputStream, val contentType: String?) : Closeable {
    override fun close() = body.close()
}

internal class OpenAiResponsesUpstream(
    baseUrl: String,
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()
) {
    private val baseUrl = baseUrl.trimEnd('/')
    @Volatile private var lastHealthAt = 0L
    @Volatile private var lastHealth = false

    fun ready(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastHealthAt < 1_500L) return lastHealth
        lastHealth = runCatching {
            val request = HttpRequest.newBuilder(URI.create("$baseUrl/health"))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build()
            http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() in 200..299
        }.getOrDefault(false)
        lastHealthAt = now
        return lastHealth
    }

    fun models(reload: Boolean = false): List<InferenceAdminModel> {
        val suffix = if (reload) "/models?reload=1" else "/models"
        val response = request("GET", suffix)
        val root = inferenceJson.parseToJsonElement(response).jsonObject
        return root["data"]?.jsonArray?.mapNotNull { item ->
            val value = item.jsonObject
            val id = value["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            InferenceAdminModel(
                id = id,
                status = value["status"]?.jsonObject?.get("value")?.jsonPrimitive?.contentOrNull ?: "unknown",
                source = value["source"]?.jsonPrimitive?.contentOrNull ?: "unknown",
                removable = value["can_remove"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
                inputModalities = value["architecture"]?.jsonObject?.get("input_modalities")?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: listOf("text")
            )
        }.orEmpty()
    }

    fun modelReady(model: String): Boolean = runCatching {
        models().any { it.id == model && it.status in setOf("loaded", "sleeping") }
    }.getOrDefault(false)

    fun loadIfNeeded(model: String, knownModels: List<InferenceAdminModel> = models()) {
        val selected = knownModels.firstOrNull { it.id == model } ?: throw IllegalArgumentException("Selected model is not installed")
        if (selected.status !in setOf("loading", "loaded", "sleeping", "downloading")) post("/models/load", """{"model":${jsonString(model)}}""")
    }

    fun unloadModel(model: String) {
        post("/models/unload", """{"model":${jsonString(model)}}""")
    }

    fun downloadModel(model: String) {
        post("/models", """{"model":${jsonString(model)}}""")
    }

    fun removeModel(model: String) {
        request("DELETE", "/models?model=${URLEncoder.encode(model, StandardCharsets.UTF_8)}")
    }

    fun open(body: String): UpstreamStream {
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/v1/responses"))
            .timeout(Duration.ofMinutes(3))
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        return UpstreamStream(response.statusCode(), response.body(), response.headers().firstValue("Content-Type").orElse(null))
    }

    private fun post(path: String, body: String): String = request("POST", path, body)

    private fun request(method: String, path: String, body: String? = null): String {
        val builder = HttpRequest.newBuilder(URI.create("$baseUrl$path"))
            .timeout(Duration.ofSeconds(20))
            .header("Accept", "application/json")
        if (body != null) builder.header("Content-Type", "application/json")
        builder.method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            val message = runCatching {
                inferenceJson.parseToJsonElement(response.body()).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            throw IllegalArgumentException(message ?: "Inference manager rejected the request")
        }
        return response.body()
    }

    private fun jsonString(value: String): String = buildJsonObject { put("value", value) }.toString()
        .removePrefix("{\"value\":")
        .removeSuffix("}")
}

internal fun Route.inferenceRoutes(
    config: ServerConfig,
    database: Database,
    limiter: AbuseLimiter,
    sessions: InferenceSessionRegistry,
    upstream: OpenAiResponsesUpstream,
    admin: InferenceAdminService
) {
    get("/v1/inference/status") {
        val auth = call.authenticate(database) ?: return@get
        call.noStoreHeader()
        if (!call.enforceRateLimit(limiter, "inference-status", auth.sessionId.toString(), INFERENCE_POLICY)) return@get
        val runtime = admin.settings()
        val ready = withContext(Dispatchers.IO) { admin.activeModelReady() }
        call.respond(sessions.status(auth.userId, ready, runtime.contextTokens))
    }

    post("/v1/inference/sessions") {
        val auth = call.authenticate(database) ?: return@post
        call.noStoreHeader()
        if (!call.enforceRateLimit(limiter, "inference-open", auth.sessionId.toString(), INFERENCE_POLICY)) return@post
        if (!withContext(Dispatchers.IO) { admin.activeModelReady() }) {
            withContext(Dispatchers.IO) { runCatching { admin.requestActiveModelLoad() } }
            return@post call.respond(HttpStatusCode.ServiceUnavailable, ApiError("INFERENCE_UNAVAILABLE", "The assistant is preparing. Try again shortly."))
        }
        val request = inferenceJson.decodeFromString<InferenceSessionOpenRequest>(call.receiveText())
        val session = sessions.open(auth.userId, request, admin.activeModel(), admin.settings())
        call.respond(HttpStatusCode.Created, InferenceSessionOpenResponse(session.id, "open", session.contextTokens))
    }

    post("/v1/inference/sessions/{sessionId}/cancel") {
        val auth = call.authenticate(database) ?: return@post
        call.noStoreHeader()
        val sessionId = call.parameters["sessionId"].orEmpty()
        sessions.cancel(auth.userId, sessionId)
        call.respond(HttpStatusCode.Accepted, buildJsonObject { put("status", "cancelled") })
    }

    delete("/v1/inference/sessions/{sessionId}") {
        val auth = call.authenticate(database) ?: return@delete
        call.noStoreHeader()
        val sessionId = call.parameters["sessionId"].orEmpty()
        sessions.close(auth.userId, sessionId)
        call.respond(HttpStatusCode.NoContent)
    }

    post("/v1/responses") {
        val auth = call.authenticate(database) ?: return@post
        call.noStoreHeader()
        if (!call.enforceRateLimit(limiter, "inference-response", auth.sessionId.toString(), INFERENCE_POLICY)) return@post
        val raw = call.receiveText()
        if (raw.encodeToByteArray().size > MAX_INFERENCE_REQUEST_BYTES) {
            return@post call.respond(HttpStatusCode.PayloadTooLarge, ApiError("PAYLOAD_TOO_LARGE", "Responses requests are limited to 64 KB"))
        }
        val request = runCatching { inferenceJson.parseToJsonElement(raw).jsonObject }
            .getOrElse { throw IllegalArgumentException("Responses request must be a JSON object") }
        require(request["tools"] == null || request["tools"] == JsonArray(emptyList())) { "Server-side tools are disabled; Finance tools run only on the phone" }
        val inputText = request["input"]?.let(::responsesInputText).orEmpty().trim()
        require(inputText.isNotBlank() && inputText.length <= MAX_INPUT_CHARS) { "Input must contain 1-$MAX_INPUT_CHARS characters" }
        val sessionId = call.request.headers["X-Nimbus-Session-Id"].orEmpty()
        require(sessionId.isNotBlank()) { "X-Nimbus-Session-Id is required" }
        val inputItem = buildJsonObject {
            put("role", "user")
            put("content", buildJsonArray { add(buildJsonObject { put("type", "input_text"); put("text", inputText) }) })
        }
        val (session, combinedHistory) = sessions.begin(auth.userId, sessionId, inputItem)
        val upstreamBody = buildUpstreamResponsesRequest(session, combinedHistory).toString()
        val stream = try {
            withContext(Dispatchers.IO) { upstream.open(upstreamBody) }
        } catch (failure: Throwable) {
            sessions.fail(session)
            throw failure
        }
        if (stream.statusCode !in 200..299) {
            val upstreamError = withContext(Dispatchers.IO) { stream.use { it.body.bufferedReader().readText().take(1_000) } }
            sessions.fail(session)
            environment.log.warn("Inference upstream returned {}: {}", stream.statusCode, upstreamError)
            return@post call.respond(HttpStatusCode.ServiceUnavailable, ApiError("INFERENCE_UPSTREAM_ERROR", "The private model could not start this response"))
        }
        sessions.attachStream(session, stream)
        var outputItems: List<JsonElement> = emptyList()
        var usage = InferenceUsage()
        var completed = false
        try {
            call.respondTextWriter(ContentType.Text.EventStream) {
                coroutineScope {
                    val lines = Channel<String>(64)
                    val producer = launch(Dispatchers.IO) {
                        runCatching {
                            stream.body.bufferedReader().use { reader ->
                                while (true) lines.send(reader.readLine() ?: break)
                            }
                        }.onSuccess { lines.close() }.onFailure(lines::close)
                    }
                    for (line in lines) {
                        var forwarded = line
                        var forwardLine = true
                        if (line.startsWith("data:")) {
                            val data = line.substringAfter("data:").trim()
                            if (data.isNotBlank() && data != "[DONE]") {
                                val event = runCatching { inferenceJson.parseToJsonElement(data).jsonObject }.getOrNull()
                                val type = event?.get("type")?.jsonPrimitive?.contentOrNull.orEmpty()
                                if (type == "response.completed") {
                                    val response = event?.get("response")?.jsonObject
                                    outputItems = response?.get("output")?.jsonArray?.toList().orEmpty()
                                        .filterNot(::isReasoningOutputItem)
                                    usage = response?.get("usage")?.jsonObject?.let(::parseUsage) ?: InferenceUsage()
                                    val enhanced = response?.let {
                                        JsonObject(it.filterKeys { key -> key !in SERVER_DETAIL_FIELDS && key != "output" } +
                                            ("output" to JsonArray(outputItems)) + ("nimbus_telemetry" to buildJsonObject {
                                            put("context_tokens", usage.inputTokens)
                                            put("context_limit", session.contextTokens)
                                        }))
                                    }
                                    if (enhanced != null) forwarded = "data: ${JsonObject(event.filterKeys { key -> key !in SERVER_DETAIL_FIELDS } + ("response" to enhanced))}"
                                    completed = true
                                } else if (event != null && isReasoningStreamEvent(type, event)) {
                                    // Internal reasoning is neither user output nor session history.
                                    // Dropping it also avoids needless private-link traffic.
                                    forwardLine = false
                                } else if (event != null) {
                                    val sanitizedResponse = event["response"]?.let { value ->
                                        (value as? JsonObject)?.let { JsonObject(it.filterKeys { key -> key !in SERVER_DETAIL_FIELDS }) } ?: value
                                    }
                                    val sanitized = JsonObject(event.filterKeys { key -> key !in SERVER_DETAIL_FIELDS } +
                                        if (sanitizedResponse != null) mapOf("response" to sanitizedResponse) else emptyMap())
                                    forwarded = "data: $sanitized"
                                }
                            }
                        }
                        if (forwardLine) {
                            write(forwarded)
                            write("\n")
                            if (line.isEmpty()) flush()
                        }
                    }
                    producer.join()
                }
            }
            if (completed) sessions.complete(session, inputItem, outputItems, usage) else sessions.fail(session)
        } catch (failure: Throwable) {
            sessions.fail(session)
            throw failure
        } finally {
            stream.close()
        }
    }
}

private val SERVER_DETAIL_FIELDS = setOf("model", "system_fingerprint", "timings", "backend", "engine")

private fun isReasoningOutputItem(item: JsonElement): Boolean =
    (item as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull == "reasoning"

private fun isReasoningStreamEvent(type: String, event: JsonObject): Boolean =
    type.startsWith("response.reasoning_") || event["item"]?.let(::isReasoningOutputItem) == true

internal fun buildUpstreamResponsesRequest(session: InferenceSession, history: List<JsonElement>): JsonObject = buildJsonObject {
    put("model", session.model)
    put("instructions", session.systemPrompt)
    put("input", JsonArray(history))
    put("stream", true)
    put("store", false)
    put("cache_prompt", true)
    put("max_output_tokens", session.maxOutputTokens)
    if (session.reasoningEffort == "none") put("reasoning", buildJsonObject { put("effort", "none") })
    put("temperature", session.temperature)
    put("top_k", session.topK)
    put("top_p", session.topP)
    put("seed", session.seed)
    put("parallel_tool_calls", false)
    if (session.responseFormat) put("text", buildJsonObject {
        put("format", buildJsonObject { put("type", "json_object") })
    })
    put("metadata", buildJsonObject { put("nimbus_session_id", session.id) })
}

private fun responsesInputText(input: JsonElement): String = when (input) {
    is JsonPrimitive -> input.contentOrNull.orEmpty()
    is JsonArray -> input.joinToString("\n") { item ->
        val objectItem = item as? JsonObject ?: return@joinToString ""
        val content = objectItem["content"]
        when (content) {
            is JsonPrimitive -> content.contentOrNull.orEmpty()
            is JsonArray -> content.joinToString("\n") { block ->
                (block as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
            }
            else -> objectItem["output"]?.jsonPrimitive?.contentOrNull.orEmpty()
        }
    }
    else -> ""
}

private fun parseUsage(value: JsonObject): InferenceUsage {
    val input = value["input_tokens"]?.jsonPrimitive?.longOrNull
        ?: value["prompt_tokens"]?.jsonPrimitive?.longOrNull ?: 0L
    val output = value["output_tokens"]?.jsonPrimitive?.longOrNull
        ?: value["completion_tokens"]?.jsonPrimitive?.longOrNull ?: 0L
    val cached = value["input_tokens_details"]?.jsonObject?.get("cached_tokens")?.jsonPrimitive?.longOrNull
        ?: value["prompt_tokens_details"]?.jsonObject?.get("cached_tokens")?.jsonPrimitive?.longOrNull ?: 0L
    val total = value["total_tokens"]?.jsonPrimitive?.longOrNull ?: input + output
    return InferenceUsage(input, cached, output, total)
}
