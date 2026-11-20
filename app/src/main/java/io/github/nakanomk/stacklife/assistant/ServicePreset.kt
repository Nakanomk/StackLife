package io.github.nakanomk.stacklife.assistant

internal enum class ServicePreset(val label: String, val baseUrl: String, val model: String) {
    DeepSeek("DeepSeek", "https://api.deepseek.com", "deepseek-flash"),
    Claude("Claude · Anthropic", "https://api.anthropic.com/v1", "claude-sonnet-5-5"),
    Gpt("GPT · OpenAI", "https://api.openai.com/v1", "gpt-5.4-mini"),
    Custom("自定义", "", "");

    companion object {
        fun forProfile(profile: ServiceProfile?): ServicePreset =
            if (profile == null) DeepSeek else entries.firstOrNull { it.baseUrl == profile.baseUrl } ?: Custom
    }
}
