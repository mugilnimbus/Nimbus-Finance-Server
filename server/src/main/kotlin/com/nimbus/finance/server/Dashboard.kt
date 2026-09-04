package com.nimbus.finance.server

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.request.host
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.delete
import kotlinx.serialization.Serializable
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

private const val ADMIN_COOKIE = "nimbus_dashboard_admin"

internal class DashboardAccess(private val adminKey: String) {
    private val launchTokens = ConcurrentHashMap<String, Instant>()
    private val sessions = ConcurrentHashMap<String, Instant>()

    fun createLaunchToken(candidate: String): String? {
        if (!constantTimeEquals(candidate, adminKey)) return null
        cleanup()
        return randomToken().also { launchTokens[sha256(it)] = Instant.now().plus(60, ChronoUnit.SECONDS) }
    }

    fun redeemLaunchToken(token: String): String? {
        cleanup()
        val expiry = launchTokens.remove(sha256(token)) ?: return null
        return if (expiry.isAfter(Instant.now())) createSession() else null
    }

    fun login(candidate: String): String? = if (constantTimeEquals(candidate, adminKey)) createSession() else null

    fun isAuthenticated(token: String?): Boolean {
        cleanup()
        return token?.let { sessions[sha256(it)]?.isAfter(Instant.now()) } == true
    }

    fun logout(token: String?) {
        token?.let { sessions.remove(sha256(it)) }
    }

    private fun createSession(): String = randomToken().also {
        sessions[sha256(it)] = Instant.now().plus(30, ChronoUnit.MINUTES)
    }

    private fun cleanup() {
        val now = Instant.now()
        launchTokens.entries.removeIf { !it.value.isAfter(now) }
        sessions.entries.removeIf { !it.value.isAfter(now) }
    }
}

