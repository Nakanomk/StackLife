package io.github.nakanomk.stacklife.assistant

import io.github.nakanomk.stacklife.i18n.uiText
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.OutputStream
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.Buffer
import okio.ByteString
import okio.BufferedSink

internal data class TurnAttachments(val messageIndex: Int, val entries: List<Pair<ChatAttachment, File>>)

internal interface ChatTransport {
    suspend fun complete(connection: ServiceConnection, messages: JsonArray, tools: JsonArray, attachments: TurnAttachments? = null): JsonObject
}

internal class ChatProblem(message: String) : IllegalStateException(message)
private fun incompatible(): Nothing = throw ChatProblem(uiText("The service did not return a complete Chat Completions answer. Check that the model supports function tools.", "服务返回的不是完整的 Chat Completions 回答，请检查模型是否支持 function tools。"))
private const val MAX_BODY_BYTES = 512L * 1024
private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
private val ALLOWED_TOOLS = setOf("planner_query", "planner_propose")

internal class OpenAiTransport(client: OkHttpClient = OkHttpClient()) : ChatTransport {
    private val http = client.newBuilder()
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .cookieJar(CookieJar.NO_COOKIES).cache(null)
        .apply { interceptors().clear(); networkInterceptors().clear() }
        .callTimeout(capped(client.callTimeoutMillis, 60_000), TimeUnit.MILLISECONDS)
        .connectTimeout(capped(client.connectTimeoutMillis, 15_000), TimeUnit.MILLISECONDS)
        .readTimeout(capped(client.readTimeoutMillis, 45_000), TimeUnit.MILLISECONDS)
        .writeTimeout(capped(client.writeTimeoutMillis, 20_000), TimeUnit.MILLISECONDS)
        .build()

    override suspend fun complete(connection: ServiceConnection, messages: JsonArray, tools: JsonArray, attachments: TurnAttachments?): JsonObject {
        val profile = serviceProfile(connection.profile.baseUrl, connection.profile.model, connection.profile.supportsImages)
        if (profile.origin != connection.profile.origin) throw ChatProblem(uiText("The service origin has changed. Enter your key again to authorize it.", "服务域已变化，请重新填写密钥并授权。"))
        val names = tools.mapTo(mutableSetOf()) { tool ->
            val spec = tool as? JsonObject ?: incompatible()
            val name = (spec["function"] as? JsonObject)?.string("name") ?: incompatible()
            if (spec.string("type") != "function" || name !in ALLOWED_TOOLS) throw ChatProblem(uiText("Unsupported assistant tool.", "不支持的助理工具。"))
            name
        }
        if (names.size != tools.size) throw ChatProblem(uiText("Duplicate assistant tool definition.", "助理工具定义重复。"))
        val body = if (attachments == null) prepareBody(profile, messages, tools, null)
            else withContext(Dispatchers.IO) { prepareBody(profile, messages, tools, attachments) }
        val endpoint = profile.baseUrl.toHttpUrl().newBuilder().addPathSegments("chat/completions").build()
        val request = Request.Builder().url(endpoint).header("Authorization", "Bearer ${connection.key}")
            .header("Accept", "application/json").post(body).build()
        return suspendCancellableCoroutine { pending ->
            val call = http.newCall(request)
            pending.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { pending.resumeWithException(networkProblem(e)) }

                override fun onResponse(call: Call, response: Response) {
                    // Keep the cancellation handler on the same call until *after* the body is consumed.
                    val result = try {
                        response.use { Result.success(readReply(it, names, connection.key)) }
                    } catch (problem: ChatProblem) { Result.failure(problem) }
                    catch (network: IOException) { Result.failure(networkProblem(network)) }
                    catch (_: SerializationException) { Result.failure(ChatProblem(uiText("The service response is not valid Chat Completions JSON.", "服务响应不是有效的 Chat Completions JSON。"))) }
                    pending.resumeWith(result)
                }
            })
        }
    }
}

