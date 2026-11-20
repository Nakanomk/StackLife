package io.github.nakanomk.stacklife.assistant

import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resumeWithException
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
import okio.BufferedSink

internal fun interface ChatTransport {
    suspend fun complete(connection: ServiceConnection, messages: JsonArray, tools: JsonArray): JsonObject
}

internal class ChatProblem(message: String) : IllegalStateException(message)
private fun incompatible(): Nothing = throw ChatProblem("服务返回的不是完整的 Chat Completions 回答，请检查模型是否支持 function tools。")
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

    override suspend fun complete(connection: ServiceConnection, messages: JsonArray, tools: JsonArray): JsonObject {
        val profile = serviceProfile(connection.profile.baseUrl, connection.profile.model)
        if (profile.origin != connection.profile.origin) throw ChatProblem("服务域已变化，请重新填写密钥并授权。")
        val names = tools.mapTo(mutableSetOf()) { tool ->
            val spec = tool as? JsonObject ?: incompatible()
            val name = (spec["function"] as? JsonObject)?.string("name") ?: incompatible()
            if (spec.string("type") != "function" || name !in ALLOWED_TOOLS) throw ChatProblem("不支持的助理工具。")
            name
        }
        if (names.size != tools.size) throw ChatProblem("助理工具定义重复。")
        val payload = buildJsonObject {
            put("model", profile.model); put("messages", messages); put("tools", tools)
            put("stream", false); put("parallel_tool_calls", false)
            if (profile.origin == ServicePreset.DeepSeek.baseUrl) {
                putJsonObject("thinking") { put("type", "disabled") }
            }
        }.toString().encodeToByteArray()
        val body = object : RequestBody() {
            override fun contentType() = JSON_TYPE
            override fun contentLength() = payload.size.toLong()
            override fun isOneShot() = true // Never replay a prompt, including an automatic HTTP follow-up.
            override fun writeTo(sink: BufferedSink) { sink.write(payload) }
        }
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
                    catch (_: SerializationException) { Result.failure(ChatProblem("服务响应不是有效的 Chat Completions JSON。")) }
                    pending.resumeWith(result)
                }
            })
        }
    }
}

private fun capped(value: Int, limit: Int): Long = (if (value == 0) limit else minOf(value, limit)).toLong()

private fun networkProblem(failure: IOException) = ChatProblem(when (failure) {
    is InterruptedIOException -> "服务请求超时，未应用任何更改。"
    is SSLException -> "无法验证服务的 HTTPS 连接，请检查地址和证书。"
    else -> "无法完成网络请求，请检查网络与服务地址。"
})

private fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun readReply(response: Response, names: Set<String>, key: String): JsonObject {
    if (!response.isSuccessful) throw ChatProblem(when (val code = response.code) {
        401, 403 -> "服务拒绝鉴权（$code），请检查密钥与模型权限。"
        429 -> "服务已限流或额度不足（429），请稍后再试。"
        in 300..399 -> "已拒绝服务重定向（$code），不会转发密钥。请直接填写最终 HTTPS 地址。"
        in 500..599 -> "模型服务暂不可用（$code），请稍后再试。"
        else -> "服务拒绝 Chat Completions 请求（$code），请检查 API 前缀、模型及工具支持。"
    })
    val body = response.body
    val source = body.source()
    if (body.contentLength() > MAX_BODY_BYTES || source.request(MAX_BODY_BYTES + 1)) {
        throw ChatProblem("服务响应过大，已停止读取。")
    }
    val root = Json.parseToJsonElement(source.readUtf8()) as? JsonObject ?: incompatible()
    if (root["error"] != null && root["error"] != JsonNull) incompatible()
    val choice = (root["choices"] as? JsonArray)?.singleOrNull() as? JsonObject ?: incompatible()
    val finish = choice.string("finish_reason")
    if (finish == "length") throw ChatProblem("模型输出被截断，请缩短请求后重试。")
    if (finish == "content_filter") throw ChatProblem("服务拒绝生成这次回答。")
    val message = choice["message"] as? JsonObject ?: incompatible()
    if (message["refusal"] != null && message["refusal"] != JsonNull) throw ChatProblem("服务拒绝生成这次回答。")
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
        if (value.contains(key)) throw ChatProblem("服务响应回显了连接密钥，已丢弃。")
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
