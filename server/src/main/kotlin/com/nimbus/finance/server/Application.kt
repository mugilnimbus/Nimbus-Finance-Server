package com.nimbus.finance.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import de.mkammerer.argon2.Argon2Factory
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import org.slf4j.event.Level
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val apiJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

fun main() {
    val config = ServerConfig.fromEnvironment()
    embeddedServer(Netty, host = "0.0.0.0", port = config.port) { module(config) }.start(wait = true)
}

fun Application.module(config: ServerConfig) {
    val database = Database(config)
    val dashboardAccess = DashboardAccess(config.adminKey)
    val abuseLimiter = AbuseLimiter()
    val inferenceSessions = InferenceSessionRegistry(
        idleTtlMillis = config.inferenceSessionIdleMinutes.coerceIn(5, 240) * 60_000L,
        maxSessionsPerUser = config.inferenceMaxSessionsPerUser.coerceIn(1, 8)
    )
    val inferenceUpstream = OpenAiResponsesUpstream(config.inferenceBaseUrl)
    val inferenceAdmin = InferenceAdminService(
        configDirectory = Path.of(config.inferenceConfigDirectory),
        modelsDirectory = Path.of(config.inferenceModelsDirectory),
        upstream = inferenceUpstream,
        sessions = inferenceSessions
    )
    val trashPurger = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "nimbus-trash-purger").apply { isDaemon = true }
    }
    trashPurger.scheduleWithFixedDelay(
        { runCatching { database.purgeExpiredTrash() }.onFailure { environment.log.error("Trash purge failed", it) } },
        1,
        1,
        TimeUnit.HOURS
    )
    monitor.subscribe(ApplicationStopped) {
        trashPurger.shutdownNow()
        database.close()
    }

    install(ContentNegotiation) { json(apiJson) }
    install(CallLogging) { level = Level.INFO }
    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_REQUEST", cause.message ?: "Invalid request"))
        }
        exception<Throwable> { call, cause ->
            this@module.environment.log.error("Unhandled request failure", cause)
            call.respond(HttpStatusCode.InternalServerError, ApiError("INTERNAL_ERROR", "The server could not complete the request"))
        }
    }

    routing {
        dashboardRoutes(config, database, dashboardAccess, abuseLimiter)
        inferenceAdminRoutes(dashboardAccess, abuseLimiter, inferenceAdmin)
        inferenceRoutes(config, database, abuseLimiter, inferenceSessions, inferenceUpstream, inferenceAdmin)
        get("/health/live") { call.respond(config.health("live")) }
        get("/health/ready") {
            if (database.ready()) call.respond(config.health("ready"))
            else call.respond(HttpStatusCode.ServiceUnavailable, ApiError("DATABASE_UNAVAILABLE", "Database is not ready"))
        }

        post("/v1/admin/registration-invites") {
            if (!call.enforceRateLimit(abuseLimiter, "admin-api", call.clientAddress(), ADMIN_POLICY)) return@post
            if (!constantTimeEquals(call.request.headers["X-Admin-Key"] ?: "", config.adminKey)) {
                return@post call.respond(HttpStatusCode.Unauthorized, ApiError("UNAUTHORIZED", "Administrator key is invalid"))
            }
            val request = runCatching { call.receive<AdminInviteRequest>() }.getOrDefault(AdminInviteRequest())
            val code = randomCode(20)
            database.createRegistrationInvite(code, request.expiresInHours.coerceIn(1, 168), request.maxUses.coerceIn(1, 20))
            call.respond(HttpStatusCode.Created, InviteResponse(code, Instant.now().plus(request.expiresInHours.coerceIn(1, 168).toLong(), ChronoUnit.HOURS).toString()))
        }

        post("/v1/auth/register") {
            val request = call.receive<RegisterRequest>()
            if (!call.enforceAuthLimits(abuseLimiter, "register", request.username)) return@post
            validateCredentials(request.username, request.password, request.displayName)
            val result = database.register(request)
            call.respond(HttpStatusCode.Created, result)
        }

        get("/v1/auth/username-availability") {
            if (!call.enforceRateLimit(abuseLimiter, "username-lookup", call.clientAddress(), PUBLIC_LOOKUP_POLICY)) return@get
            val username = call.request.queryParameters["username"].orEmpty().trim()
            val valid = username.matches(USERNAME_PATTERN)
            val available = valid && database.usernameAvailable(username)
            call.noStoreHeader()
            call.respond(
                UsernameAvailabilityResponse(
                    username = username,
                    available = available,
                    valid = valid,
                    message = when {
                        username.isBlank() -> "Enter a username"
                        !valid -> "Use 3-64 letters, numbers, dots, underscores, or hyphens"
                        !available -> "Username is already taken"
                        else -> "Username is available"
                    }
                )
            )
        }

        post("/v1/auth/login") {
            val request = call.receive<LoginRequest>()
            if (!call.enforceAuthLimits(abuseLimiter, "login", request.username)) return@post
            call.respond(database.login(request) ?: return@post call.respond(HttpStatusCode.Unauthorized, ApiError("INVALID_CREDENTIALS", "Username or password is incorrect")))
        }

        post("/v1/auth/refresh") {
            val request = call.receive<RefreshRequest>()
            if (!call.enforceRateLimit(abuseLimiter, "refresh-ip", call.clientAddress(), AUTH_IP_POLICY) ||
                !call.enforceRateLimit(abuseLimiter, "refresh-token", request.refreshToken, AUTH_IDENTITY_POLICY)) return@post
            call.respond(database.refresh(request.refreshToken) ?: return@post call.respond(HttpStatusCode.Unauthorized, ApiError("SESSION_EXPIRED", "Please sign in again")))
        }

        post("/v1/auth/logout") {
            val auth = call.authenticate(database) ?: return@post
            database.revokeSession(auth.sessionId)
            call.respond(HttpStatusCode.NoContent)
        }

        get("/v1/me") {
            val auth = call.authenticate(database) ?: return@get
            call.respond(UserResponse(auth.userId.toString(), auth.username, auth.displayName))
        }

        post("/v1/me/profile") {
            val auth = call.authenticate(database) ?: return@post
            val request = call.receive<UpdateProfileRequest>()
            require(request.displayName.trim().length in 1..80) { "Name must be 1-80 characters" }
            call.respond(database.updateDisplayName(auth, request.displayName.trim()))
        }

        delete("/v1/me/account") {
            val auth = call.authenticate(database) ?: return@delete
            val request = call.receive<DeleteOwnAccountRequest>()
            if (!call.enforceRateLimit(abuseLimiter, "delete-account", auth.userId.toString(), ADMIN_POLICY)) return@delete
            require(request.username.trim().equals(auth.username, true)) { "Type your username exactly to confirm" }
            if (!database.verifyPassword(auth.userId, request.password)) {
                return@delete call.respond(HttpStatusCode.Forbidden, ApiError("INVALID_PASSWORD", "Password is incorrect"))
            }
            when (request.mode.uppercase()) {
                "TRASH" -> call.respond(AccountDeletionResponse("TRASHED", database.moveUserToTrash(auth.userId)))
                "PERMANENT" -> {
                    database.deleteUserPermanently(auth.username)
                    call.respond(AccountDeletionResponse("DELETED", null))
                }
                else -> throw IllegalArgumentException("Deletion mode must be TRASH or PERMANENT")
            }
        }

        post("/v1/registration-invites") {
            val auth = call.authenticate(database) ?: return@post
            val request = runCatching { call.receive<UserInviteRequest>() }.getOrDefault(UserInviteRequest())
            val hours = request.expiresInHours.coerceIn(1, 72)
            val code = randomCode(20)
            if (!database.createUserRegistrationInvite(auth, code, hours)) {
                return@post call.respond(HttpStatusCode.TooManyRequests, ApiError("INVITE_LIMIT", "You can create up to 5 account invitations per day"))
            }
            call.respond(HttpStatusCode.Created, InviteResponse(code, Instant.now().plus(hours.toLong(), ChronoUnit.HOURS).toString()))
        }

        post("/v1/groups/{groupId}/invites") {
            val auth = call.authenticate(database) ?: return@post
            val groupId = call.parameters["groupId"].asUuid("groupId")
            if (!database.isGroupAdmin(auth.userId, groupId)) {
                return@post call.respond(HttpStatusCode.Forbidden, ApiError("FORBIDDEN", "Only a group administrator can invite members"))
            }
            val request = runCatching { call.receive<GroupInviteRequest>() }.getOrDefault(GroupInviteRequest())
            val code = randomCode(16)
            database.createGroupInvite(auth.userId, groupId, code, request.expiresInHours.coerceIn(1, 168))
            call.respond(HttpStatusCode.Created, InviteResponse(code, Instant.now().plus(request.expiresInHours.coerceIn(1, 168).toLong(), ChronoUnit.HOURS).toString()))
        }

        post("/v1/group-invites/accept") {
            val auth = call.authenticate(database) ?: return@post
            val request = call.receive<AcceptInviteRequest>()
            if (!call.enforceRateLimit(abuseLimiter, "group-invite", "${auth.userId}:${request.code}", AUTH_IDENTITY_POLICY)) return@post
            val groupId = database.acceptGroupInvite(auth, request.code)
                ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_INVITE", "The invitation is invalid, expired, or already used"))
            call.respond(AcceptInviteResponse(groupId.toString(), true))
        }

        post("/v1/sync/push") {
            val auth = call.authenticate(database) ?: return@post
            if (!call.enforceRateLimit(abuseLimiter, "sync-push", auth.sessionId.toString(), SYNC_POLICY)) return@post
            val contentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (contentLength == null || contentLength > MAX_SYNC_REQUEST_BYTES) {
                return@post call.respond(HttpStatusCode.PayloadTooLarge, ApiError("PAYLOAD_TOO_LARGE", "Sync requests must include Content-Length and be at most 24 MB"))
            }
            val request = call.receive<SyncPushRequest>()
            require(request.operations.size <= 100) { "At most 100 operations may be pushed at once" }
            call.respond(SyncPushResponse(database.applyOperations(auth, request.operations)))
        }

        get("/v1/sync/pull") {
            val auth = call.authenticate(database) ?: return@get
            if (!call.enforceRateLimit(abuseLimiter, "sync-pull", auth.sessionId.toString(), SYNC_POLICY)) return@get
            val after = call.request.queryParameters["after"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 200
            call.respond(database.pull(auth.userId, after, limit))
        }

        get("/v1/sync/bootstrap") {
            val auth = call.authenticate(database) ?: return@get
            if (!call.enforceRateLimit(abuseLimiter, "sync-bootstrap", auth.sessionId.toString(), SYNC_POLICY)) return@get
            val after = call.request.queryParameters["after"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 200
            call.respond(database.bootstrap(auth.userId, after, limit))
        }
    }
}

private fun ServerConfig.health(status: String) = HealthResponse(
    status = status,
    version = buildVersion,
    revision = buildRevision,
    builtAt = buildTime,
    schemaVersion = schemaVersion
)

internal fun ApplicationCall.clientAddress(): String = request.local.remoteAddress.ifBlank { "unknown" }

internal suspend fun ApplicationCall.enforceRateLimit(
    limiter: AbuseLimiter,
    namespace: String,
    key: String,
    policy: RateLimitPolicy
): Boolean {
    val retryAfter = limiter.retryAfterSeconds(namespace, key, policy) ?: return true
    response.headers.append(HttpHeaders.RetryAfter, retryAfter.toString())
    respond(HttpStatusCode.TooManyRequests, ApiError("RATE_LIMITED", "Too many requests. Try again later."))
    return false
}

private suspend fun ApplicationCall.enforceAuthLimits(limiter: AbuseLimiter, operation: String, identity: String): Boolean =
    enforceRateLimit(limiter, "$operation-ip", clientAddress(), AUTH_IP_POLICY) &&
        enforceRateLimit(limiter, "$operation-identity", identity.trim().lowercase(), AUTH_IDENTITY_POLICY)

internal suspend fun ApplicationCall.authenticate(database: Database): AuthUser? {
    val header = request.headers[HttpHeaders.Authorization]
    val raw = header?.takeIf { it.startsWith("Bearer ", true) }?.substringAfter(' ')?.trim()
    val auth = raw?.let(database::authenticate)
    if (auth == null) respond(HttpStatusCode.Unauthorized, ApiError("UNAUTHORIZED", "A valid access token is required"))
    return auth
}

private class BatchRejected(val results: List<SyncOperationResult>) : RuntimeException("Sync batch was rolled back")

class Database(config: ServerConfig) : AutoCloseable {
    private val dataSource = HikariDataSource(HikariConfig().apply {
        jdbcUrl = config.jdbcUrl
        username = config.dbUser
        password = config.dbPassword
        maximumPoolSize = 8
        minimumIdle = 1
        connectionTimeout = 10_000
    })

    init { migrate(); purgeExpiredTrash() }

    fun ready(): Boolean = runCatching { dataSource.connection.use { it.isValid(2) } }.getOrDefault(false)
    fun dashboardSummary(): DashboardSummary = query { connection ->
        val counts = connection.createStatement().use { statement ->
            statement.executeQuery("""
                SELECT
                    (SELECT count(*) FROM users WHERE status='ACTIVE'),
                    (SELECT count(*) FROM users WHERE status='DISABLED'),
                    (SELECT count(*) FROM users WHERE status='TRASHED'),
                    (SELECT count(*) FROM device_sessions WHERE revoked_at IS NULL AND refresh_expires_at>now()),
                    (SELECT count(*) FROM registration_invites WHERE revoked_at IS NULL AND expires_at>now() AND use_count<max_uses)
            """.trimIndent()).use { rs -> rs.next(); listOf(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5)) }
        }
        val users = connection.createStatement().use { statement ->
            statement.executeQuery("""
                SELECT u.username, u.display_name, u.status, u.created_at,
                    count(s.id) FILTER (WHERE s.revoked_at IS NULL AND s.refresh_expires_at>now())
                    ,u.purge_after
                FROM users u LEFT JOIN device_sessions s ON s.user_id=u.id
                GROUP BY u.id, u.username, u.display_name, u.status, u.created_at, u.purge_after
                ORDER BY u.created_at DESC
                LIMIT 100
            """.trimIndent()).use { rs ->
                buildList {
                    while (rs.next()) add(DashboardUser(rs.getString(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant().toString(), rs.getInt(5), rs.getTimestamp(6)?.toInstant()?.toString()))
                }
            }
        }
        DashboardSummary(counts[0], counts[1], counts[2], counts[3], counts[4], users)
    }

    fun usernameAvailable(username: String): Boolean = query { connection ->
        connection.prepareStatement("SELECT NOT EXISTS(SELECT 1 FROM users WHERE username=?)").use {
            it.setString(1, username.trim()); it.executeQuery().use { rs -> rs.next(); rs.getBoolean(1) }
        }
    }

    fun updateDisplayName(auth: AuthUser, displayName: String): UserResponse = tx { connection ->
        connection.prepareStatement("UPDATE users SET display_name=?, updated_at=now() WHERE id=?").use {
            it.setString(1, displayName); it.setObject(2, auth.userId); it.executeUpdate()
        }
        connection.audit(auth.userId, "PROFILE_UPDATED", "SUCCESS")
        UserResponse(auth.userId.toString(), auth.username, displayName)
    }

    fun setUserEnabled(username: String, enabled: Boolean): Boolean = tx { connection ->
        val userId = connection.prepareStatement("SELECT id FROM users WHERE username=? FOR UPDATE").use {
            it.setString(1, username.trim()); it.executeQuery().use { rs -> if (rs.next()) rs.getObject(1, UUID::class.java) else null }
        } ?: return@tx false
        connection.prepareStatement("UPDATE users SET status=?, deleted_at=NULL, purge_after=NULL, updated_at=now() WHERE id=?").use {
            it.setString(1, if (enabled) "ACTIVE" else "DISABLED"); it.setObject(2, userId); it.executeUpdate()
        }
        if (!enabled) connection.prepareStatement("UPDATE device_sessions SET revoked_at=COALESCE(revoked_at,now()) WHERE user_id=?").use {
            it.setObject(1, userId); it.executeUpdate()
        }
        connection.audit(userId, if (enabled) "ACCOUNT_RESTORED" else "ACCOUNT_DISABLED", "ADMIN")
        true
    }

    fun verifyPassword(userId: UUID, password: String): Boolean = query { connection ->
        connection.prepareStatement("SELECT password_hash FROM users WHERE id=?").use {
            it.setObject(1, userId); it.executeQuery().use { rs -> rs.next() && Passwords.verify(rs.getString(1), password) }
        }
    }

    fun moveUserToTrash(userId: UUID): String = tx { connection ->
        val purgeAfter = Instant.now().plus(90, ChronoUnit.DAYS)
        val changed = connection.prepareStatement("UPDATE users SET status='TRASHED',deleted_at=now(),purge_after=?,updated_at=now() WHERE id=? AND status<>'TRASHED'").use {
            it.setTimestamp(1, java.sql.Timestamp.from(purgeAfter)); it.setObject(2, userId); it.executeUpdate()
        }
        require(changed == 1) { "Account is already in Trash" }
        connection.prepareStatement("UPDATE device_sessions SET revoked_at=COALESCE(revoked_at,now()) WHERE user_id=?").use { it.setObject(1, userId); it.executeUpdate() }
        connection.audit(userId, "ACCOUNT_TRASHED", "SELF")
        purgeAfter.toString()
    }

    fun deleteUserPermanently(username: String): Boolean = tx { connection ->
        val target = connection.prepareStatement("SELECT id,username,display_name FROM users WHERE username=? FOR UPDATE").use {
            it.setString(1, username.trim()); it.executeQuery().use { rs ->
                if (rs.next()) Triple(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3)) else null
            }
        } ?: return@tx false
        val userId = target.first
        val groups = connection.prepareStatement("SELECT DISTINCT group_id FROM group_members WHERE user_id=? UNION SELECT id FROM finance_groups WHERE created_by_user_id=?").use {
            it.setObject(1, userId); it.setObject(2, userId); it.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getObject(1, UUID::class.java)) } }
        }
        groups.forEach { groupId ->
            val replacement = connection.prepareStatement("""
                SELECT gm.user_id FROM group_members gm JOIN users u ON u.id=gm.user_id
                WHERE gm.group_id=? AND gm.user_id<>? AND gm.left_at IS NULL AND u.status IN ('ACTIVE','DISABLED')
                ORDER BY CASE gm.role WHEN 'ADMIN' THEN 0 ELSE 1 END, gm.joined_at LIMIT 1
            """.trimIndent()).use {
                it.setObject(1, groupId); it.setObject(2, userId); it.executeQuery().use { rs -> if (rs.next()) rs.getObject(1, UUID::class.java) else null }
            }
            if (replacement == null) {
                connection.prepareStatement("DELETE FROM change_log WHERE scope_type='GROUP' AND scope_id=?").use { it.setObject(1, groupId); it.executeUpdate() }
                connection.prepareStatement("DELETE FROM sync_entities WHERE scope_type='GROUP' AND scope_id=?").use { it.setObject(1, groupId); it.executeUpdate() }
                connection.prepareStatement("DELETE FROM finance_groups WHERE id=?").use { it.setObject(1, groupId); it.executeUpdate() }
            } else {
                connection.anonymizeGroupUser(groupId, userId, target.third)
                connection.prepareStatement("UPDATE group_members SET role='ADMIN' WHERE group_id=? AND user_id=?").use { it.setObject(1, groupId); it.setObject(2, replacement); it.executeUpdate() }
                connection.prepareStatement("UPDATE finance_groups SET created_by_user_id=? WHERE id=? AND created_by_user_id=?").use { it.setObject(1, replacement); it.setObject(2, groupId); it.setObject(3, userId); it.executeUpdate() }
                connection.prepareStatement("UPDATE sync_entities SET owner_user_id=? WHERE scope_type='GROUP' AND scope_id=? AND owner_user_id=?").use { it.setObject(1, replacement); it.setObject(2, groupId); it.setObject(3, userId); it.executeUpdate() }
                connection.prepareStatement("DELETE FROM group_members WHERE group_id=? AND user_id=?").use { it.setObject(1, groupId); it.setObject(2, userId); it.executeUpdate() }
                val memberPayload = buildJsonObject {
                    put("userId", userId.toString())
                    put("username", "deleted-${userId.toString().take(6)}")
                    put("displayName", deletedMemberLabel(userId))
                }
                connection.appendSystemChange("GROUP", groupId, "GROUP_MEMBER", userId, "DELETE", 1, memberPayload)
            }
        }
        connection.prepareStatement("DELETE FROM group_invites WHERE created_by_user_id=?").use { it.setObject(1, userId); it.executeUpdate() }
        connection.prepareStatement("DELETE FROM change_log WHERE scope_type='PERSONAL' AND scope_id=?").use { it.setObject(1, userId); it.executeUpdate() }
        connection.prepareStatement("DELETE FROM sync_entities WHERE scope_type='PERSONAL' AND scope_id=?").use { it.setObject(1, userId); it.executeUpdate() }
        connection.prepareStatement("DELETE FROM audit_events WHERE user_id=?").use { it.setObject(1, userId); it.executeUpdate() }
        connection.prepareStatement("DELETE FROM users WHERE id=?").use { it.setObject(1, userId); it.executeUpdate() }
        true
    }

    fun purgeExpiredTrash(): Int {
        val expired = query { connection -> connection.prepareStatement("SELECT username FROM users WHERE status='TRASHED' AND purge_after<=now()").use {
            it.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        } }
        expired.forEach(::deleteUserPermanently)
        return expired.size
    }
    override fun close() = dataSource.close()

    private fun migrate() {
        tx { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE IF NOT EXISTS schema_migrations(version INTEGER PRIMARY KEY, checksum TEXT NOT NULL, applied_at TIMESTAMPTZ NOT NULL DEFAULT now())")
            }
            listOf(1 to "001_initial_schema.sql", 2 to "002_account_trash_and_deletion.sql").forEach { (version, file) ->
                val resource = requireNotNull(javaClass.classLoader.getResourceAsStream("db/migration/$file")) { "Database migration resource is missing: $file" }
                val sql = resource.bufferedReader().use { it.readText() }
                val checksum = sha256(sql)
                val applied = connection.prepareStatement("SELECT checksum FROM schema_migrations WHERE version=?").use { statement ->
                    statement.setInt(1, version)
                    statement.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
                }
                if (applied != null) {
                    check(constantTimeEquals(applied, checksum)) { "Database migration $version checksum does not match the applied version" }
                } else {
                    sql.split(Regex(";\\s*(?:\\r?\\n|$)"))
                        .map(String::trim)
                        .filter(String::isNotBlank)
                        .forEach { statementSql -> connection.createStatement().use { it.execute(statementSql) } }
                    connection.prepareStatement("INSERT INTO schema_migrations(version,checksum) VALUES (?,?)").use {
                        it.setInt(1, version); it.setString(2, checksum); it.executeUpdate()
                    }
                }
            }
        }
    }

    fun createRegistrationInvite(code: String, expiresHours: Int, maxUses: Int) = tx { connection ->
        connection.prepareStatement("INSERT INTO registration_invites(id, code_hash, expires_at, max_uses) VALUES (?, ?, now() + (? || ' hours')::interval, ?)").use {
            it.setObject(1, UUID.randomUUID()); it.setString(2, sha256(code)); it.setInt(3, expiresHours); it.setInt(4, maxUses); it.executeUpdate()
        }
    }

    fun createUserRegistrationInvite(auth: AuthUser, code: String, expiresHours: Int): Boolean = tx { connection ->
        val recent = connection.prepareStatement("SELECT count(*) FROM audit_events WHERE user_id=? AND event_type='REGISTRATION_INVITE_CREATED' AND created_at>now()-interval '24 hours'").use {
            it.setObject(1, auth.userId); it.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
        if (recent >= 5) return@tx false
        connection.prepareStatement("INSERT INTO registration_invites(id, code_hash, expires_at, max_uses, created_by_user_id) VALUES (?, ?, now() + (? || ' hours')::interval, 1, ?)").use {
            it.setObject(1, UUID.randomUUID()); it.setString(2, sha256(code)); it.setInt(3, expiresHours); it.setObject(4, auth.userId); it.executeUpdate()
        }
        connection.audit(auth.userId, "REGISTRATION_INVITE_CREATED", "USER")
        true
    }

    fun register(request: RegisterRequest): TokenResponse = try { tx { connection ->
        val invite = connection.prepareStatement("SELECT id, max_uses, use_count FROM registration_invites WHERE code_hash=? AND revoked_at IS NULL AND expires_at>now() FOR UPDATE").use {
            it.setString(1, sha256(request.inviteCode)); it.executeQuery().use { rs -> if (rs.next()) Triple(rs.getObject(1, UUID::class.java), rs.getInt(2), rs.getInt(3)) else null }
        } ?: throw IllegalArgumentException("Invitation is invalid or expired")
        require(invite.third < invite.second) { "Invitation has already been used" }

        val userId = UUID.randomUUID()
        val passwordHash = Passwords.hash(request.password)
        try {
            connection.prepareStatement("INSERT INTO users(id, username, display_name, password_hash) VALUES (?, ?, ?, ?)").use {
                it.setObject(1, userId); it.setString(2, request.username.trim()); it.setString(3, request.displayName.trim()); it.setString(4, passwordHash); it.executeUpdate()
            }
        } catch (error: Exception) {
            if (error.message?.contains("username", true) == true || error.cause?.message?.contains("username", true) == true) throw IllegalArgumentException("Username is already taken")
            throw error
        }
        connection.prepareStatement("UPDATE registration_invites SET use_count=use_count+1 WHERE id=?").use { it.setObject(1, invite.first); it.executeUpdate() }
        connection.audit(userId, "REGISTER", "SUCCESS")
        createSession(connection, userId, request.username.trim(), request.displayName.trim(), request.deviceName.trim().ifBlank { "Android phone" })
    } } catch (error: SQLException) {
        if (error.sqlState == "23505") throw IllegalArgumentException("Username is already taken")
        throw error
    }

    fun login(request: LoginRequest): TokenResponse? = tx { connection ->
        val user = connection.prepareStatement("SELECT id, username, display_name, password_hash FROM users WHERE username=? AND status='ACTIVE'").use {
            it.setString(1, request.username.trim()); it.executeQuery().use { rs ->
                if (rs.next()) UserPassword(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3), rs.getString(4)) else null
            }
        }
        if (user == null || !Passwords.verify(user.passwordHash, request.password)) {
            connection.audit(user?.id, "LOGIN", "FAILED")
            null
        } else {
            connection.audit(user.id, "LOGIN", "SUCCESS")
            createSession(connection, user.id, user.username, user.displayName, request.deviceName.trim().ifBlank { "Android phone" })
        }
    }

    fun authenticate(accessToken: String): AuthUser? = query { connection ->
        connection.prepareStatement("""
            SELECT u.id, u.username, u.display_name, s.id
            FROM device_sessions s JOIN users u ON u.id=s.user_id
            WHERE s.access_token_hash=? AND s.revoked_at IS NULL AND s.access_expires_at>now() AND u.status='ACTIVE'
        """.trimIndent()).use {
            it.setString(1, sha256(accessToken)); it.executeQuery().use { rs ->
                if (rs.next()) AuthUser(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3), rs.getObject(4, UUID::class.java)) else null
            }
        }
    }

    fun refresh(refreshToken: String): TokenResponse? = tx { connection ->
        val session = connection.prepareStatement("""
            SELECT s.id, u.id, u.username, u.display_name FROM device_sessions s JOIN users u ON u.id=s.user_id
            WHERE s.refresh_token_hash=? AND s.revoked_at IS NULL AND s.refresh_expires_at>now() AND u.status='ACTIVE' FOR UPDATE
        """.trimIndent()).use {
            it.setString(1, sha256(refreshToken)); it.executeQuery().use { rs ->
                if (rs.next()) AuthUser(rs.getObject(2, UUID::class.java), rs.getString(3), rs.getString(4), rs.getObject(1, UUID::class.java)) else null
            }
        } ?: return@tx null
        val access = randomToken()
        val refresh = randomToken()
        connection.prepareStatement("""
            UPDATE device_sessions SET access_token_hash=?, access_expires_at=now()+interval '15 minutes',
            refresh_token_hash=?, refresh_expires_at=now()+interval '30 days', last_seen_at=now() WHERE id=?
        """.trimIndent()).use {
            it.setString(1, sha256(access)); it.setString(2, sha256(refresh)); it.setObject(3, session.sessionId); it.executeUpdate()
        }
        TokenResponse(access, refresh, 900, UserResponse(session.userId.toString(), session.username, session.displayName))
    }

    fun revokeSession(sessionId: UUID) = tx { connection ->
        connection.prepareStatement("UPDATE device_sessions SET revoked_at=now() WHERE id=?").use { it.setObject(1, sessionId); it.executeUpdate() }
    }

    fun isGroupAdmin(userId: UUID, groupId: UUID): Boolean = query { connection ->
        connection.prepareStatement("""
            SELECT 1 FROM group_members gm JOIN finance_groups g ON g.id=gm.group_id
            WHERE gm.group_id=? AND gm.user_id=? AND gm.role='ADMIN' AND gm.left_at IS NULL AND g.deleted_at IS NULL
        """.trimIndent()).use {
            it.setObject(1, groupId); it.setObject(2, userId); it.executeQuery().next()
        }
    }

    fun createGroupInvite(userId: UUID, groupId: UUID, code: String, expiresHours: Int) = tx { connection ->
        connection.prepareStatement("INSERT INTO group_invites(id, group_id, code_hash, created_by_user_id, expires_at) VALUES (?, ?, ?, ?, now()+(? || ' hours')::interval)").use {
            it.setObject(1, UUID.randomUUID()); it.setObject(2, groupId); it.setString(3, sha256(code)); it.setObject(4, userId); it.setInt(5, expiresHours); it.executeUpdate()
        }
    }

    fun acceptGroupInvite(auth: AuthUser, code: String): UUID? = tx { connection ->
        val invite = connection.prepareStatement("""
            SELECT gi.id, gi.group_id, gi.max_uses, gi.use_count FROM group_invites gi
            JOIN finance_groups g ON g.id=gi.group_id
            WHERE gi.code_hash=? AND gi.revoked_at IS NULL AND gi.expires_at>now() AND g.deleted_at IS NULL FOR UPDATE OF gi
        """.trimIndent()).use {
            it.setString(1, sha256(code)); it.executeQuery().use { rs ->
                if (rs.next()) GroupInviteRow(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getInt(3), rs.getInt(4)) else null
            }
        } ?: return@tx null
        if (invite.useCount >= invite.maxUses) return@tx null
        connection.prepareStatement("""
            INSERT INTO group_members(group_id,user_id,role) VALUES (?,?,'MEMBER')
            ON CONFLICT(group_id,user_id) DO UPDATE SET left_at=NULL, role='MEMBER', joined_at=now()
        """.trimIndent()).use { it.setObject(1, invite.groupId); it.setObject(2, auth.userId); it.executeUpdate() }
        connection.prepareStatement("UPDATE group_invites SET use_count=use_count+1 WHERE id=?").use { it.setObject(1, invite.id); it.executeUpdate() }

        val operationId = UUID.randomUUID()
        val payload = buildJsonObject { put("userId", auth.userId.toString()); put("username", auth.username); put("displayName", auth.displayName) }
        val seq = connection.appendChange(operationId, auth, "GROUP", invite.groupId, "GROUP_MEMBER", auth.userId, "UPSERT", null, 1, payload)
        connection.prepareStatement("UPDATE group_members SET server_seq=? WHERE group_id=? AND user_id=?").use {
            it.setLong(1, seq); it.setObject(2, invite.groupId); it.setObject(3, auth.userId); it.executeUpdate()
        }
        invite.groupId
    }

    /** A client batch is one transaction so cascades cannot be only half applied. */
    fun applyOperations(auth: AuthUser, operations: List<SyncOperation>): List<SyncOperationResult> = try {
        tx { connection ->
            val requestIndexByOperationId = operations.mapIndexed { index, operation -> operation.operationId to index }.toMap()
            val results = orderedSyncOperations(operations)
                .map { operation -> requestIndexByOperationId.getValue(operation.operationId) to applyOperation(connection, auth, operation) }
                .sortedBy { it.first }
                .map { it.second }
            if (results.any { it.status !in setOf("ACCEPTED", "DUPLICATE") }) throw BatchRejected(results)
            results
        }
    } catch (rejected: BatchRejected) {
        rejected.results.map { result ->
            if (result.status == "ACCEPTED") {
                result.copy(status = "REJECTED", serverVersion = null, serverSeq = null, errorCode = "BATCH_ROLLED_BACK")
            } else result
        }
    }

    private fun applyOperation(connection: Connection, auth: AuthUser, operation: SyncOperation): SyncOperationResult {
        val operationId = operation.operationId.asUuid("operationId")
        val entityId = operation.entityId.asUuid("entityId")
        val entityType = operation.entityType.uppercase()
        require(entityType in setOf("PROFILE", "FINANCIAL_ACCOUNT", "CATEGORY", "TRANSACTION", "BUDGET", "EXPENSE_SET", "TRANSFER", "LOAN", "LOAN_PAYMENT", "SCHEDULED_PAYMENT", "INVESTMENT", "INVESTMENT_ACCOUNT", "INVESTMENT_ENTRY", "INVESTMENT_VALUATION", "GROUP", "GROUP_EXPENSE", "SETTLEMENT")) { "Unsupported entity type" }
        val action = operation.action.uppercase()
        require(action in setOf("UPSERT", "DELETE")) { "Unsupported action" }

        connection.findPriorResult(operationId)?.let { return it.copy(status = "DUPLICATE") }

        val scopeType = operation.scopeType.uppercase()
        require(
            (scopeType == "PERSONAL" && entityType in setOf("PROFILE", "FINANCIAL_ACCOUNT", "CATEGORY", "TRANSACTION", "BUDGET", "EXPENSE_SET", "TRANSFER", "LOAN", "LOAN_PAYMENT", "SCHEDULED_PAYMENT", "INVESTMENT", "INVESTMENT_ACCOUNT", "INVESTMENT_ENTRY", "INVESTMENT_VALUATION")) ||
                (scopeType == "GROUP" && entityType in setOf("GROUP", "GROUP_EXPENSE", "SETTLEMENT"))
        ) { "Entity type is not valid for this scope" }
        val scopeId = when (scopeType) {
            "PERSONAL" -> {
                require(operation.scopeId == "SELF" || operation.scopeId.equals(auth.userId.toString(), true)) { "Personal scope must belong to the authenticated user" }
                auth.userId
            }
            "GROUP" -> operation.scopeId.asUuid("scopeId")
            else -> throw IllegalArgumentException("Unsupported scope type")
        }
        if (entityType == "GROUP") require(entityId == scopeId) { "Group entity ID must match group scope ID" }
        val submittedPayload = if (action == "DELETE") null else operation.payload ?: throw IllegalArgumentException("UPSERT requires payload")
        if (submittedPayload != null) validatePayload(entityType, entityId, submittedPayload)

        var createdGroup = false
        if (scopeType == "GROUP" && entityType == "GROUP" && action == "UPSERT") {
            createdGroup = connection.prepareStatement("INSERT INTO finance_groups(id,created_by_user_id) VALUES (?,?) ON CONFLICT(id) DO NOTHING").use {
                it.setObject(1, scopeId); it.setObject(2, auth.userId); it.executeUpdate() == 1
            }
            if (createdGroup) {
                connection.prepareStatement("""
                    INSERT INTO group_members(group_id,user_id,role) VALUES (?,?,'ADMIN')
                    ON CONFLICT(group_id,user_id) DO NOTHING
                """.trimIndent()).use { it.setObject(1, scopeId); it.setObject(2, auth.userId); it.executeUpdate() }
            }
        }
        if (scopeType == "GROUP" && !connection.isActiveMember(auth.userId, scopeId)) {
            return SyncOperationResult(operation.operationId, "REJECTED", errorCode = "NOT_GROUP_MEMBER")
        }
        if (scopeType == "GROUP" && entityType == "GROUP" && !createdGroup && !connection.isGroupAdmin(auth.userId, scopeId)) {
            return SyncOperationResult(operation.operationId, "REJECTED", errorCode = "NOT_GROUP_ADMIN")
        }

        val current = connection.findEntity(entityType, entityId, scopeType, scopeId)
        if (current != null && operation.baseVersion != null && operation.baseVersion != current.version) {
            return SyncOperationResult(operation.operationId, "CONFLICT", current.version, current.serverSeq, current.payload, "STALE_VERSION")
        }
        if (current == null && operation.baseVersion != null && operation.baseVersion > 0) {
            return SyncOperationResult(operation.operationId, "CONFLICT", errorCode = "ENTITY_MISSING")
        }
        if (submittedPayload != null) connection.validateReferences(entityType, submittedPayload, scopeType, scopeId)

        val version = (current?.version ?: 0) + 1
        val payload = submittedPayload
        connection.prepareStatement("""
            INSERT INTO sync_entities(entity_type,entity_id,scope_type,scope_id,owner_user_id,version,payload,deleted_at)
            VALUES (?,?,?,?,?,?,?::jsonb,CASE WHEN ?='DELETE' THEN now() ELSE NULL END)
            ON CONFLICT(entity_type,entity_id,scope_type,scope_id) DO UPDATE SET
              owner_user_id=excluded.owner_user_id, version=excluded.version, payload=excluded.payload,
              deleted_at=excluded.deleted_at, updated_at=now()
        """.trimIndent()).use {
            it.setString(1, entityType); it.setObject(2, entityId); it.setString(3, scopeType); it.setObject(4, scopeId)
            it.setObject(5, auth.userId); it.setLong(6, version); it.setString(7, payload?.toString()); it.setString(8, action); it.executeUpdate()
        }
        val seq = connection.appendChange(operationId, auth, scopeType, scopeId, entityType, entityId, action, operation.baseVersion, version, payload)
        connection.prepareStatement("UPDATE sync_entities SET server_seq=? WHERE entity_type=? AND entity_id=? AND scope_type=? AND scope_id=?").use {
            it.setLong(1, seq); it.setString(2, entityType); it.setObject(3, entityId); it.setString(4, scopeType); it.setObject(5, scopeId); it.executeUpdate()
        }
        if (entityType == "GROUP" && action == "DELETE") {
            connection.prepareStatement("UPDATE finance_groups SET deleted_at=now() WHERE id=?").use { it.setObject(1, scopeId); it.executeUpdate() }
        }
        return SyncOperationResult(operation.operationId, "ACCEPTED", version, seq, payload)
    }

    fun pull(userId: UUID, after: Long, limit: Int): SyncPullResponse = query { connection ->
        val changes = mutableListOf<SyncChange>()
        connection.prepareStatement("""
            SELECT c.server_seq,c.operation_id,c.scope_type,c.scope_id,c.entity_type,c.entity_id,c.action,c.server_version,c.payload
            FROM change_log c
            WHERE c.server_seq>? AND (
              (c.scope_type='PERSONAL' AND c.scope_id=?) OR
              (c.scope_type='GROUP' AND EXISTS(SELECT 1 FROM group_members gm WHERE gm.group_id=c.scope_id AND gm.user_id=? AND gm.left_at IS NULL))
            ) ORDER BY c.server_seq LIMIT ?
        """.trimIndent()).use {
            it.setLong(1, after); it.setObject(2, userId); it.setObject(3, userId); it.setInt(4, limit + 1)
            it.executeQuery().use { rs -> while (rs.next()) changes += rs.toChange() }
        }
        val hasMore = changes.size > limit
        val page = if (hasMore) changes.take(limit) else changes
        SyncPullResponse(page, page.lastOrNull()?.serverSeq ?: after, hasMore)
    }

    fun bootstrap(userId: UUID, after: Long, limit: Int): SyncBootstrapResponse = query { connection ->
        val records = mutableListOf<SyncChange>()
        connection.prepareStatement("""
            SELECT server_seq,NULL::uuid AS operation_id,scope_type,scope_id,entity_type,entity_id,
                   CASE WHEN deleted_at IS NULL THEN 'UPSERT' ELSE 'DELETE' END AS action,version,payload
            FROM sync_entities e WHERE server_seq>? AND (
              (scope_type='PERSONAL' AND scope_id=?) OR
              (scope_type='GROUP' AND EXISTS(SELECT 1 FROM group_members gm WHERE gm.group_id=e.scope_id AND gm.user_id=? AND gm.left_at IS NULL))
            ) ORDER BY server_seq LIMIT ?
        """.trimIndent()).use {
            it.setLong(1, after); it.setObject(2, userId); it.setObject(3, userId); it.setInt(4, limit + 1)
            it.executeQuery().use { rs -> while (rs.next()) records += rs.toChange() }
        }
        connection.prepareStatement("""
            SELECT gm.server_seq,NULL::uuid,'GROUP',gm.group_id,'GROUP_MEMBER',gm.user_id,'UPSERT',1,
                   jsonb_build_object('userId',gm.user_id::text,'username',u.username::text,'displayName',u.display_name)
            FROM group_members gm JOIN users u ON u.id=gm.user_id
            WHERE gm.server_seq>? AND gm.left_at IS NULL AND EXISTS(SELECT 1 FROM group_members mine WHERE mine.group_id=gm.group_id AND mine.user_id=? AND mine.left_at IS NULL)
            ORDER BY gm.server_seq LIMIT ?
        """.trimIndent()).use {
            it.setLong(1, after); it.setObject(2, userId); it.setInt(3, limit + 1)
            it.executeQuery().use { rs -> while (rs.next()) records += rs.toChange() }
        }
        val ordered = records.distinctBy { "${it.scopeType}:${it.scopeId}:${it.entityType}:${it.entityId}" }.sortedBy { it.serverSeq }
        val hasMore = ordered.size > limit
        val page = if (hasMore) ordered.take(limit) else ordered
        // Advance only to a record actually returned. A concurrent write can then be picked up by normal pull without a skipped sequence.
        val cursor = page.lastOrNull()?.serverSeq ?: after
        SyncBootstrapResponse(page, cursor, hasMore)
    }

    private fun createSession(connection: Connection, userId: UUID, username: String, displayName: String, deviceName: String): TokenResponse {
        val access = randomToken(); val refresh = randomToken(); val sessionId = UUID.randomUUID()
        connection.prepareStatement("""
            INSERT INTO device_sessions(id,user_id,device_name,access_token_hash,access_expires_at,refresh_token_hash,refresh_expires_at)
            VALUES (?,?,?,?,now()+interval '15 minutes',?,now()+interval '30 days')
        """.trimIndent()).use {
            it.setObject(1, sessionId); it.setObject(2, userId); it.setString(3, deviceName); it.setString(4, sha256(access)); it.setString(5, sha256(refresh)); it.executeUpdate()
        }
        return TokenResponse(access, refresh, 900, UserResponse(userId.toString(), username, displayName))
    }

    private fun <T> query(block: (Connection) -> T): T = dataSource.connection.use(block)
    private fun <T> tx(block: (Connection) -> T): T = dataSource.connection.use { connection ->
        connection.autoCommit = false
        try { block(connection).also { connection.commit() } } catch (error: Throwable) { connection.rollback(); throw error }
    }
}

