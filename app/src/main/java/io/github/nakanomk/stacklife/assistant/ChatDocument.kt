package io.github.nakanomk.stacklife.assistant

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import io.github.nakanomk.stacklife.i18n.uiText
import io.github.nakanomk.stacklife.planner.privatePhotoId
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal sealed interface ChatAttachment {
    val id: String
    val mime: String
    val bytes: Long
}

internal data class ChatDocument(override val id: String, override val mime: String, val displayName: String,
    override val bytes: Long) : ChatAttachment

private const val TEXT_LIMIT = 64 * 1024L
private const val PDF_LIMIT = 4 * 1024 * 1024L
internal const val PDF_MIME = "application/pdf"
private val textTypes = mapOf("txt" to "text/plain", "md" to "text/markdown", "csv" to "text/csv", "json" to "application/json")
// Android MediaProvider rescans published .csv files as text/comma-separated-values.
private val documentAliases = mapOf("text/comma-separated-values" to "text/csv", "text/x-markdown" to "text/markdown")
internal fun documentMimeTypes(pdf: Boolean) = (textTypes.values + documentAliases.keys + if (pdf) listOf(PDF_MIME) else emptyList()).toTypedArray()

internal val ServiceProfile?.acceptsPdf: Boolean get() = this != null && supportsImages &&
    origin == "https://api.openai.com" && baseUrl == "https://api.openai.com/v1" &&
    (model == "gpt-5.4-mini" || model == "gpt-5.4-mini-2026-03-17")

private fun invalidDocument(): Nothing = throw ChatProblem(uiText("Invalid or empty document. Choose a readable UTF-8 TXT, MD, CSV or JSON file, or an accepted PDF.", "文件无效或为空。请选择可读的 UTF-8 TXT、MD、CSV、JSON 或受支持的 PDF。"))
internal fun pdfUnavailable(): Nothing = throw ChatProblem(uiText("PDF is only available with image input enabled for GPT-5.4 mini on OpenAI’s official /v1 endpoint.", "PDF 仅适用于 OpenAI 官方 /v1 地址上已启用图像输入的 GPT-5.4 mini。"))
private fun tooLarge(pdf: Boolean): Nothing = throw ChatProblem(if (pdf) uiText("PDF files must be at most 4 MiB.", "PDF 最大为 4 MiB。") else uiText("Text files must be at most 64 KiB.", "文本文件最大为 64 KiB。"))
private fun canonicalMime(name: String): String {
    if (name.length !in 1..160 || name.any { it == '/' || it == '\\' || it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }) invalidDocument()
    val ext = name.substringAfterLast('.', "").lowercase()
    return if (ext == "pdf") PDF_MIME else textTypes[ext] ?: invalidDocument()
}

/** OpenDocument supplies a read grant, not a filesystem path. Never follow URLs or retain that grant. */
internal suspend fun copyDocument(resolver: ContentResolver, uri: Uri, pending: File, id: String, allowPdf: Boolean): ChatDocument {
    if (uri.scheme != ContentResolver.SCHEME_CONTENT) invalidDocument()
    val (name, claimedSize) = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { rows ->
        if (!rows.moveToFirst()) invalidDocument()
        val size = rows.getColumnIndex(OpenableColumns.SIZE)
        (rows.getString(rows.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) ?: invalidDocument()) to
            (if (size < 0 || rows.isNull(size)) null else rows.getLong(size))
    } ?: invalidDocument()
    val mime = canonicalMime(name)
    val pdf = mime == PDF_MIME
    if (pdf && !allowPdf) pdfUnavailable()
    val declared = resolver.getType(uri)?.substringBefore(';')?.trim()?.lowercase()
    if ((documentAliases[declared] ?: declared) != mime && !(declared == "text/plain" && !pdf)) invalidDocument()
    val limit = if (pdf) PDF_LIMIT else TEXT_LIMIT
    if (claimedSize != null && claimedSize > limit) tooLarge(pdf)
    var bytes = 0L
    resolver.openInputStream(uri)?.use { input -> pending.outputStream().use { output ->
        val buffer = ByteArray(8192)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count == -1) break
            if (count == 0) invalidDocument()
            bytes += count
            if (bytes > limit) tooLarge(pdf)
            output.write(buffer, 0, count)
        }
    } } ?: invalidDocument()
    if (claimedSize != null && claimedSize >= 0 && bytes != claimedSize) invalidDocument()
    return ChatDocument(id, mime, name, bytes).also { it.readValidated(pending) }
}

/** Return text only for text parts. A PDF is checked in place and streamed by the HTTP body. */
internal fun ChatDocument.readValidated(file: File): String? {
    if (!privatePhotoId.matches(id) || canonicalMime(displayName) != mime || !file.isFile || bytes != file.length() || bytes < 1) invalidDocument()
    if (bytes > if (mime == PDF_MIME) PDF_LIMIT else TEXT_LIMIT) tooLarge(mime == PDF_MIME)
    try {
        if (mime == PDF_MIME) {
            RandomAccessFile(file, "r").use { pdf ->
                if (pdf.length() < 10 || pdf.readInt() != 0x25504446 || pdf.read() != '-'.code) invalidDocument()
                val tail = ByteArray(minOf(bytes, 1024L).toInt())
                pdf.seek(bytes - tail.size); pdf.readFully(tail)
                if (!tail.toString(Charsets.ISO_8859_1).trimEnd().endsWith("%%EOF")) invalidDocument()
            }
            return null
        }
        val data = ByteArray(bytes.toInt())
        java.io.DataInputStream(file.inputStream()).use { it.readFully(data); if (it.read() != -1) invalidDocument() }
        val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(data)).toString().removePrefix("\uFEFF")
        if (text.isBlank() || text.any { it.isISOControl() && it !in "\t\n\r" }) invalidDocument()
        return text
    } catch (_: CharacterCodingException) { invalidDocument() }
      catch (_: IOException) { invalidDocument() }
}
