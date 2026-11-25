package io.github.nakanomk.stacklife.assistant

import io.github.nakanomk.stacklife.i18n.uiText

internal enum class ServicePreset(private val nameLabel: String, val baseUrl: String, val model: String) {
    DeepSeek("DeepSeek", "https://api.deepseek.com", "deepseek-flash"),
    Claude("Claude · Anthropic", "https://api.anthropic.com/v1", "claude-sonnet-5-5"),
    Gpt("GPT · OpenAI", "https://api.openai.com/v1", "gpt-5.4-mini"),
    Custom("Custom", "", "");

    val label: String get() = if (this == Custom) uiText(nameLabel, "自定义") else nameLabel

    // First-party inline/base64 limits only. A private gateway may impose smaller limits.
    fun inlineImageLength(bytes: Long): Long {
        val encoded = base64Length(bytes)
        if (this == DeepSeek && bytes > 32L * 1024 * 1024) throw ChatProblem(uiText(
            "An image exceeds DeepSeek’s 32 MiB inline image limit.", "有图片超过 DeepSeek 的 32 MiB 内联图片上限。"))
        if (this == Claude && encoded > 10_000_000L) throw ChatProblem(uiText(
            "An encoded image exceeds Claude’s 10 MB image limit.", "有图片编码后超过 Claude 的单张 10 MB 上限。"))
        return encoded
    }

    fun checkRequestLength(total: Long) {
        val limit = when (this) {
            DeepSeek -> 48L * 1024 * 1024
            Claude -> 128_000_000L
            Gpt -> 512_000_000L
            Custom -> MAX_CHAT_ATTACHMENTS * base64Length(MAX_IMAGE_INPUT_BYTES) + 4L * 1024 * 1024
        }
        if (total > limit) throw ChatProblem(uiText(
            { "This encoded request is $total bytes; the limit is $limit. Shorten the message or select smaller images." },
            { "编码后的请求为 $total 字节，上限为 $limit 字节。请缩短消息或选择较小的图片。" }))
    }

    companion object {
        fun forProfile(profile: ServiceProfile?): ServicePreset =
            if (profile == null) DeepSeek else entries.firstOrNull { it.baseUrl == profile.baseUrl } ?: Custom
    }
}

private fun base64Length(bytes: Long) = ((bytes + 2) / 3) * 4