/**
 * Referential validation happens while a batch is applied, so dependency
 * parents must be visible before their children. Deletions use the reverse
 * order. The API still returns results in the client's original order.
 */
internal fun orderedSyncOperations(operations: List<SyncOperation>): List<SyncOperation> =
    operations.withIndex()
        .sortedWith(compareBy<IndexedValue<SyncOperation>>(
            { if (it.value.action.equals("DELETE", ignoreCase = true)) 1 else 0 },
            {
                val rank = syncEntityDependencyRank(it.value.entityType)
                if (it.value.action.equals("DELETE", ignoreCase = true)) -rank else rank
            },
            { it.index }
        ))
        .map { it.value }

private fun syncEntityDependencyRank(entityType: String): Int = when (entityType.uppercase()) {
    "PROFILE", "GROUP" -> 0
    "FINANCIAL_ACCOUNT", "CATEGORY", "INVESTMENT" -> 10
    "EXPENSE_SET", "SCHEDULED_PAYMENT", "LOAN", "BUDGET" -> 20
    "TRANSFER", "INVESTMENT_ACCOUNT", "GROUP_EXPENSE", "SETTLEMENT" -> 30
    "TRANSACTION", "LOAN_PAYMENT", "INVESTMENT_ENTRY", "INVESTMENT_VALUATION" -> 40
    else -> 50
}

