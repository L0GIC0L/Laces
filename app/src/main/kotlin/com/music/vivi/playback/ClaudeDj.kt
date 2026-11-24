package com.music.vivi.playback

import android.content.Context
import androidx.media3.common.MediaItem
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.music.innertube.YouTube
import com.music.innertube.models.SongItem
import com.music.innertube.models.filterExplicit
import com.music.vivi.constants.ClaudeDjApiKey
import com.music.vivi.constants.HideExplicitKey
import com.music.vivi.db.MusicDatabase
import com.music.vivi.extensions.toMediaItem
import com.music.vivi.utils.dataStore
import com.music.vivi.utils.get
import com.music.vivi.utils.reportException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Turns a spoken request ("something upbeat for a long drive", "more like Tame Impala")
 * into a playable queue. Used by Android Auto / assistant voice search and the in-app mic button.
 */
object ClaudeDj {
    private const val MODEL = "claude-opus-5-5"
    private const val MAX_SONGS = 15

    private val SYSTEM_PROMPT = """
        You are the DJ inside a music app, often used while driving. The listener spoke a request
        out loud and it was transcribed for you, so expect speech-to-text mistakes and fix obvious ones.
        Reply with up to $MAX_SONGS real, existing songs that fit the request, one per line,
        formatted exactly as: Song Title - Artist
        If the listener names a specific song, put that song first, then follow with songs that suit it.
        If the listener names an artist, mix their best-known songs with a few close matches.
        Output only the list: no numbering, no commentary.
    """.trimIndent()

    /**
     * Asks Claude for songs and resolves them on YouTube Music. Falls back to a plain song search
     * when no Claude API key is set or the request fails; a blank query shuffles the library.
     */
    suspend fun buildQueue(context: Context, database: MusicDatabase, query: String): List<MediaItem> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) {
                return@withContext database.allSongs().first().shuffled().map { it.toMediaItem() }
            }

            val hideExplicit = context.dataStore.get(HideExplicitKey, false)
            val apiKey = context.dataStore.get(ClaudeDjApiKey, "")

            suspend fun searchSongs(text: String): List<SongItem> =
                YouTube.search(text, YouTube.SearchFilter.FILTER_SONG).getOrNull()
                    ?.items
                    ?.filterIsInstance<SongItem>()
                    ?.filterExplicit(hideExplicit)
                    ?: emptyList()

            val picks = if (apiKey.isBlank()) {
                emptyList()
            } else {
                try {
                    pickSongs(apiKey, query)
                } catch (e: Exception) {
                    reportException(e)
                    emptyList()
                }
            }

            if (picks.isEmpty()) {
                return@withContext searchSongs(query).map { it.toMediaItem() }
            }

            picks
                .map { pick -> async { searchSongs(pick).firstOrNull() } }
                .awaitAll()
                .filterNotNull()
                .distinctBy { it.id }
                .map { it.toMediaItem() }
        }

    private fun pickSongs(apiKey: String, request: String): List<String> {
        val client = AnthropicOkHttpClient.builder().apiKey(apiKey).build()
        try {
            val params = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(4000L)
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                .system(SYSTEM_PROMPT)
                .addUserMessage(request)
                .build()

            return client.messages().create(params).content()
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
