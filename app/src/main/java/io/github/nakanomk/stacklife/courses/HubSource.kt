package io.github.nakanomk.stacklife.courses

import io.github.nakanomk.stacklife.i18n.uiText
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeToSequence

internal const val HUB_URL = "https://hubs.hust.edu.cn/basicInformation/scheduleInformation/index"
internal const val HUB_LOGIN_URL = "https://hubs.hust.edu.cn/login"
private const val byteLimit = 2 * 1024 * 1024

private fun httpsPage(url: String?): URI? {
    if (url == null) return null
    val uri = try { URI(url) } catch (_: java.net.URISyntaxException) { return null }
    return uri.takeIf { it.scheme.equals("https", ignoreCase = true) && it.host != null && it.rawUserInfo == null }
}
internal fun isScheduleRoute(url: String?): Boolean = httpsPage(url)?.let {
    it.host.equals("hubs.hust.edu.cn", ignoreCase = true) && (it.port == -1 || it.port == 443) &&
        it.rawPath == "/basicInformation/scheduleInformation/index" && it.rawFragment.isNullOrEmpty()
} == true

internal class SourceProblem(message: String) : IOException(message)

/** kotlinx.serialization's stream decoder replaces malformed UTF-8. Don't silently change a course name. */
private class DocumentStream(input: InputStream) : FilterInputStream(input) {
    private var left = byteLimit
    private var continuation = 0
    private var low = 0x80
    private var high = 0xBF

    private fun invalid(): Nothing = throw SourceProblem(uiText("The file must contain valid UTF-8 JSON.", "文件须为有效的 UTF-8 JSON"))
    private fun validate(b: Int) {
        if (continuation != 0) {
            if (b !in low..high) invalid()
            continuation--
            low = 0x80; high = 0xBF
        } else when (b) {
            in 0..0x7F -> Unit
            in 0xC2..0xDF -> continuation = 1
            in 0xE0..0xEF -> {
                continuation = 2
                if (b == 0xE0) low = 0xA0 // Not an overlong two-byte scalar.
                if (b == 0xED) high = 0x9F // No surrogate code points.
            }
            in 0xF0..0xF4 -> {
                continuation = 3
                if (b == 0xF0) low = 0x90
                if (b == 0xF4) high = 0x8F // At most U+10FFFF.
            }
            else -> invalid()
        }
    }
    private fun count(n: Int) {
        if (n < 0 && continuation != 0) invalid()
        if (n > left) throw SourceProblem(uiText("The file exceeds 2 MiB. Select the raw week array for a single term.", "文件超过 2 MiB，请选择单个学期的原始周数组"))
        if (n > 0) left -= n
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val n = super.read(buffer, offset, minOf(left + 1, length))
        count(n)
        for (i in offset until offset + n) validate(buffer[i].toInt() and 0xFF)
        return n
    }
    override fun read(): Int = super.read().also { b ->
        count(if (b < 0) -1 else 1)
        if (b >= 0) validate(b)
    }
}

/** Parse one row at a time. Never trust a document provider's reported length or truncate an oversized file. */
@OptIn(ExperimentalSerializationApi::class)
internal fun readWeekFile(stream: InputStream): JsonArray {
    val input = DocumentStream(stream).buffered()
    input.mark(3)
    if (input.read() != 0xEF || input.read() != 0xBB || input.read() != 0xBF) input.reset()
    return try {
        JsonArray(buildList {
            for (row in Json.decodeToSequence<JsonElement>(input, DecodeSequenceMode.ARRAY_WRAPPED)) {
                if (size >= 2048) throw SourceProblem(uiText("Too many week records. Select a file for a single term.", "周记录过多，请选择单个学期的文件"))
                add(row)
            }
        })
    } catch (_: IllegalArgumentException) {
        throw SourceProblem(uiText("Invalid JSON. Select the raw week array returned by getStudentScheduleByXqh.", "JSON 格式错误，需要 getStudentScheduleByXqh 返回的原始周数组"))
    }
}

private fun parseRows(text: String): JsonArray {
    val value = try { Json.parseToJsonElement(text) } catch (_: IllegalArgumentException) {
        throw SourceProblem(uiText("Invalid JSON. Select the raw HUB week array.", "JSON 格式错误，需要 HUB 原始周数组"))
    }
    return value as? JsonArray ?: throw SourceProblem(uiText("Unsupported format. Select the raw week array returned by getStudentScheduleByXqh.", "不支持此格式，需要 getStudentScheduleByXqh 返回的原始周数组"))
}

/** evaluateJavascript encodes the returned JS string once more. Exceptions never include page content. */
internal fun decodeHubReply(encoded: String?): JsonArray {
    if (encoded == null || encoded.length > 6 * 1024 * 1024) throw SourceProblem(uiText("The page did not return a readable single-term timetable.", "网页未返回可读取的单学期课表"))
    val value = try { Json.parseToJsonElement(encoded) as? JsonPrimitive } catch (_: IllegalArgumentException) { null }
    val inner = value?.takeIf { it.isString }?.content ?: throw SourceProblem(uiText("Extraction failed. The page may have changed or the timetable may have moved.", "提取失败，页面可能已离开或结构已改变"))
    return when (inner) {
        "\"no_table\"" -> throw SourceProblem(uiText("HUB timetable not found. Sign in, select the term and wait for it to load, or use a raw file.", "未找到 HUB 总课表：请登录、选好学期并等待加载，或使用原始文件"))
        "\"loading\"" -> throw SourceProblem(uiText("The timetable is still loading. Try extracting it again later.", "课表仍在加载，请稍后重新提取"))
        "\"wrong_route\"" -> throw SourceProblem(uiText("Extraction is only available on the HUB timetable page.", "仅可在 HUB 总课表页面提取"))
        "\"too_large\"" -> throw SourceProblem(uiText("The data exceeds the limit for a single term.", "数据超过单学期读取上限"))
        else -> parseRows(inner)
    }
}