private fun Connection.findPriorResult(operationId: UUID): SyncOperationResult? = prepareStatement("SELECT server_version,server_seq,payload FROM change_log WHERE operation_id=?").use {
    it.setObject(1, operationId); it.executeQuery().use { rs -> if (rs.next()) SyncOperationResult(operationId.toString(), "ACCEPTED", rs.getLong(1), rs.getLong(2), rs.jsonOrNull(3)) else null }
}

private fun Connection.findEntity(type: String, id: UUID, scopeType: String, scopeId: UUID): EntityRow? = prepareStatement("SELECT version,server_seq,payload FROM sync_entities WHERE entity_type=? AND entity_id=? AND scope_type=? AND scope_id=? FOR UPDATE").use {
    it.setString(1, type); it.setObject(2, id); it.setString(3, scopeType); it.setObject(4, scopeId); it.executeQuery().use { rs -> if (rs.next()) EntityRow(rs.getLong(1), rs.getLong(2), rs.jsonOrNull(3)) else null }
}

private fun Connection.entityExists(type: String, id: String, scopeType: String, scopeId: UUID): Boolean {
    val entityId = uuidOrNull(id) ?: return false
    return prepareStatement("SELECT 1 FROM sync_entities WHERE entity_type=? AND entity_id=? AND scope_type=? AND scope_id=? AND deleted_at IS NULL").use {
        it.setString(1, type); it.setObject(2, entityId); it.setString(3, scopeType); it.setObject(4, scopeId)
        it.executeQuery().next()
    }
}

