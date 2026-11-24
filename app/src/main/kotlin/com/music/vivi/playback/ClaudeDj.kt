package com.music.vivi.playback

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Turns a spoken request ("something upbeat for a long drive", "more like Tame Impala")
 * into a list of "Title - Artist" search strings that can be resolved on YouTube Music.
 */
object ClaudeDj {
    private const val MODEL = "claude-opus-5-5"
    const val MAX_SONGS = 15

    private val SYSTEM_PROMPT = """
        You are the DJ inside a car music app. The driver spoke a request out loud and it was
        transcribed for you, so expect speech-to-text mistakes and fix obvious ones.
        Reply with up to $MAX_SONGS real, existing songs that fit the request, one per line,
        formatted exactly as: Song Title - Artist
        If the driver names a specific song, put that song first, then follow with songs that suit it.
        If the driver names an artist, mix their best-known songs with a few close matches.
        Output only the list: no numbering, no commentary.
    """.trimIndent()

    /** Returns search strings, or an empty list if Claude gave nothing usable. */
    suspend fun pickSongs(apiKey: String, request: String): List<String> = withContext(Dispatchers.IO) {
        val client = AnthropicOkHttpClient.builder().apiKey(apiKey).build()
        try {
            val params = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(4000L)
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                .system(SYSTEM_PROMPT)
                .addUserMessage(request)
                .build()

            client.messages().create(params).content()
                .flatMap { block -> block.text().map { listOf(it.text()) }.orElse(emptyList()) }
                .flatMap { it.lines() }
                .map { it.trim().trimStart('-', '*', '•', ' ').replace(Regex("^\\d+[.)]\\s*"), "") }
                .filter { it.isNotBlank() }
                .take(MAX_SONGS)
        } finally {
            client.close()
        }
    }
}
