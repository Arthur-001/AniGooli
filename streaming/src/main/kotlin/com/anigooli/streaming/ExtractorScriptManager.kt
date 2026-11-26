package com.anigooli.streaming

import android.content.Context
import android.util.Log
import com.imashnake.animite.api.preferences.PreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ExtractorScriptManager"
private const val MANIFEST_URL =
    "https://raw.githubusercontent.com/Arthur-001/AniGooli/main/scripts/extractor-manifest.json"
private const val UPSTREAM_ANI_CLI_URL =
    "https://raw.githubusercontent.com/pystardust/ani-cli/master/ani-cli"
private const val UPDATE_INTERVAL_MS = 6L * 60L * 60L * 1000L
private const val FALLBACK_SCRIPT_ASSET = "extractor_fallback.js"
private const val FALLBACK_MANIFEST_ASSET = "extractor_manifest_fallback.json"

/**
 * Manages downloading, SHA-256 verification, atomic caching, and upstream `ani-cli`
 * live config syncing for AniGooli's remote streaming extractor script.
 */
@Singleton
class ExtractorScriptManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: CloudflareAwareHttpClient,
    private val preferencesRepository: PreferencesRepository,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val updateMutex = Mutex()
    private val extractorDir = File(context.filesDir, "extractor")
    private val cachedScriptFile = File(extractorDir, "extractor.js")
    private val cachedManifestFile = File(extractorDir, "extractor-manifest.json")
    private val cachedUpstreamFile = File(extractorDir, "upstream-config.json")

    /** Returns the active extractor JS source and merged runtime config (remote cache + ani-cli override). */
    suspend fun getActiveScriptAndConfig(): Pair<String, ExtractorConfig> = withContext(Dispatchers.IO) {
        val manifest = readCachedManifest() ?: readBundledManifest()
        val script = readCachedScript(manifest) ?: readBundledScript()
        val upstreamOverlay = readUpstreamOverlay()
        val config = if (upstreamOverlay != null) {
            ExtractorConfig(
                miruroBase = upstreamOverlay.miruroBase,
                userAgent = upstreamOverlay.userAgent,
            )
        } else {
            manifest.defaultConfig
        }
        script to config
    }

    /** Returns the current script/upstream sync status for Settings display. */
    suspend fun getStatus(): ExtractorStatus = withContext(Dispatchers.IO) {
        val cachedManifest = readCachedManifest()
        val activeManifest = cachedManifest ?: readBundledManifest()
        val (_, activeConfig) = getActiveScriptAndConfig()
        val cachedVer = preferencesRepository.extractorCachedVersion.firstOrNull()
        val upstreamVer = preferencesRepository.upstreamAniCliVersion.firstOrNull()
        val lastChecked = preferencesRepository.extractorLastCheckedMs.firstOrNull() ?: 0L
        ExtractorStatus(
            scriptVersion = cachedVer ?: activeManifest.version,
            upstreamAniCliVersion = upstreamVer ?: activeManifest.upstreamAniCliVersion,
            lastCheckedMs = lastChecked,
            isFromRemoteCache = cachedManifest != null && cachedScriptFile.exists(),
            miruroBase = activeConfig.miruroBase,
        )
    }

    /** Refreshes both AniGooli's remote `extractor.js` and upstream `pystardust/ani-cli` domain/UA config. */
    suspend fun refreshIfNeeded(force: Boolean = false): ExtractorStatus = updateMutex.withLock {
        withContext(Dispatchers.IO) {
            val autoEnabled = preferencesRepository.extractorAutoUpdateEnabled.firstOrNull() ?: true
            val lastChecked = preferencesRepository.extractorLastCheckedMs.firstOrNull() ?: 0L
            val now = System.currentTimeMillis()
            if (!force && (!autoEnabled || now - lastChecked < UPDATE_INTERVAL_MS)) {
                return@withContext getStatus()
            }

            if (!extractorDir.exists()) extractorDir.mkdirs()

            runCatching {
                syncRemoteExtractor()
            }.onFailure { err ->
                Log.w(TAG, "Remote extractor sync skipped: ${err.message}")
            }

            runCatching {
                syncUpstreamAniCliConfig()
            }.onFailure { err ->
                Log.w(TAG, "Upstream ani-cli config sync skipped: ${err.message}")
            }

            preferencesRepository.setExtractorLastCheckedMs(now)
            getStatus()
        }
    }

    private suspend fun syncRemoteExtractor() {
        val manifestBody = httpClient.get(
            url = MANIFEST_URL,
            allowCloudflareBypass = false,
        ).body
        val manifest = json.decodeFromString<ExtractorManifest>(manifestBody)
        val existingManifest = readCachedManifest()
        val needsScriptFetch = !cachedScriptFile.exists() ||
            existingManifest?.sha256 != manifest.sha256 ||
            sha256Hex(cachedScriptFile.readBytes()) != manifest.sha256

        if (needsScriptFetch) {
            val scriptText = httpClient.get(
                url = manifest.scriptUrl,
                allowCloudflareBypass = false,
            ).body
            val actualHash = sha256Hex(scriptText.toByteArray(Charsets.UTF_8))
            require(actualHash.equals(manifest.sha256, ignoreCase = true)) {
                "Extractor SHA-256 mismatch: expected ${manifest.sha256}, got $actualHash"
            }
            val tmpScript = File(extractorDir, "extractor.js.tmp")
            tmpScript.writeText(scriptText, Charsets.UTF_8)
            if (!tmpScript.renameTo(cachedScriptFile)) {
                tmpScript.copyTo(cachedScriptFile, overwrite = true)
                tmpScript.delete()
            }
        }
        val tmpManifest = File(extractorDir, "extractor-manifest.json.tmp")
        tmpManifest.writeText(manifestBody, Charsets.UTF_8)
        if (!tmpManifest.renameTo(cachedManifestFile)) {
            tmpManifest.copyTo(cachedManifestFile, overwrite = true)
            tmpManifest.delete()
        }
        preferencesRepository.setExtractorCachedVersion(manifest.version)
    }

    private suspend fun syncUpstreamAniCliConfig() {
        val rawScript = httpClient.get(
            url = UPSTREAM_ANI_CLI_URL,
            allowCloudflareBypass = false,
        ).body
        val parsed = UpstreamAniCliParser.parse(rawScript) ?: return
        val cfg = ExtractorConfig(miruroBase = parsed.miruroBase, userAgent = parsed.userAgent)
        cachedUpstreamFile.writeText(json.encodeToString(cfg), Charsets.UTF_8)
        preferencesRepository.setUpstreamAniCliVersion(parsed.versionNumber)
    }

    private fun readCachedManifest(): ExtractorManifest? = runCatching {
        if (!cachedManifestFile.exists()) return null
        json.decodeFromString<ExtractorManifest>(cachedManifestFile.readText(Charsets.UTF_8))
    }.getOrNull()

    private fun readCachedScript(manifest: ExtractorManifest): String? = runCatching {
        if (!cachedScriptFile.exists()) return null
        val bytes = cachedScriptFile.readBytes()
        if (sha256Hex(bytes).equals(manifest.sha256, ignoreCase = true)) {
            String(bytes, Charsets.UTF_8)
        } else {
            null
        }
    }.getOrNull()

    private fun readUpstreamOverlay(): ExtractorConfig? = runCatching {
        if (!cachedUpstreamFile.exists()) return null
        json.decodeFromString<ExtractorConfig>(cachedUpstreamFile.readText(Charsets.UTF_8))
    }.getOrNull()

    private fun readBundledManifest(): ExtractorManifest {
        val text = context.assets.open(FALLBACK_MANIFEST_ASSET).bufferedReader().use { it.readText() }
        return json.decodeFromString(text)
    }

    private fun readBundledScript(): String {
        return context.assets.open(FALLBACK_SCRIPT_ASSET).bufferedReader().use { it.readText() }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