/** Referential checks run inside the same transaction as the batch. */
private fun Connection.validateReferences(entityType: String, payload: JsonElement, scopeType: String, scopeId: UUID) {
    fun requirePersonal(type: String, id: String?, label: String) {
        if (id != null) require(entityExists(type, id, "PERSONAL", scopeId)) { "$label does not exist or is inactive" }
    }
    when (entityType) {
        "TRANSACTION" -> apiJson.decodeFromJsonElement<TransactionPayload>(payload).also {
            requirePersonal("FINANCIAL_ACCOUNT", it.account, "Transaction account")
            requirePersonal("EXPENSE_SET", it.expenseSetId, "Expense set")
            requirePersonal("SCHEDULED_PAYMENT", it.scheduledPaymentId, "Scheduled payment")
        }
        "TRANSFER" -> apiJson.decodeFromJsonElement<TransferPayload>(payload).also {
            requirePersonal("FINANCIAL_ACCOUNT", it.fromAccount, "Source account")
            requirePersonal("FINANCIAL_ACCOUNT", it.toAccount, "Destination account")
        }
        "LOAN" -> apiJson.decodeFromJsonElement<LoanPayload>(payload).also { requirePersonal("FINANCIAL_ACCOUNT", it.account, "Loan account") }
        "LOAN_PAYMENT" -> apiJson.decodeFromJsonElement<LoanPaymentPayload>(payload).also {
            requirePersonal("LOAN", it.loanId, "Loan")
            requirePersonal("FINANCIAL_ACCOUNT", it.account, "Loan payment account")
        }
        "SCHEDULED_PAYMENT" -> apiJson.decodeFromJsonElement<ScheduledPaymentPayload>(payload).also { requirePersonal("FINANCIAL_ACCOUNT", it.account, "Scheduled payment account") }
        "INVESTMENT_ACCOUNT" -> apiJson.decodeFromJsonElement<InvestmentAccountPayload>(payload).also { requirePersonal("INVESTMENT", it.investmentId, "Investment") }
        "INVESTMENT_ENTRY" -> apiJson.decodeFromJsonElement<InvestmentEntryPayload>(payload).also {
            requirePersonal("INVESTMENT", it.investmentId, "Investment")
            requirePersonal("FINANCIAL_ACCOUNT", it.personalAccountId, "Personal account")
            requirePersonal("INVESTMENT_ACCOUNT", it.investmentAccountId, "Investment account")
            requirePersonal("INVESTMENT_ACCOUNT", it.toInvestmentAccountId, "Destination investment account")
        }
        "INVESTMENT_VALUATION" -> apiJson.decodeFromJsonElement<InvestmentValuationPayload>(payload).also { requirePersonal("INVESTMENT", it.investmentId, "Investment") }
        "GROUP_EXPENSE" -> apiJson.decodeFromJsonElement<GroupExpensePayload>(payload).also {
            (listOfNotNull(it.paidByUserId) + it.participantUserIds).distinct().forEach { userId ->
                val id = userId.asUuid("group member user ID")
                require(isActiveMember(id, scopeId)) { "Shared entry references a user who is not an active group member" }
            }
        }
        "SETTLEMENT" -> apiJson.decodeFromJsonElement<SettlementPayload>(payload).also {
            listOfNotNull(it.fromUserId, it.toUserId).distinct().forEach { userId ->
                val id = userId.asUuid("group member user ID")
                require(isActiveMember(id, scopeId)) { "Settlement references a user who is not an active group member" }
            }
        }
    }
}

