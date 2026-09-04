package com.nimbus.finance.server

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ServerConfigTest {
    private val environment = mapOf("DB_PASSWORD" to "test-password", "FINANCE_ADMIN_KEY" to "test-admin-key")

    @Test fun defaultsKeepBoundedSessions() {
        val config = ServerConfig.fromEnvironment(environment::get)
        assertEquals(8080, config.port)
        assertEquals(30, config.inferenceSessionIdleMinutes)
        assertEquals(3, config.inferenceMaxSessionsPerUser)
    }

    @Test fun invalidNumbersFailWithoutPrintingTheirValues() {
        for ((name, value) in listOf("PORT" to "65536", "PORT" to "private-invalid-value",
            "INFERENCE_SESSION_IDLE_MINUTES" to "0", "INFERENCE_MAX_SESSIONS_PER_USER" to "99")) {
            val error = assertFailsWith<IllegalArgumentException> {
                ServerConfig.fromEnvironment((environment + (name to value))::get)
            }
            assertFalse(error.message.orEmpty().contains("private-invalid-value"))
        }
    }

    @Test fun missingCredentialsFailClosed() {
        assertFailsWith<IllegalArgumentException> { ServerConfig.fromEnvironment(emptyMap<String, String>()::get) }
    }

    @Test fun blankSecretFilesAreRejected() {
        val file = Files.createTempFile("nimbus-config-test-", ".txt")
        try {
            val values = environment - "DB_PASSWORD" + ("DB_PASSWORD_FILE" to file.toString())
            val error = assertFailsWith<IllegalArgumentException> { ServerConfig.fromEnvironment(values::get) }
            assertFalse(error.message.orEmpty().contains(file.toString()))
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test fun secretFilesAreReadAndTrimmed() {
        val file = Files.createTempFile("nimbus-config-test-", ".txt")
        try {
            Files.writeString(file, "test-file-password\n")
            val values = environment - "DB_PASSWORD" + ("DB_PASSWORD_FILE" to file.toString())
            assertEquals("test-file-password", ServerConfig.fromEnvironment(values::get).dbPassword)
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
