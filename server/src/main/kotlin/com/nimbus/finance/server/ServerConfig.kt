package com.nimbus.finance.server

import java.nio.file.Files
import java.nio.file.Path

data class ServerConfig(
    val port: Int,
    val jdbcUrl: String,
    val dbUser: String,
    val dbPassword: String,
    val adminKey: String,
    val buildVersion: String,
    val publicServerUrl: String,
    val inferenceBaseUrl: String = "http://nimbus-inference:8080",
    val inferenceConfigDirectory: String = System.getProperty("java.io.tmpdir") + "/nimbus-inference",
    val inferenceModelsDirectory: String = "models",
    val inferenceSessionIdleMinutes: Int = 30,
    val inferenceMaxSessionsPerUser: Int = 3,
    val buildRevision: String = "unknown",
    val buildTime: String = "unknown",
    val schemaVersion: Int = 3
) {
    companion object {
        fun fromEnvironment(read: (String) -> String? = System::getenv): ServerConfig {
            fun env(name: String, fallback: String): String = read(name)?.takeIf(String::isNotBlank) ?: fallback
            fun number(name: String, fallback: Int, range: IntRange): Int {
                val value = env(name, fallback.toString()).toIntOrNull()
                require(value != null && value in range) { "$name must be an integer in ${range.first}..${range.last}" }
                return value
            }
            fun secret(valueName: String, fileName: String): String {
                read(valueName)?.takeIf(String::isNotBlank)?.let { return it }
                val file = read(fileName)?.takeIf(String::isNotBlank)
                require(file != null) { "$valueName or $fileName must be configured" }
                // Do not include secret values or host paths in configuration errors.
                val value = runCatching { Files.readString(Path.of(file)).trim() }
                    .getOrElse { throw IllegalArgumentException("$fileName must point to a readable secret file") }
                require(value.isNotBlank()) { "$fileName must contain a non-empty secret" }
                return value
            }
            return ServerConfig(
                port = number("PORT", 8080, 1..65535),
                jdbcUrl = env("JDBC_DATABASE_URL", "jdbc:postgresql://localhost:5432/nimbus"),
                dbUser = env("DB_USER", "nimbus"),
                dbPassword = secret("DB_PASSWORD", "DB_PASSWORD_FILE"),
                adminKey = secret("FINANCE_ADMIN_KEY", "FINANCE_ADMIN_KEY_FILE"),
                buildVersion = env("BUILD_VERSION", "dev"),
                publicServerUrl = env("PUBLIC_SERVER_URL", ""),
                inferenceBaseUrl = env("INFERENCE_BASE_URL", "http://nimbus-inference:8080").trimEnd('/'),
                inferenceConfigDirectory = env("INFERENCE_CONFIG_DIRECTORY", "/inference-config"),
                inferenceModelsDirectory = env("INFERENCE_MODELS_DIRECTORY", "/models"),
                inferenceSessionIdleMinutes = number("INFERENCE_SESSION_IDLE_MINUTES", 30, 5..240),
                inferenceMaxSessionsPerUser = number("INFERENCE_MAX_SESSIONS_PER_USER", 3, 1..8),
                buildRevision = env("BUILD_REVISION", "unknown"),
                buildTime = env("BUILD_TIME", "unknown"),
                schemaVersion = number("SERVER_SCHEMA_VERSION", 3, 1..Int.MAX_VALUE)
            )
        }
    }
}