private fun Connection.isActiveMember(userId: UUID, groupId: UUID): Boolean = prepareStatement("""
    SELECT 1 FROM group_members gm JOIN finance_groups g ON g.id=gm.group_id
    WHERE gm.group_id=? AND gm.user_id=? AND gm.left_at IS NULL AND g.deleted_at IS NULL
""".trimIndent()).use {
    it.setObject(1, groupId); it.setObject(2, userId); it.executeQuery().next()
}

private fun Connection.isGroupAdmin(userId: UUID, groupId: UUID): Boolean = prepareStatement("""
    SELECT 1 FROM group_members gm JOIN finance_groups g ON g.id=gm.group_id
    WHERE gm.group_id=? AND gm.user_id=? AND gm.role='ADMIN' AND gm.left_at IS NULL AND g.deleted_at IS NULL
""".trimIndent()).use {
    it.setObject(1, groupId); it.setObject(2, userId); it.executeQuery().next()
}

private fun Connection.appendChange(operationId: UUID, auth: AuthUser, scopeType: String, scopeId: UUID, entityType: String, entityId: UUID, action: String, baseVersion: Long?, version: Long, payload: JsonElement?): Long =
    prepareStatement("""
        INSERT INTO change_log(operation_id,actor_user_id,device_session_id,scope_type,scope_id,entity_type,entity_id,action,base_version,server_version,payload)
        VALUES (?,?,?,?,?,?,?,?,?,?,?::jsonb) RETURNING server_seq
    """.trimIndent()).use {
        it.setObject(1, operationId); it.setObject(2, auth.userId); it.setObject(3, auth.sessionId); it.setString(4, scopeType); it.setObject(5, scopeId)
        it.setString(6, entityType); it.setObject(7, entityId); it.setString(8, action)
        if (baseVersion == null) it.setNull(9, java.sql.Types.BIGINT) else it.setLong(9, baseVersion)
        it.setLong(10, version); it.setString(11, payload?.toString()); it.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
    }