/** Same local preflight before history claims the files and before each explicitly authorized tool round. */
internal suspend fun validateAttachmentTurn(profile: ServiceProfile, messages: JsonArray, tools: JsonArray, attachments: TurnAttachments) {
    withContext(Dispatchers.IO) { prepareBody(profile, messages, tools, attachments) }
}

private fun invalidTurn(): Nothing = throw ChatProblem(uiText("Attach files or images to the current user message only.", "只能给本次发送的用户消息附加文件或图片。"))
private fun imageNotReady(): Nothing = throw ChatProblem(uiText("An image is not ready or no longer available. Remove it or select it again.", "有图片尚未就绪或已失效，请移除或重新选择。"))

private data class UploadPart(val prefix: ByteString, val bytes: Long, val file: File)

private fun prepareBody(profile: ServiceProfile, messages: JsonArray, tools: JsonArray, attachments: TurnAttachments?): RequestBody {
        var lastUser = -1
        for ((index, element) in messages.withIndex()) {
            val message = element as? JsonObject ?: invalidTurn()
            val role = message.string("role")
            when (role) {
                "user" -> lastUser = index
                "assistant", "system", "tool" -> Unit
                else -> invalidTurn()
            }
            // No caller may inject prebuilt content parts, URLs or images from older history.
            if (message.string("content") == null && !(role == "assistant" &&
                    (message["content"] ?: JsonNull) == JsonNull && message["tool_calls"] is JsonArray)) invalidTurn()
            if (message.keys.any { it != "role" && it != "content" &&
                    !(role == "tool" && it == "tool_call_id") && !(role == "assistant" && it == "tool_calls") }) invalidTurn()
        }
        if (lastUser < 0) invalidTurn()
        if (attachments != null) {
            // After the explicit prompt the only valid suffix is completed tool exchanges from this send.
            var tail = lastUser + 1
            while (tail < messages.size) {
                val reply = messages[tail].jsonObject
                val result = messages.getOrNull(tail + 1) as? JsonObject ?: invalidTurn()
                val call = (reply["tool_calls"] as? JsonArray)?.singleOrNull() as? JsonObject ?: invalidTurn()
                val id = call.string("id") ?: invalidTurn()
                if (reply.string("role") != "assistant" || result.string("role") != "tool" || result.string("tool_call_id") != id) invalidTurn()
                tail += 2
            }
            if (attachments.messageIndex != lastUser) invalidTurn()
            if (!profile.supportsImages && attachments.entries.any { it.first is ChatImage }) throw ChatProblem(uiText(
                "Image input is not enabled for this endpoint and model. Check the service settings or remove the images.",
                "此服务地址和模型未启用图片输入。请检查服务配置或移除图片。"))
            if (attachments.entries.size !in 1..MAX_CHAT_ATTACHMENTS) throw ChatProblem(uiText("Select one to five attachments per message.", "每条消息请选择一至五个附件。"))
        } else if (messages[lastUser].jsonObject.string("content").isNullOrBlank()) invalidTurn()

        val preset = ServicePreset.forProfile(profile)
        val header = Buffer().writeUtf8("{\"model\":").writeUtf8(JsonPrimitive(profile.model).toString()).writeUtf8(",\"messages\":[")
        val parts = ArrayList<UploadPart>(attachments?.entries?.size ?: 0)
        var uploadLength = 0L
        for ((index, message) in messages.withIndex()) {
            if (index > 0) header.writeByte(','.code)
            if (attachments == null || index != attachments.messageIndex) {
                header.writeUtf8(message.toString())
                continue
            }
            header.writeUtf8("{\"role\":\"user\",\"content\":[")
            val text = message.jsonObject.getValue("content").jsonPrimitive
            val includeText = text.content.isNotEmpty()
            if (includeText) header.writeUtf8("{\"type\":\"text\",\"text\":").writeUtf8(text.toString()).writeByte('}'.code)
            val ids = mutableSetOf<String>()
            for ((ordinal, entry) in attachments.entries.withIndex()) {
                val (attachment, file) = entry
                if (!ids.add(attachment.id) || file.name != attachment.id) invalidTurn()
                if (includeText || ordinal > 0) header.writeByte(','.code)
                val length = when (attachment) {
                    is ChatImage -> {
                        if (!attachment.matchesPreparedFile(file)) imageNotReady()
                        header.writeUtf8("{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:").writeUtf8(attachment.mime).writeUtf8(";base64,")
                        preset.inlineImageLength(attachment.bytes)
                    }
                    is ChatDocument -> {
                        if (attachment.mime == PDF_MIME && !profile.acceptsPdf) pdfUnavailable()
                        val content = attachment.readValidated(file)
                        if (content != null) {
                            header.writeUtf8("{\"type\":\"text\",\"text\":").writeUtf8(JsonPrimitive(content).toString()).writeByte('}'.code)
                            continue
                        }
                        header.writeUtf8("{\"type\":\"file\",\"file\":{\"filename\":").writeUtf8(JsonPrimitive(attachment.displayName).toString())
                            .writeUtf8(",\"file_data\":\"data:application/pdf;base64,")
                        ((attachment.bytes + 2) / 3) * 4
                    }
                }
                val prefix = header.readByteString()
                parts += UploadPart(prefix, attachment.bytes, file)
                uploadLength += prefix.size + length
                header.writeUtf8("\"}}")
            }
            header.writeUtf8("]}")
        }
        header.writeUtf8("],\"tools\":").writeUtf8(tools.toString()).writeUtf8(",\"stream\":false,\"parallel_tool_calls\":false")
        if (profile.origin == ServicePreset.DeepSeek.baseUrl) header.writeUtf8(",\"thinking\":{\"type\":\"disabled\"}")
        val suffix = header.writeByte('}'.code).readByteString()
        val total = uploadLength + suffix.size
        preset.checkRequestLength(total)

        return object : RequestBody() {
            override fun contentType() = JSON_TYPE
            override fun contentLength() = total
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) {
                if (parts.isNotEmpty()) {
                    val output = object : OutputStream() {
                        override fun write(value: Int) { sink.writeByte(value) }
                        override fun write(bytes: ByteArray, offset: Int, length: Int) { sink.write(bytes, offset, length) }
                        // The per-image encoder closes this adapter to emit padding, not the HTTP sink.
                    }
                    val buffer = ByteArray(32 * 1024)
                    for (part in parts) {
                        sink.write(part.prefix)
                        part.file.inputStream().use { input ->
                            Base64.getEncoder().wrap(output).use { encoder ->
                                var left = part.bytes
                                while (left > 0) {
                                    val count = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
                                    if (count <= 0) throw IOException("Attachment length changed")
                                    encoder.write(buffer, 0, count)
                                    left -= count
                                }
                                if (input.read() != -1) throw IOException("Attachment length changed")
                            }
                        }
                    }
                }
                sink.write(suffix)
            }
        }
    }