internal fun Route.dashboardRoutes(config: ServerConfig, database: Database, access: DashboardAccess, limiter: AbuseLimiter) {
    get("/dashboard") { call.respondDashboardResource("dashboard/index.html", ContentType.Text.Html) }
    get("/dashboard/app.css") { call.respondDashboardResource("dashboard/app.css", ContentType.Text.CSS) }
    get("/dashboard/admin.css") { call.respondDashboardResource("dashboard/admin.css", ContentType.Text.CSS) }
    get("/dashboard/app.js") { call.respondDashboardResource("dashboard/app.js", ContentType.Application.JavaScript) }
    get("/dashboard/icon.svg") { call.respondDashboardResource("dashboard/icon.svg", ContentType.parse("image/svg+xml")) }

    get("/v1/dashboard/config") {
        call.noStore()
        call.respond(DashboardConfig(config.buildVersion, config.publicServerUrl))
    }
    get("/v1/dashboard/qr.svg") {
        val value = call.request.queryParameters["value"]?.trim().orEmpty()
        require(value.length in 1..1_024) { "QR value must be 1-1024 characters" }
        call.noStore()
        call.respondText(qrSvg(value), ContentType.parse("image/svg+xml"))
    }

    post("/v1/dashboard/admin/launch") {
        if (!call.enforceRateLimit(limiter, "dashboard-launch", call.clientAddress(), ADMIN_POLICY)) return@post
        val key = call.request.headers["X-Admin-Key"].orEmpty()
        val token = access.createLaunchToken(key)
            ?: return@post call.respond(HttpStatusCode.Unauthorized, ApiError("UNAUTHORIZED", "Administrator key is invalid"))
        call.noStore()
        call.respond(DashboardLaunchResponse(token))
    }
    post("/v1/dashboard/admin/redeem") {
        val request = call.receive<DashboardRedeemRequest>()
        if (!call.enforceRateLimit(limiter, "dashboard-redeem", "${call.clientAddress()}:${request.token}", ADMIN_POLICY)) return@post
        val session = access.redeemLaunchToken(request.token)
            ?: return@post call.respond(HttpStatusCode.Unauthorized, ApiError("UNAUTHORIZED", "Dashboard launch link is invalid or expired"))
        call.setAdminCookie(session, config)
        call.respond(HttpStatusCode.NoContent)
    }
    post("/v1/dashboard/admin/login") {
        val request = call.receive<DashboardAdminLoginRequest>()
        if (!call.enforceRateLimit(limiter, "dashboard-login", call.clientAddress(), ADMIN_POLICY)) return@post
        val session = access.login(request.adminKey)
            ?: return@post call.respond(HttpStatusCode.Unauthorized, ApiError("UNAUTHORIZED", "Administrator key is invalid"))
        call.setAdminCookie(session, config)
        call.respond(HttpStatusCode.NoContent)
    }
    post("/v1/dashboard/admin/logout") {
        access.logout(call.request.cookies[ADMIN_COOKIE])
        call.expireAdminCookie()
        call.respond(HttpStatusCode.NoContent)
    }
    get("/v1/dashboard/admin/summary") {
        if (!access.isAuthenticated(call.request.cookies[ADMIN_COOKIE])) return@get call.adminRequired()
        database.purgeExpiredTrash()
        call.noStore()
        call.respond(database.dashboardSummary())
    }
    post("/v1/dashboard/admin/registration-invites") {
        if (!access.isAuthenticated(call.request.cookies[ADMIN_COOKIE])) return@post call.adminRequired()
        val request = call.receive<DashboardInviteRequest>()
        val serverUrl = normalizeServerUrl(request.serverUrl.ifBlank { config.publicServerUrl })
        val expiresHours = request.expiresInHours.coerceIn(1, 168)
        val maxUses = request.maxUses.coerceIn(1, 20)
        val code = randomCode(20)
        val expiresAt = Instant.now().plus(expiresHours.toLong(), ChronoUnit.HOURS).toString()
        database.createRegistrationInvite(code, expiresHours, maxUses)
        call.noStore()
        call.respond(
            HttpStatusCode.Created,
            DashboardInviteResponse(code, expiresAt, enrollmentDeepLink(serverUrl, code))
        )
    }
    post("/v1/dashboard/admin/users/{username}/status") {
        if (!access.isAuthenticated(call.request.cookies[ADMIN_COOKIE])) return@post call.adminRequired()
        val username = call.parameters["username"]?.trim().orEmpty()
        require(username.matches(Regex("[A-Za-z0-9._-]{3,64}"))) { "Enter a valid username" }
        val request = call.receive<DashboardUserStatusRequest>()
        if (!database.setUserEnabled(username, request.enabled)) {
            return@post call.respond(HttpStatusCode.NotFound, ApiError("USER_NOT_FOUND", "User account was not found"))
        }
        call.noStore()
        call.respond(database.dashboardSummary())
    }
    delete("/v1/dashboard/admin/users/{username}") {
        if (!access.isAuthenticated(call.request.cookies[ADMIN_COOKIE])) return@delete call.adminRequired()
        val username = call.parameters["username"]?.trim().orEmpty()
        require(username.matches(Regex("[A-Za-z0-9._-]{3,64}"))) { "Enter a valid username" }
        val request = call.receive<DashboardDeleteUserRequest>()
        require(request.confirmation.equals(username, true)) { "Type the username exactly to confirm permanent deletion" }
        if (!database.deleteUserPermanently(username)) {
            return@delete call.respond(HttpStatusCode.NotFound, ApiError("USER_NOT_FOUND", "User account was not found"))
        }
        call.noStore()
        call.respond(database.dashboardSummary())
    }
}

private suspend fun ApplicationCall.respondDashboardResource(name: String, contentType: ContentType) {
    val text = requireNotNull(javaClass.classLoader.getResourceAsStream(name)) { "Dashboard resource is missing: $name" }
        .bufferedReader().use { it.readText() }
    response.headers.append("Content-Security-Policy", "default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'")
    response.headers.append("X-Content-Type-Options", "nosniff")
    noStore()
    respondText(text, contentType)
}