private fun Connection.appendSystemChange(scopeType: String, scopeId: UUID, entityType: String, entityId: UUID, action: String, version: Long, payload: JsonElement?): Long =
    prepareStatement("""
        INSERT INTO change_log(operation_id,actor_user_id,device_session_id,scope_type,scope_id,entity_type,entity_id,action,server_version,payload)
        VALUES (?,NULL,NULL,?,?,?,?,?,?,?::jsonb) RETURNING server_seq
    """.trimIndent()).use {
        it.setObject(1, UUID.randomUUID()); it.setString(2, scopeType); it.setObject(3, scopeId); it.setString(4, entityType)
        it.setObject(5, entityId); it.setString(6, action); it.setLong(7, version); it.setString(8, payload?.toString())
        it.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
    }

private fun Connection.anonymizeGroupUser(groupId: UUID, userId: UUID, displayName: String) {
    val rows = prepareStatement("SELECT entity_type,entity_id,version,payload FROM sync_entities WHERE scope_type='GROUP' AND scope_id=? AND deleted_at IS NULL AND payload IS NOT NULL FOR UPDATE").use {
        it.setObject(1, groupId); it.executeQuery().use { rs -> buildList {
            while (rs.next()) add(GroupPayloadRow(rs.getString(1), rs.getObject(2, UUID::class.java), rs.getLong(3), rs.jsonOrNull(4)!!))
        } }
    }
    rows.forEach { row ->
        val next = anonymizeSharedPayload(row.entityType, row.payload, userId, displayName)
        if (next != row.payload) {
            val version = row.version + 1
            val seq = appendSystemChange("GROUP", groupId, row.entityType, row.entityId, "UPSERT", version, next)
            prepareStatement("UPDATE sync_entities SET version=?,server_seq=?,payload=?::jsonb,updated_at=now() WHERE entity_type=? AND entity_id=? AND scope_type='GROUP' AND scope_id=?").use {
                it.setLong(1, version); it.setLong(2, seq); it.setString(3, next.toString()); it.setString(4, row.entityType); it.setObject(5, row.entityId); it.setObject(6, groupId); it.executeUpdate()
            }
        }
    }
    val history = prepareStatement("SELECT server_seq,entity_type,payload FROM change_log WHERE scope_type='GROUP' AND scope_id=? AND payload IS NOT NULL FOR UPDATE").use {
        it.setObject(1, groupId); it.executeQuery().use { rs -> buildList {
            while (rs.next()) add(ChangePayloadRow(rs.getLong(1), rs.getString(2), rs.jsonOrNull(3)!!))
        } }
    }
    history.forEach { row ->
        val next = anonymizeSharedPayload(row.entityType, row.payload, userId, displayName)
        if (next != row.payload) prepareStatement("UPDATE change_log SET payload=?::jsonb WHERE server_seq=?").use {
            it.setString(1, next.toString()); it.setLong(2, row.serverSeq); it.executeUpdate()
        }
    }
}

