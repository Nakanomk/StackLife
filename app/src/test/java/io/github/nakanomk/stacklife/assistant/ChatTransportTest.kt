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
        .addSubjectAlternativeName("127.0.0.1").addSubjectAlternativeName("api.openai.com").build()
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

    @Test fun streamsOrderedImagesOnlyOnLatestUserTurnIncludingToolRoundsAndImageOnly() = runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("stacklife-wire").toFile()
        try {
            val selected = listOf(pngFixture(dir, 121), pngFixture(dir, 127))
            val authorized = ServiceConnection(profile.copy(supportsImages = true), key)
            for (prompt in listOf("文字与图片，含引号 \"", "")) {
                val conversation = buildJsonArray {
                    addJsonObject { put("role", "user"); put("content", "historical image caption") }
                    addJsonObject { put("role", "assistant"); put("content", "old reply") }
                    addJsonObject { put("role", "user"); put("content", prompt) }
                    addJsonObject { put("role", "assistant"); put("content", JsonNull); putJsonArray("tool_calls") {
                        addJsonObject { put("id", "t1"); put("type", "function"); putJsonObject("function") { put("name", "planner_query"); put("arguments", "{}") } }
                    } }
                    addJsonObject { put("role", "tool"); put("tool_call_id", "t1"); put("content", "{}") }
                }
                val turn = TurnAttachments(2, selected)
                validateAttachmentTurn(authorized.profile, conversation, tools, turn)
                assertEquals(if (prompt.isEmpty()) 1 else 0, server.requestCount)
                server.enqueue(MockResponse(body = reply("""{"role":"assistant","content":"ok"}""")))
                transport.complete(authorized, conversation, tools, turn)
                val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                val wire = requireNotNull(request.body)
                assertEquals(wire.size.toLong(), request.headers["Content-Length"]!!.toLong())
                val sent = Json.parseToJsonElement(wire.utf8()).jsonObject.getValue("messages").jsonArray
                assertEquals(conversation.size, sent.size)
                for (i in conversation.indices) if (i != 2) assertEquals(conversation[i], sent[i])
                val parts = sent[2].jsonObject.getValue("content").jsonArray
                val textCount = if (prompt.isEmpty()) 0 else 1
                if (textCount == 1) assertEquals(buildJsonObject { put("type", "text"); put("text", prompt) }, parts.first())
                assertEquals(textCount + selected.size, parts.size)
                for ((i, entry) in selected.withIndex()) {
                    val part = parts[textCount + i].jsonObject
                    assertEquals(JsonPrimitive("image_url"), part["type"])
                    val data = part.getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content
                    assertTrue(data.startsWith("data:image/png;base64,"))
                    assertArrayEquals(entry.second.readBytes(), java.util.Base64.getDecoder().decode(data.substringAfter(',')))
                }
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun refusesUnreadyDisabledWrongRoleNoncurrentOrInjectedImagesWithoutStartingHttp() = runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("stacklife-wire-invalid").toFile()
        try {
            val entry = pngFixture(dir, 1)
            val enabled = ServiceConnection(profile.copy(supportsImages = true), key)
            val turn = TurnAttachments(0, listOf(entry))
            val invalidRole = Json.parseToJsonElement("""[{"role":"remote","content":"x"}]""").jsonArray
            val twoUsers = JsonArray(listOf(messages.single(), messages.single()))
            val completedTurn = Json.parseToJsonElement("""[{"role":"user","content":"previous"},{"role":"assistant","content":"answered"}]""").jsonArray
            val injected = Json.parseToJsonElement("""[{"role":"user","content":[{"type":"image_url","image_url":{"url":"https://private.invalid/media"}}]},{"role":"user","content":"new"}]""").jsonArray
            suspend fun blocked(c: ServiceConnection, m: JsonArray, t: TurnAttachments?) {
                assertTrue(runCatching { transport.complete(c, m, tools, t) }.exceptionOrNull() is ChatProblem)
            }
            blocked(connection, messages, turn)
            blocked(enabled, invalidRole, turn)
            blocked(enabled, twoUsers, turn)
            blocked(enabled, completedTurn, turn)
            blocked(enabled, messages, turn.copy(messageIndex = -1))
            blocked(enabled, injected, null)
            blocked(enabled, messages, turn.copy(entries = emptyList()))
            blocked(enabled, messages, turn.copy(entries = List(6) { entry }))
            for (bad in listOf(entry.first.copy(bytes = 0), entry.first.copy(width = 2049), entry.first.copy(width = 2),
                entry.first.copy(mime = "image/svg+xml"), entry.first.copy(mime = "image/jpeg"),
                entry.first.copy(id = "../escape"), entry.first.copy(bytes = MAX_IMAGE_INPUT_BYTES + 1))) {
                blocked(enabled, messages, TurnAttachments(0, listOf(bad to entry.second)))
            }
            entry.second.delete()
            blocked(enabled, messages, turn)
            assertEquals(0, server.requestCount)
        } finally { dir.deleteRecursively() }
    }

    @Test fun mixedFilesUseExactChatPartsOverLocalTlsAndNoFilesEndpoint() = runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("typed-wire").toFile()
        try {
            fun part(name: String, mime: String, data: String): Pair<ChatDocument, java.io.File> {
                val file = java.io.File(dir, java.util.UUID.randomUUID().toString()).apply { writeText(data) }
                return ChatDocument(file.name, mime, name, file.length()) to file
            }
            val note = "附件中的文本：\"工具\"。\n第二行"
            val text = part("notes.md", "text/markdown", note)
            val pdf = part("week \"1\".pdf", PDF_MIME, "%PDF-1.4\n1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n" +
                "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n" +
                "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 72 72] /Resources << >> >>\nendobj\n" +
                "xref\n0 4\n0000000000 65535 f \n0000000009 00000 n \n0000000058 00000 n \n0000000115 00000 n \n" +
                "trailer\n<< /Size 4 /Root 1 0 R >>\nstartxref\n201\n%%EOF\n")
            val png = pngFixture(dir, 3)
            val turn = TurnAttachments(1, listOf(text, png, pdf))
            val chat = Json.parseToJsonElement("""[{"role":"system","content":"system"},{"role":"user","content":""}]""").jsonArray
            // The proxy accepts CONNECT and terminates TLS on loopback. No DNS or real external credential.
            val proxy = java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress("127.0.0.1", server.port))
            val local = OpenAiTransport(client.newBuilder().proxy(proxy).dns { error("No external DNS") }.build())
            val official = ServiceConnection(serviceProfile(ServicePreset.Gpt.baseUrl, "gpt-5.4-mini"), key)
            server.enqueue(MockResponse.Builder().inTunnel().build())
            server.enqueue(MockResponse(body = reply("""{"role":"assistant","content":"ok"}""")))
            local.complete(official, chat, tools, turn)
            assertEquals("CONNECT", server.takeRequest(2, TimeUnit.SECONDS)!!.method)
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertNotNull(request.handshake)
            assertEquals("/v1/chat/completions", request.target)
            val body = requireNotNull(request.body)
            assertEquals(body.size.toLong(), request.headers["Content-Length"]!!.toLong())
            val payload = Json.parseToJsonElement(body.utf8()).jsonObject
            assertEquals(tools, payload["tools"])
            val sent = payload.getValue("messages").jsonArray
            assertEquals(chat.first(), sent.first())
            val parts = sent.last().jsonObject.getValue("content").jsonArray
            assertEquals(listOf("text", "image_url", "file"), parts.map { it.jsonObject.getValue("type").jsonPrimitive.content })
            assertEquals(note, parts[0].jsonObject.getValue("text").jsonPrimitive.content)
            val file = parts[2].jsonObject.getValue("file").jsonObject
            assertEquals(setOf("filename", "file_data"), file.keys)
            assertEquals(pdf.first.displayName, file.getValue("filename").jsonPrimitive.content)
            val data = file.getValue("file_data").jsonPrimitive.content
            assertEquals("data:application/pdf;base64", data.substringBefore(','))
            assertArrayEquals(pdf.second.readBytes(), java.util.Base64.getDecoder().decode(data.substringAfter(',')))
        } finally { dir.deleteRecursively() }
    }

    @Test fun invalidUnsupportedMissingAndEmptyDocumentsNeverStartHttp() = runBlocking {
        val file = java.nio.file.Files.createTempDirectory("document-preflight").toFile().resolve(java.util.UUID.randomUUID().toString())
        try {
            suspend fun rejected(name: String, mime: String, bytes: ByteArray, missing: Boolean = false) {
                file.writeBytes(bytes)
                val part = ChatDocument(file.name, mime, name, file.length())
                if (missing) file.delete()
                val turn = TurnAttachments(0, listOf(part to file))
                assertTrue(runCatching { transport.complete(connection, messages, tools, turn) }.exceptionOrNull() is ChatProblem)
            }
            rejected("bad.txt", "text/plain", byteArrayOf(0xc3.toByte(), 0x28))
            rejected("empty.txt", "text/plain", byteArrayOf())
            rejected("large.txt", "text/plain", ByteArray(65537) { 65 })
            rejected("sheet.xlsx", "text/plain", "no".encodeToByteArray())
            rejected("notes.txt", "application/json", "{}".encodeToByteArray())
            rejected("missing.csv", "text/csv", "a,b".encodeToByteArray(), missing = true)
            rejected("document.pdf", PDF_MIME, "%PDF-1.4\n%%EOF".encodeToByteArray())
            assertEquals(0, server.requestCount)
        } finally { file.parentFile!!.deleteRecursively() }
    }

    /** Real static RGB PNGs with multi-buffer IDAT and deterministic, non-sensitive pixels. */
    private fun pngFixture(dir: java.io.File, side: Int): Pair<ChatImage, java.io.File> {
        val id = java.util.UUID.randomUUID().toString()
        val file = java.io.File(dir, id)
        java.io.DataOutputStream(file.outputStream()).use { out ->
            out.writeLong(0x89504e470d0a1a0auL.toLong())
            fun chunk(name: String, data: ByteArray) {
                val tag = name.toByteArray(Charsets.US_ASCII)
                out.writeInt(data.size); out.write(tag); out.write(data)
                out.writeInt(java.util.zip.CRC32().apply { update(tag); update(data) }.value.toInt())
            }
            chunk("IHDR", java.nio.ByteBuffer.allocate(13).putInt(side).putInt(side).put(byteArrayOf(8, 2, 0, 0, 0)).array())
            val compressed = java.io.ByteArrayOutputStream()
            val random = java.util.Random(side.toLong())
            val row = ByteArray(3 * side)
            java.util.zip.DeflaterOutputStream(compressed).use { data ->
                repeat(side) { data.write(0); random.nextBytes(row); data.write(row) }
            }
            chunk("IDAT", compressed.toByteArray()); chunk("IEND", byteArrayOf())
        }
        return ChatImage(id, "image/png", side, side, file.length()) to file
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
