package io.github.nakanomk.stacklife.assistant

import java.net.Proxy
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class ProviderRequestTest {
    @Test fun officialImageDefaultsRequireCanonicalEndpointAndExactModel() {
        for (preset in listOf(ServicePreset.DeepSeek, ServicePreset.Claude, ServicePreset.Gpt)) {
            assertTrue(serviceProfile("${preset.baseUrl}/", preset.model).supportsImages)
            assertFalse(serviceProfile(preset.baseUrl, "${preset.model}-other").supportsImages)
            assertFalse(serviceProfile("${preset.baseUrl}/proxy", preset.model).supportsImages)
            assertFalse(serviceProfile("https://private.invalid/v1", preset.model).supportsImages)
            assertTrue(serviceProfile("https://private.invalid/v1", preset.model, supportsImages = true).supportsImages)
        }
    }

    @Test fun inlineLimitsCountRealBase64PaddingAndEntireEncodedRequest() {
        assertEquals(4L, ServicePreset.Claude.inlineImageLength(1))
        assertEquals(10_000_000L, ServicePreset.Claude.inlineImageLength(7_500_000))
        assertTrue(runCatching { ServicePreset.Claude.inlineImageLength(7_500_001) }.exceptionOrNull() is ChatProblem)
        val deepSeekImage = 32L * 1024 * 1024
        assertTrue(ServicePreset.DeepSeek.inlineImageLength(deepSeekImage) > deepSeekImage)
        assertTrue(runCatching { ServicePreset.DeepSeek.inlineImageLength(deepSeekImage + 1) }.exceptionOrNull() is ChatProblem)
        for ((preset, bytes) in listOf(ServicePreset.DeepSeek to 48L * 1024 * 1024,
            ServicePreset.Claude to 128_000_000L, ServicePreset.Gpt to 512_000_000L)) {
            preset.checkRequestLength(bytes)
            assertTrue(runCatching { preset.checkRequestLength(bytes + 1) }.exceptionOrNull() is ChatProblem)
        }
    }

    @Test fun presetsAddressOfficialChatEndpointsAndOnlyDeepSeekDisablesThinking() = runBlocking {
        val messages = buildJsonArray { addJsonObject { put("role", "user"); put("content", "查队列") } }
        val tools = buildJsonArray { addJsonObject {
            put("type", "function")
            putJsonObject("function") { put("name", "planner_query"); putJsonObject("parameters") { put("type", "object") } }
        } }
        lateinit var request: Request
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).dns { error("Test must not connect") }
            .eventListener(object : EventListener() {
                override fun callStart(call: Call) { request = call.request(); call.cancel() }
            }).build()
        val transport = OpenAiTransport(client)
        for ((preset, endpoint) in listOf(
            ServicePreset.DeepSeek to "https://api.deepseek.com/chat/completions",
            ServicePreset.Claude to "https://api.anthropic.com/v1/chat/completions",
            ServicePreset.Gpt to "https://api.openai.com/v1/chat/completions",
        )) {
            val key = UUID.randomUUID().toString()
            val connection = ServiceConnection(serviceProfile(preset.baseUrl, preset.model), key)
            assertTrue(runCatching { transport.complete(connection, messages, tools) }.exceptionOrNull() is ChatProblem)
            assertEquals(endpoint, request.url.toString())
            assertEquals("Bearer $key", request.header("Authorization"))
            val body = Buffer().use { buffer -> request.body!!.writeTo(buffer); Json.parseToJsonElement(buffer.readUtf8()).jsonObject }
            assertEquals(JsonPrimitive(preset.model), body["model"])
            assertEquals(tools, body["tools"])
            if (preset == ServicePreset.DeepSeek) assertEquals(JsonPrimitive("disabled"), body["thinking"]?.jsonObject?.get("type"))
            else assertFalse(body.containsKey("thinking"))
        }
    }
}