private fun anonymizeSharedPayload(entityType: String, payload: JsonElement, userId: UUID, displayName: String): JsonElement {
    val source = payload as? JsonObject ?: return payload
    val next = source.toMutableMap()
    val deletedLabel = deletedMemberLabel(userId)
    when (entityType) {
        "GROUP" -> {
            source["members"]?.let { members -> next["members"] = JsonArray(members.jsonArray.filterNot { it.jsonPrimitive.content == displayName }) }
            source["memberAccounts"]?.let { accounts -> next["memberAccounts"] = JsonArray(accounts.jsonArray.filterNot {
                (it as? JsonObject)?.get("userId")?.jsonPrimitive?.content == userId.toString()
            }) }
        }
        "GROUP_EXPENSE" -> {
            if (source["paidBy"]?.jsonPrimitive?.content == displayName) next["paidBy"] = JsonPrimitive(deletedLabel)
            source["participants"]?.let { participants -> next["participants"] = JsonArray(participants.jsonArray.map {
                if (it.jsonPrimitive.content == displayName) JsonPrimitive(deletedLabel) else it
            }.distinct()) }
        }
        "SETTLEMENT" -> {
            if (source["from"]?.jsonPrimitive?.content == displayName) next["from"] = JsonPrimitive(deletedLabel)
            if (source["to"]?.jsonPrimitive?.content == displayName) next["to"] = JsonPrimitive(deletedLabel)
        }
        "GROUP_MEMBER" -> if (source["userId"]?.jsonPrimitive?.content == userId.toString()) {
            next["username"] = JsonPrimitive("deleted-${userId.toString().take(6)}")
            next["displayName"] = JsonPrimitive(deletedLabel)
        }
    }
    return JsonObject(next)
}

private fun deletedMemberLabel(userId: UUID): String = "Deleted member ${userId.toString().take(6)}"

private fun Connection.audit(userId: UUID?, type: String, detail: String) = prepareStatement("INSERT INTO audit_events(user_id,event_type,detail_code) VALUES (?,?,?)").use {
    if (userId == null) it.setNull(1, java.sql.Types.OTHER) else it.setObject(1, userId); it.setString(2, type); it.setString(3, detail); it.executeUpdate()
}

private fun ResultSet.toChange() = SyncChange(
    serverSeq = getLong(1), operationId = getObject(2)?.toString(), scopeType = getString(3), scopeId = getObject(4).toString(),
    entityType = getString(5), entityId = getObject(6).toString(), action = getString(7), serverVersion = getLong(8), payload = jsonOrNull(9)
)
private fun ResultSet.jsonOrNull(column: Int): JsonElement? = getString(column)?.let(apiJson::parseToJsonElement)

private object Passwords {
    private val argon2 = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id)
    fun hash(password: String): String = argon2.hash(3, 65_536, 1, password.toCharArray())
    fun verify(hash: String, password: String): Boolean = argon2.verify(hash, password.toCharArray())
}

