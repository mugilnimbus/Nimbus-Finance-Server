package com.nimbus.finance.server

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InferenceRoutesTest {
    private val owner = UUID.fromString("00000000-0000-0000-0000-000000000011")
    private val otherOwner = UUID.fromString("00000000-0000-0000-0000-000000000012")

    @Test
    fun retainedSessionIsOwnerIsolatedAndSingleGeneration() {
        val registry = InferenceSessionRegistry(60_000, 3)
        val session = registry.open(owner, request(), "gemma-4-e2b-it")
        val input = JsonPrimitive("hello")

        registry.begin(owner, session.id, input)
        assertFailsWith<IllegalArgumentException> { registry.begin(owner, session.id, input) }
        assertFailsWith<IllegalArgumentException> { registry.cancel(otherOwner, session.id) }

        registry.complete(session, input, listOf(JsonPrimitive("answer")), InferenceUsage(8, 4, 3, 11))
        val (_, history) = registry.begin(owner, session.id, JsonPrimitive("follow-up"))
        assertEquals(listOf(input, JsonPrimitive("answer"), JsonPrimitive("follow-up")), history)
        registry.fail(session)
    }

    @Test
    fun upstreamRequestUsesResponsesFormatWithoutServerToolsOrStorage() {
        val session = InferenceSessionRegistry(60_000, 3).open(owner, request(), "gemma-4-e2b-it")
        val body = buildUpstreamResponsesRequest(session, listOf(JsonPrimitive("hello")))

        assertEquals("gemma-4-e2b-it", body["model"]!!.jsonPrimitive.content)
        assertEquals("Short immutable prompt", body["instructions"]!!.jsonPrimitive.content)
        assertEquals(1, body["input"]!!.jsonArray.size)
        assertEquals(false, body["store"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(true, body["cache_prompt"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("none", body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertFalse("tools" in body)
        assertTrue(body["metadata"] is JsonObject)
    }

    @Test
    fun ownerRuntimeProfileOverridesPhoneInferenceLimits() {
        val runtime = InferenceRuntimeSettings(contextTokens = 16_384, maxOutputTokens = 768, reasoningEffort = "none")
        val phoneRequest = request().copy(contextTokens = 4_096, maxOutputTokens = 384)

        val session = InferenceSessionRegistry(60_000, 3).open(owner, phoneRequest, "gemma-4-e2b-it", runtime)

        assertEquals(16_384, session.contextTokens)
        assertEquals(768, session.maxOutputTokens)
        assertEquals("none", session.reasoningEffort)
    }

    private fun request() = InferenceSessionOpenRequest(
        sessionId = "00000000-0000-0000-0000-000000000021",
        systemPrompt = "Short immutable prompt"
    )
}
