package io.github.nakanomk.stacklife.assistant

import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ChatTransportTest {
    private val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost")
        .addSubjectAlternativeName("127.0.0.1").build()
    private val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
    private val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
    private val client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
    private val server = MockWebServer()
    private val key = "fixture-only-bearer"
    private val profile get() = serviceProfile(server.url("/gateway/v1/").newBuilder().host("127.0.0.1").build().toString(), "fixture-model")
    private val connection get() = ServiceConnection(profile, key)
    private val messages = Json.parseToJsonElement("""[{"role":"user","content":"查今天"}]""").jsonArray
    private val tools = Json.parseToJsonElement("""[{"type":"function","function":{"name":"planner_query","parameters":{"type":"object"}}},{"type":"function","function":{"name":"planner_propose","parameters":{"type":"object"}}}]""").jsonArray
    private val transport = OpenAiTransport(client)

    @Before fun start() {
        server.useHttps(serverTls.sslSocketFactory())
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After fun close() { server.close(); client.connectionPool.evictAll() }

    private fun reply(message: String, finish: String = "stop") =
        """{"choices":[{"finish_reason":"$finish","message":$message}]}"""

    @Test fun sendsOnlyAuthorizedBodyToPrefixedTlsEndpointAndProjectsAnswer() = runBlocking {
        val cleanCall = """{"id":"call-1","type":"function","function":{"name":"planner_query","arguments":"{}"}}"""
        val fullCall = """{"id":"call-1","type":"function","secret_extra":"discard","function":{"name":"planner_query","arguments":"{}","reasoning":"discard"}}"""
        server.enqueue(MockResponse(body = reply("""{"role":"assistant","content":null,"tool_calls":[$fullCall],"reasoning":"discard"}""", "tool_calls")))
        val message = transport.complete(connection, messages, tools)
        assertEquals(Json.parseToJsonElement("""{"role":"assistant","content":null,"tool_calls":[$cleanCall]}"""), message)
        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertNotNull(request.handshake)
        assertEquals("POST", request.method)
        assertEquals("/gateway/v1/chat/completions", request.target)
        assertEquals("Bearer $key", request.headers["Authorization"])
        val body = Json.parseToJsonElement(requireNotNull(request.body).utf8()).jsonObject
        assertEquals(setOf("model", "messages", "tools", "stream", "parallel_tool_calls"), body.keys)
        assertEquals(JsonPrimitive(profile.model), body["model"])
        assertEquals(messages, body["messages"])
        assertEquals(tools, body["tools"])
        assertEquals(JsonPrimitive(false), body["parallel_tool_calls"])
        assertFalse(body.toString().contains(key))
        assertFalse(connection.toString().contains(key))
        server.enqueue(MockResponse(body = reply("""{"role":"assistant","content":"没有安排","reasoning_content":"discard"}""")))
        assertEquals(Json.parseToJsonElement("""{"role":"assistant","content":"没有安排"}"""), transport.complete(connection, messages, tools))
    }

    @Test fun neverFollowsCrossHostRedirectEvenWhenSuppliedClientEnablesIt() = runBlocking {
        MockWebServer().use { other ->
            other.useHttps(serverTls.sslSocketFactory()); other.start(InetAddress.getByName("127.0.0.1"), 0)
            val destination = other.url("/different").newBuilder().host("localhost").build()
            other.enqueue(MockResponse(body = reply("""{"role":"assistant","content":"unreachable"}""")))
            server.enqueue(MockResponse.Builder().code(307).addHeader("Location", destination).build())
            val permissive = OpenAiTransport(client.newBuilder().followRedirects(true).followSslRedirects(true).build())
            val error = runCatching { permissive.complete(connection, messages, tools) }.exceptionOrNull()
            assertTrue(error is ChatProblem && "307" in error.message.orEmpty())
            assertEquals(1, server.requestCount)
            assertEquals(0, other.requestCount)
        }
    }

    @Test fun cancellingWhileBodyArrivesCancelsTheActualCall() = runBlocking {
        val reading = CountDownLatch(1)
        val released = CountDownLatch(1)
        val observer = object : EventListener() {
            override fun responseBodyStart(call: Call) { reading.countDown() }
            override fun callFailed(call: Call, ioe: IOException) { if (call.isCanceled()) released.countDown() }
        }
        server.enqueue(MockResponse.Builder().body("x".repeat(4096)).throttleBody(32, 150, TimeUnit.MILLISECONDS).build())
        val observed = OpenAiTransport(client.newBuilder().eventListener(observer).build())
        val work = launch(start = CoroutineStart.UNDISPATCHED) { observed.complete(connection, messages, tools) }
        try { assertTrue("body read started", reading.await(5, TimeUnit.SECONDS)) }
        finally { work.cancelAndJoin() }
        assertTrue("cancellation released the exchange before the body arrived", released.await(3, TimeUnit.SECONDS))
    }

    @Test fun reportsHttpFailuresWithoutDisclosingBodiesOrKeysAndDoesNotReplay503() = runBlocking {
        for (code in listOf(401, 429, 503)) {
            server.enqueue(MockResponse.Builder().code(code).body("server-private $key").addHeader("Retry-After", "0").build())
            val error = runCatching { transport.complete(connection, messages, tools) }.exceptionOrNull()
            assertTrue(error is ChatProblem && "$code" in error.message.orEmpty())
            assertFalse(error.toString().contains("server-private")); assertFalse(error.toString().contains(key))
        }
        assertEquals(3, server.requestCount)
    }

    @Test fun rejectsPartialRefusedOversizedAndIncompatibleReplies() = runBlocking {
        val normal = """{"role":"assistant","content":"partial text"}"""
        val unknown = """{"role":"assistant","tool_calls":[{"type":"function","id":"1","function":{"name":"planner_commit","arguments":"{}"}}]}"""
        for (body in listOf(reply(normal, "length"), reply(normal, "content_filter"),
            reply("""{"role":"assistant","content":"wrong","refusal":"manufacturer message"}"""),
            reply(normal, "tool_calls"), reply(unknown, "tool_calls"), reply("""{"role":"assistant","content":"$key"}"""),
            """{"output_text":"Responses only"}""", "{".repeat(3), "a".repeat(512 * 1024 + 1))) {
            server.enqueue(MockResponse(body = body))
            val error = runCatching { transport.complete(connection, messages, tools) }.exceptionOrNull()
            assertTrue(error is ChatProblem)
            assertFalse(error.toString().contains(key))
        }
    }

    @Test fun timedOutBodyFailsWithoutEchoingRemoteText() = runBlocking {
        server.enqueue(MockResponse.Builder().body("sensitive text").bodyDelay(1, TimeUnit.SECONDS).build())
        val short = OpenAiTransport(client.newBuilder().readTimeout(200, TimeUnit.MILLISECONDS).build())
        val error = runCatching { short.complete(connection, messages, tools) }.exceptionOrNull()
        assertTrue(error is ChatProblem && "超时" in error.message.orEmpty())
        assertFalse(error.toString().contains("sensitive"))
    }

    @Test fun rejectsForgedOriginAndExtraToolsBeforeAnyHttpRequest() = runBlocking {
        val forged = ServiceConnection(profile.copy(origin = "https://other.invalid"), key)
        val extra = Json.parseToJsonElement("""[{"type":"function","function":{"name":"planner_commit"}}]""").jsonArray
        assertTrue(runCatching { transport.complete(forged, messages, tools) }.exceptionOrNull() is ChatProblem)
        assertTrue(runCatching { transport.complete(connection, messages, extra) }.exceptionOrNull() is ChatProblem)
        assertEquals(0, server.requestCount)
    }

    @Test fun canonicalOriginIncludesNonDefaultPortButNeverAPathOrCredentials() {
        assertEquals(ServiceProfile("https://example.invalid/api/v1", "m", "https://example.invalid"),
            serviceProfile("HTTPS://EXAMPLE.INVALID:443/api/v1/", "m"))
        assertEquals("https://example.invalid:8443", serviceProfile("https://example.invalid:8443/x", "m").origin)
        for (bad in listOf("http://example.invalid", "https://u:p@example.invalid", "https://@example.invalid", "https://example.invalid/?q=1", "https://example.invalid/#", "https:example.invalid")) {
            assertTrue(runCatching { serviceProfile(bad, "m") }.isFailure)
        }
    }
}