private val secureRandom = SecureRandom()
internal fun randomToken(): String = ByteArray(32).also(secureRandom::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
internal fun randomCode(length: Int): String {
    val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    return buildString(length) { repeat(length) { append(alphabet[secureRandom.nextInt(alphabet.length)]) } }
}
internal fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
internal fun constantTimeEquals(left: String, right: String): Boolean = MessageDigest.isEqual(left.toByteArray(), right.toByteArray())
private fun String?.asUuid(label: String): UUID = runCatching { UUID.fromString(this) }.getOrElse { throw IllegalArgumentException("$label must be a UUID") }
private fun uuidOrNull(value: String): UUID? = runCatching { UUID.fromString(value) }.getOrNull()
private val USERNAME_PATTERN = Regex("[A-Za-z0-9._-]{3,64}")
private val INVESTMENT_ENTRY_TYPES = setOf(
    "CONTRIBUTION", "OPERATING_INCOME", "OPERATING_EXPENSE", "DIVIDEND", "OWNER_SALARY",
    "CAPITAL_RETURN", "INVESTMENT_SALE", "INVESTMENT_FEE", "ASSET_PURCHASE", "ASSET_SALE",
    "BUSINESS_LOAN_RECEIVED", "BUSINESS_LOAN_PAYMENT", "BUSINESS_TRANSFER"
)
private fun validateCredentials(username: String, password: String, displayName: String) {
    require(username.trim().matches(USERNAME_PATTERN)) { "Username must be 3-64 letters, numbers, dots, underscores, or hyphens" }
    require(password.length in 12..128) { "Password must be 12-128 characters" }
    require(displayName.trim().length in 1..80) { "Name must be 1-80 characters" }
}

internal fun ApplicationCall.noStoreHeader() {
    response.headers.append(HttpHeaders.CacheControl, "no-store")
}

private fun validatePayload(entityType: String, entityId: UUID, payload: JsonElement) {
    when (entityType) {
        "PROFILE" -> apiJson.decodeFromJsonElement<ProfilePayload>(payload).also {
            require(it.ownerName.trim().length in 1..80) { "Profile name must be 1-80 characters" }
            require(it.currencyCode.matches(Regex("[A-Z]{3}"))) { "Currency must be a three-letter uppercase code" }
            require(it.monthStartDay in 1..31) { "Monthly cycle start day must be 1-31" }
        }
        "FINANCIAL_ACCOUNT" -> apiJson.decodeFromJsonElement<FinancialAccountPayload>(payload).also {
            require(it.id == entityId.toString()) { "Financial account payload ID must match entity ID" }
            require(it.name.trim().length in 1..80) { "Financial account name must be 1-80 characters" }
            require(it.type in setOf("BANK", "CASH", "SAVINGS", "CREDIT_CARD", "DIGITAL_WALLET", "OTHER")) { "Financial account type is invalid" }
            require(it.openingBalanceMinor in 0..MAX_AMOUNT_MINOR) { "Financial account opening balance is outside the supported range" }
            require(it.nature in setOf("ASSET", "LIABILITY")) { "Financial account nature is invalid" }
            require(it.liquidity in setOf("LIQUID", "NON_LIQUID")) { "Financial account liquidity is invalid" }
            require(it.nature != "LIABILITY" || it.liquidity == "NON_LIQUID") { "Liability accounts cannot be available cash" }
        }
        "CATEGORY" -> apiJson.decodeFromJsonElement<CategoryPayload>(payload).also {
            require(it.id == entityId.toString()) { "Category payload ID must match entity ID" }
            require(it.name.trim().length in 2..80 && it.type in setOf("EXPENSE", "INCOME")) { "Category is invalid" }
        }
        "TRANSACTION" -> apiJson.decodeFromJsonElement<TransactionPayload>(payload).also {
            require(it.id == entityId.toString()) { "Transaction payload ID must match entity ID" }
            require(it.amountMinor in 1..MAX_AMOUNT_MINOR) { "Transaction amount is outside the supported range" }
            require(it.type in setOf("EXPENSE", "INCOME")) { "Transaction type is invalid" }
            require(it.category.trim().length in 1..80 && it.account.trim().length in 1..80 && it.note.length <= 2_000) { "Transaction text is invalid" }
            require(it.lineItems.size <= 200) { "A transaction can contain at most 200 item allocations" }
            require(it.lineItems.all { line -> line.id.asUuid("lineItemId").toString() == line.id && line.description.trim().length in 1..160 && line.category.trim().length in 1..80 && line.amountMinor in 1..MAX_AMOUNT_MINOR }) { "Transaction item allocation is invalid" }
            require(it.lineItems.isEmpty() || it.lineItems.sumOf { line -> line.amountMinor } == it.amountMinor) { "Transaction item allocations must equal the parent amount" }
            require(it.minuteOfDay == null || it.minuteOfDay in 0..1439) { "Transaction time is invalid" }
            require(it.paymentMethod.length <= 80) { "Transaction payment method is invalid" }
            validateAttachments(it.attachments)
        }
        "BUDGET" -> apiJson.decodeFromJsonElement<BudgetPayload>(payload).also {
            require(it.category.trim().length in 1..80 && it.monthlyLimitMinor in 1..MAX_AMOUNT_MINOR) { "Budget is invalid" }
        }
        "EXPENSE_SET" -> apiJson.decodeFromJsonElement<ExpenseSetPayload>(payload).also {
            require(it.id == entityId.toString() && it.name.trim().length in 1..120 && it.description.length <= 500) { "Expense set is invalid" }
        }
        "TRANSFER" -> apiJson.decodeFromJsonElement<TransferPayload>(payload).also {
            require(it.id == entityId.toString() && it.amountMinor in 1..MAX_AMOUNT_MINOR) { "Transfer is invalid" }
            require(it.fromAccount.trim().length in 1..80 && it.toAccount.trim().length in 1..80 && it.fromAccount != it.toAccount && it.note.length <= 2_000) { "Transfer text is invalid" }
        }
        "LOAN" -> apiJson.decodeFromJsonElement<LoanPayload>(payload).also {
            require(it.id == entityId.toString() && it.counterparty.trim().length in 1..120 && it.principalMinor in 1..MAX_AMOUNT_MINOR) { "Loan is invalid" }
            require(it.direction in setOf("BORROWED", "LENT") && it.planType in setOf("ONE_TIME", "CUSTOM", "MONTHLY", "EMI")) { "Loan direction or repayment plan is invalid" }
            require(it.interestMode in setOf("KNOWN_RATE", "DERIVED_FROM_PAYMENT", "VARIABLE_RATE")) { "Loan interest mode is invalid" }
            require(it.account.trim().length in 1..80 && it.annualInterestBps in 0..1_000_000 && it.termMonths in 1..1_200 && it.dueDay in 1..31) { "Loan schedule is invalid" }
            require(it.monthlyPaymentMinor in 0..MAX_AMOUNT_MINOR && it.note.length <= 2_000) { "Loan payment or note is invalid" }
            require(it.firstDueEpochDay == null || it.firstDueEpochDay >= it.startEpochDay) { "First repayment cannot be before the loan" }
            require(it.reminderDaysBefore in setOf(1, 3) && it.customRepayments.size <= 1_200) { "Loan reminder or custom schedule is invalid" }
            require(it.customRepayments.all { payment -> payment.id.asUuid("plannedPaymentId").toString() == payment.id && payment.amountMinor in 1..MAX_AMOUNT_MINOR && payment.dueEpochDay >= it.startEpochDay }) { "Custom loan payment is invalid" }
            require(it.cashTreatment in setOf("FULL_HISTORY", "LIABILITY_ONLY", "CURRENT_REMAINDER")) { "Loan cash treatment is invalid" }
            require(it.initialCashImpactMinor in 0..it.principalMinor) { "Loan cash impact is invalid" }
            require(it.purpose in setOf("CASH", "VEHICLE", "PROPERTY", "PRODUCT", "EDUCATION", "OTHER")) { "Loan purpose is invalid" }
            require(it.downPaymentMinor in 0..MAX_AMOUNT_MINOR && it.initialAccountChangeMinor in -MAX_AMOUNT_MINOR..MAX_AMOUNT_MINOR) { "Loan initial payment is invalid" }
            require(it.purpose == "CASH" || (it.direction == "BORROWED" && it.cashTreatment != "CURRENT_REMAINDER" && it.initialCashImpactMinor == 0L)) { "Financed purchase cash treatment is invalid" }
            require(it.purpose == "CASH" || it.initialAccountChangeMinor == 0L || it.initialAccountChangeMinor == -it.downPaymentMinor) { "Financed purchase cannot create borrowed cash" }
            require((it.reconciledBalanceMinor == null) == (it.reconciledEpochDay == null)) { "Loan reconciliation is incomplete" }
            require(it.reconciledBalanceMinor == null || it.reconciledBalanceMinor in 0..MAX_AMOUNT_MINOR) { "Loan reconciled balance is invalid" }
        }
        "LOAN_PAYMENT" -> apiJson.decodeFromJsonElement<LoanPaymentPayload>(payload).also {
            require(it.id == entityId.toString()) { "Loan payment payload ID must match entity ID" }
            require(it.loanId.asUuid("loanId").toString() == it.loanId && it.amountMinor in 1..MAX_AMOUNT_MINOR) { "Loan payment is invalid" }
            require(it.principalMinor in 0..it.amountMinor && it.interestMinor in 0..it.amountMinor && it.principalMinor + it.interestMinor == it.amountMinor) { "Loan payment split is invalid" }
            require(it.account.trim().length in 1..80 && it.note.length <= 2_000) { "Loan payment text is invalid" }
            require(it.origin in setOf("MANUAL", "HISTORICAL_RECONCILIATION")) { "Loan payment origin is invalid" }
        }
        "SCHEDULED_PAYMENT" -> apiJson.decodeFromJsonElement<ScheduledPaymentPayload>(payload).also {
            require(it.id == entityId.toString() && it.title.trim().length in 1..160 && it.amountMinor in 1..MAX_AMOUNT_MINOR) { "Scheduled payment is invalid" }
            require(it.category.trim().length in 1..80 && it.account.trim().length in 1..80 && it.note.length <= 2_000) { "Scheduled payment text is invalid" }
            require(it.intervalCount in 1..10_000 && it.intervalUnit in setOf("DAYS", "WEEKS", "MONTHS", "YEARS") && it.reminderDaysBefore in setOf(1, 3)) { "Scheduled payment interval is invalid" }
            require(it.completedDueEpochDays.size <= 20_000 && it.completedDueEpochDays.distinct().size == it.completedDueEpochDays.size) { "Scheduled payment completion history is invalid" }
        }
        "INVESTMENT" -> apiJson.decodeFromJsonElement<InvestmentPayload>(payload).also {
            require(it.id == entityId.toString() && it.name.trim().length in 1..120) { "Investment is invalid" }
            require(it.kind in setOf("PASSIVE", "STARTUP") && it.ownershipBps in 1..10_000 && it.description.length <= 2_000) { "Investment ownership or text is invalid" }
            require(it.ledgerScope in setOf("WHOLE_COMPANY", "OWNERS_SHARE")) { "Investment ledger scope is invalid" }
        }
        "INVESTMENT_ACCOUNT" -> apiJson.decodeFromJsonElement<InvestmentAccountPayload>(payload).also {
            require(it.id == entityId.toString() && uuidOrNull(it.investmentId) != null && it.name.trim().length in 1..80) { "Investment account is invalid" }
            require(it.type in setOf("BANK", "CASH", "DIGITAL_WALLET", "OTHER")) { "Investment account type is invalid" }
        }
        "INVESTMENT_ENTRY" -> apiJson.decodeFromJsonElement<InvestmentEntryPayload>(payload).also {
            require(it.id == entityId.toString() && uuidOrNull(it.investmentId) != null && it.amountMinor in 1..MAX_AMOUNT_MINOR) { "Investment entry is invalid" }
            require(it.type in INVESTMENT_ENTRY_TYPES && it.category.trim().length in 1..80 && it.note.length <= 2_000 && it.counterparty.length <= 120) { "Investment entry text or type is invalid" }
            require(it.basisMinor in 0..MAX_AMOUNT_MINOR && it.principalMinor in 0..it.amountMinor) { "Investment basis or principal is invalid" }
            validateAttachments(it.attachments)
        }
        "INVESTMENT_VALUATION" -> apiJson.decodeFromJsonElement<InvestmentValuationPayload>(payload).also {
            require(it.id == entityId.toString() && uuidOrNull(it.investmentId) != null && it.valueMinor in 0..MAX_AMOUNT_MINOR && it.note.length <= 500) { "Investment valuation is invalid" }
        }
        "GROUP" -> apiJson.decodeFromJsonElement<GroupPayload>(payload).also {
            require(it.id == entityId.toString()) { "Group payload ID must match entity ID" }
            require(it.name.trim().length in 1..120 && it.members.size in 1..100 && it.members.all { name -> name.trim().length in 1..80 }) { "Group is invalid" }
        }
        "GROUP_EXPENSE" -> apiJson.decodeFromJsonElement<GroupExpensePayload>(payload).also {
            require(it.id == entityId.toString()) { "Expense payload ID must match entity ID" }
            require(it.title.trim().length in 1..160 && it.amountMinor in 1..MAX_AMOUNT_MINOR && it.paidBy.trim().length in 1..80) { "Shared expense is invalid" }
            require(it.participants.isNotEmpty() && it.participants.size <= 100 && it.participants.distinct().size == it.participants.size) { "Expense participants are invalid" }
            require(it.note.length <= 2_000) { "Expense note is too long" }
            require(it.type in setOf("EXPENSE", "INCOME") && it.category.trim().length in 1..80 && it.account.trim().length in 1..80) { "Shared entry type, category, or account is invalid" }
            validateAttachments(it.attachments)
        }
        "SETTLEMENT" -> apiJson.decodeFromJsonElement<SettlementPayload>(payload).also {
            require(it.id == entityId.toString()) { "Settlement payload ID must match entity ID" }
            require(it.from.trim().length in 1..80 && it.to.trim().length in 1..80 && it.from != it.to && it.amountMinor in 1..MAX_AMOUNT_MINOR) { "Settlement is invalid" }
        }
    }
}

private fun validateAttachments(attachments: List<AttachmentPayload>) {
    require(attachments.size <= 3) { "An entry can contain at most three attachments" }
    attachments.forEach { attachment ->
        require(attachment.id.asUuid("attachmentId").toString() == attachment.id) { "Attachment ID is invalid" }
        require(attachment.fileName.trim().length in 1..180 && attachment.mimeType.trim().length in 1..120) { "Attachment metadata is invalid" }
        require(attachment.sizeBytes in 0..MAX_ATTACHMENT_BYTES && attachment.base64Data.length <= MAX_ATTACHMENT_BASE64_CHARS) { "Attachment is larger than 5 MB" }
        val decoded = runCatching { Base64.getDecoder().decode(attachment.base64Data) }.getOrElse { throw IllegalArgumentException("Attachment content is not valid base64") }
        require(decoded.size.toLong() == attachment.sizeBytes) { "Attachment size does not match its content" }
    }
}

private const val MAX_AMOUNT_MINOR = 100_000_000_000_000L
private const val MAX_SYNC_REQUEST_BYTES = 24L * 1024L * 1024L
private const val MAX_ATTACHMENT_BYTES = 5L * 1024L * 1024L
private const val MAX_ATTACHMENT_BASE64_CHARS = 7_000_000

private data class UserPassword(val id: UUID, val username: String, val displayName: String, val passwordHash: String)
private data class EntityRow(val version: Long, val serverSeq: Long, val payload: JsonElement?)
private data class GroupPayloadRow(val entityType: String, val entityId: UUID, val version: Long, val payload: JsonElement)
private data class ChangePayloadRow(val serverSeq: Long, val entityType: String, val payload: JsonElement)
private data class GroupInviteRow(val id: UUID, val groupId: UUID, val maxUses: Int, val useCount: Int)
