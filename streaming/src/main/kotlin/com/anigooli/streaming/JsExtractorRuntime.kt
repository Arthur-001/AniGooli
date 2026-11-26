package com.anigooli.streaming

import android.content.Context
import android.util.Log
import androidx.concurrent.futures.await
import androidx.javascriptengine.JavaScriptSandbox
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "JsExtractorRuntime"
private const val JS_EVAL_TIMEOUT_MS = 6_000L

/** Request descriptor returned by the remote extractor script. */
@Serializable
data class ExtractorRequest(
    /** Target HTTP URL. */
    val url: String,
    /** HTTP headers required by the endpoint. */
    val headers: Map<String, String> = emptyMap(),
)

/**
 * Executes AniGooli's updatable `extractor.js` inside AndroidX's sandboxed V8 [JavaScriptSandbox],
 * falling back seamlessly to [NativeMiruroParser] on devices without JavaScriptSandbox support.
 */
@Singleton
class JsExtractorRuntime @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scriptManager: ExtractorScriptManager,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Builds the provider search URL & headers via the active extractor script. */
    suspend fun buildSearchRequest(query: String): ExtractorRequest {
        val (script, config) = scriptManager.getActiveScriptAndConfig()
        val call = "JSON.stringify(AniGooliExtractor.buildSearchRequest(${quote(query)}, ${configJson(config)}));"
        return evaluateOrFallback(script, call) {
            val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
            ExtractorRequest(
                url = "${config.miruroBase.trimEnd('/')}/search?q=$encoded",
                headers = mapOf("User-Agent" to config.userAgent),
            )
        }
    }

    /** Parses provider search HTML via the active extractor script. */
    suspend fun parseSearchResponse(html: String): List<ProviderAnime> {
        val (script, config) = scriptManager.getActiveScriptAndConfig()
        val call = "JSON.stringify(AniGooliExtractor.parseSearchResponse(${quote(html)}, ${configJson(config)}));"
        return evaluateOrFallback(script, call) {
            NativeMiruroParser.parseSearchResponse(html)
        }
    }

    /** Builds the episode-list URL & headers via the active extractor script. */
    suspend fun buildEpisodesRequest(providerId: String): ExtractorRequest {
        val (script, config) = scriptManager.getActiveScriptAndConfig()
        val call = "JSON.stringify(AniGooliExtractor.buildEpisodesRequest(${quote(providerId)}, ${configJson(config)}));"
        return evaluateOrFallback(script, call) {
            val encoded = java.net.URLEncoder.encode(providerId, "UTF-8")
            ExtractorRequest(
                url = "${config.miruroBase.trimEnd('/')}/watch/$encoded/x?ep=1",
                headers = mapOf("User-Agent" to config.userAgent),
            )
        }
    }

    /** Parses available episode numbers from watch page HTML via the active extractor script. */
    suspend fun parseEpisodesResponse(html: String): List<String> {
        val (script, config) = scriptManager.getActiveScriptAndConfig()
        val call = "JSON.stringify(AniGooliExtractor.parseEpisodesResponse(${quote(html)}, ${configJson(config)}));"
        return evaluateOrFallback(script, call) {
            NativeMiruroParser.parseEpisodesResponse(html)
        }
    }

    /** Builds the episode stream page URL & headers via the active extractor script. */
    suspend fun buildMediaRequest(providerId: String, episode: String, mode: String): ExtractorRequest {
        val (script, config) = scriptManager.getActiveScriptAndConfig()
        val call = "JSON.stringify(AniGooliExtractor.buildMediaRequest(" +
            "${quote(providerId)}, ${quote(episode)}, ${quote(mode)}, ${configJson(config)}));"
        return evaluateOrFallback(script, call) {
            val ep = java.net.URLEncoder.encode(episode, "UTF-8")
            val id = java.net.URLEncoder.encode(providerId, "UTF-8")
            ExtractorRequest(
                url = "${config.miruroBase.trimEnd('/')}/watch/$id/x?ep=$ep",
                headers = mapOf("User-Agent" to config.userAgent),
            )
        }
    }

    /** Parses raw video stream candidates and English subtitle URLs from episode watch HTML. */
    suspend fun parseMediaResponse(html: String, mode: String): MediaCandidateList {
        val (script, config) = scriptManager.getActiveScriptAndConfig()
        val call = "JSON.stringify(AniGooliExtractor.parseMediaResponse(" +
            "${quote(html)}, ${quote(mode)}, ${configJson(config)}));"
        return evaluateOrFallback(script, call) {
            NativeMiruroParser.parseMediaResponse(html, mode)
        }
    }

    /** Parses an HLS master playlist via the active extractor script. */
    suspend fun parseM3u8(playlistText: String, baseUrl: String): M3u8ParseResult? {
        val (script, _) = scriptManager.getActiveScriptAndConfig()
        val call = "JSON.stringify(AniGooliExtractor.parseM3u8(${quote(playlistText)}, ${quote(baseUrl)}));"
        return evaluateOrFallback(script, call) {
            NativeMiruroParser.parseM3u8(playlistText, baseUrl)
        }
    }

    private suspend inline fun <reified T> evaluateOrFallback(
        script: String,
        callExpression: String,
        crossinline fallback: () -> T,
    ): T {
        if (!JavaScriptSandbox.isSupported()) {
            return fallback()
        }
        return runCatching {
            withTimeout(JS_EVAL_TIMEOUT_MS) {
                withContext(Dispatchers.Default) {
                    val sandbox = JavaScriptSandbox.createConnectedInstanceAsync(context).await()
                    try {
                        val isolate = sandbox.createIsolate()
                        try {
                            val resultJson = isolate.evaluateJavaScriptAsync("$script\n$callExpression").await()
                            json.decodeFromString<T>(resultJson)
                        } finally {
                            isolate.close()
                        }
                    } finally {
                        sandbox.close()
                    }
                }
            }
        }.getOrElse { err ->
            Log.w(TAG, "JavaScriptSandbox execution fallback triggered: ${err.message}")
            fallback()
        }
    }

    private fun quote(value: String): String = JSONObject.quote(value)

    private fun configJson(config: ExtractorConfig): String = json.encodeToString(config)
}