private fun ApplicationCall.noStore() {
    response.headers.append(HttpHeaders.CacheControl, "no-store")
}

private suspend fun ApplicationCall.adminRequired() =
    respond(HttpStatusCode.Unauthorized, ApiError("ADMIN_SIGN_IN_REQUIRED", "Open the owner dashboard again or enter the administrator key"))

private fun ApplicationCall.setAdminCookie(value: String, config: ServerConfig) {
    val secure = adminCookieRequiresSecure(request.host(), config.publicServerUrl)
    response.cookies.append(
        Cookie(ADMIN_COOKIE, value, maxAge = 1_800, path = "/", secure = secure, httpOnly = true, extensions = mapOf("SameSite" to "Strict"))
    )
}

internal fun adminCookieRequiresSecure(requestHost: String, publicServerUrl: String): Boolean {
    val normalizedHost = requestHost.lowercase()
    val isLoopbackHost = normalizedHost in setOf("localhost", "127.0.0.1", "::1", "[::1]")
    return !isLoopbackHost && publicServerUrl.startsWith("https://", ignoreCase = true)
}

private fun ApplicationCall.expireAdminCookie() {
    response.cookies.append(
        Cookie(ADMIN_COOKIE, "", maxAge = 0, path = "/", httpOnly = true, extensions = mapOf("SameSite" to "Strict"))
    )
}

internal fun normalizeServerUrl(value: String): String {
    val normalized = value.trim().trimEnd('/')
    val uri = runCatching { URI(normalized) }.getOrElse { throw IllegalArgumentException("Enter a valid server address") }
    require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) {
        "Server address must be a private HTTPS origin"
    }
    return normalized
}

internal fun enrollmentDeepLink(serverUrl: String, inviteCode: String? = null): String = buildString {
    append("nimbus://connect?server=")
    append(URLEncoder.encode(normalizeServerUrl(serverUrl), StandardCharsets.UTF_8.name()))
    inviteCode?.takeIf(String::isNotBlank)?.let {
        append("&invite=")
        append(URLEncoder.encode(it.trim().uppercase(), StandardCharsets.UTF_8.name()))
    }
}

internal fun qrSvg(value: String): String {
    val matrix = QRCodeWriter().encode(
        value,
        BarcodeFormat.QR_CODE,
        0,
        0,
        mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
    )
    val path = buildString {
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix[x, y]) append("M$x $y" + "h1v1h-1z")
        }
    }
    return """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${matrix.width} ${matrix.height}" shape-rendering="crispEdges" role="img" aria-label="Nimbus enrollment QR code"><rect width="100%" height="100%" fill="#ffffff"/><path d="$path" fill="#2e1065"/></svg>"""
}

@Serializable data class DashboardConfig(val version: String, val serverUrl: String)
@Serializable data class DashboardLaunchResponse(val token: String)
@Serializable data class DashboardRedeemRequest(val token: String)
@Serializable data class DashboardAdminLoginRequest(val adminKey: String)
@Serializable data class DashboardInviteRequest(val serverUrl: String = "", val expiresInHours: Int = 24, val maxUses: Int = 1)
@Serializable data class DashboardInviteResponse(val code: String, val expiresAt: String, val deepLink: String)
@Serializable data class DashboardUserStatusRequest(val enabled: Boolean)
@Serializable data class DashboardDeleteUserRequest(val confirmation: String)
@Serializable data class DashboardUser(val username: String, val displayName: String, val status: String, val createdAt: String, val activeSessions: Int, val purgeAfter: String? = null)
@Serializable data class DashboardSummary(val users: Int, val disabledUsers: Int, val trashedUsers: Int, val activeSessions: Int, val availableInvites: Int, val accounts: List<DashboardUser>)