private fun capped(value: Int, limit: Int): Long = (if (value == 0) limit else minOf(value, limit)).toLong()

private fun networkProblem(failure: IOException) = ChatProblem(when (failure) {
    is InterruptedIOException -> uiText("The request timed out. No changes were applied.", "服务请求超时，未应用任何更改。")
    is SSLException -> uiText("Could not verify the HTTPS connection. Check the service address and certificate.", "无法验证服务的 HTTPS 连接，请检查地址和证书。")
    else -> uiText("The network request failed. Check your network and service address.", "无法完成网络请求，请检查网络与服务地址。")
})

private fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun readReply(response: Response, names: Set<String>, key: String): JsonObject {
    if (!response.isSuccessful) throw ChatProblem(when (val code = response.code) {
        401, 403 -> uiText({ "The service refused authorization ($code). Check the key and model permissions." }, { "服务拒绝鉴权（$code），请检查密钥与模型权限。" })
        429 -> uiText("The service rate limit or quota was reached (429). Try later.", "服务已限流或额度不足（429），请稍后再试。")
        in 300..399 -> uiText({ "Service redirect refused ($code). Your key was not forwarded. Enter the final HTTPS address directly." }, { "已拒绝服务重定向（$code），不会转发密钥。请直接填写最终 HTTPS 地址。" })
        in 500..599 -> uiText({ "The model service is temporarily unavailable ($code). Try later." }, { "模型服务暂不可用（$code），请稍后再试。" })
        else -> uiText({ "The service rejected the Chat Completions request ($code). Check the API prefix, model, and tool support." }, { "服务拒绝 Chat Completions 请求（$code），请检查 API 前缀、模型及工具支持。" })
    })
    val body = response.body
    val source = body.source()
    if (body.contentLength() > MAX_BODY_BYTES || source.request(MAX_BODY_BYTES + 1)) {
        throw ChatProblem(uiText("Stopped reading because the service response was too large.", "服务响应过大，已停止读取。"))
    }
    val root = Json.parseToJsonElement(source.readUtf8()) as? JsonObject ?: incompatible()
    if (root["error"] != null && root["error"] != JsonNull) incompatible()
    val choice = (root["choices"] as? JsonArray)?.singleOrNull() as? JsonObject ?: incompatible()
    val finish = choice.string("finish_reason")
    if (finish == "length") throw ChatProblem(uiText("The model output was cut off. Shorten the request and try again.", "模型输出被截断，请缩短请求后重试。"))
    if (finish == "content_filter") throw ChatProblem(uiText("The service declined to answer this request.", "服务拒绝生成这次回答。"))
    val message = choice["message"] as? JsonObject ?: incompatible()
    if (message["refusal"] != null && message["refusal"] != JsonNull) throw ChatProblem(uiText("The service declined to answer this request.", "服务拒绝生成这次回答。"))
    if (message.string("role") != "assistant" || (message["function_call"] ?: JsonNull) != JsonNull) incompatible()
    val content = message.string("content")
    if (content == null && (message["content"] ?: JsonNull) != JsonNull) incompatible()
    val calls = when (val raw = message["tool_calls"]) {
        null, JsonNull -> null
        is JsonArray -> raw.takeIf { it.isNotEmpty() }
        else -> incompatible()
    }
    if (calls == null) {
        if (finish != "stop" || content.isNullOrBlank()) incompatible()
    } else if (finish != "tool_calls") incompatible()

    // Neither display a provider's credential echo nor pass it back in a subsequent tool turn.
    fun safe(value: String): String {
        if (value.contains(key)) throw ChatProblem(uiText("The service echoed your connection key. The response was discarded.", "服务响应回显了连接密钥，已丢弃。"))
        return value
    }
    val ids = mutableSetOf<String>()
    return buildJsonObject {
        put("role", "assistant")
        put("content", content?.let { JsonPrimitive(safe(it)) } ?: JsonNull)
        if (calls != null) put("tool_calls", buildJsonArray {
            for (item in calls) {
                val call = item as? JsonObject ?: incompatible()
                val id = call.string("id")?.takeIf { it.isNotBlank() && ids.add(it) } ?: incompatible()
                if (call.string("type") != "function") incompatible()
                val function = call["function"] as? JsonObject ?: incompatible()
                val name = function.string("name")?.takeIf { it in names } ?: incompatible()
                val arguments = function.string("arguments") ?: incompatible()
                if (Json.parseToJsonElement(arguments) !is JsonObject) incompatible()
                add(buildJsonObject {
                    put("id", safe(id)); put("type", "function")
                    put("function", buildJsonObject { put("name", name); put("arguments", safe(arguments)) })
                })
            }
        })
    }
}
