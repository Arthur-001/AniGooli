package com.imashnake.animite.settings

import com.imashnake.animite.api.preferences.PreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

private const val GITHUB_LATEST_RELEASE_URL =
    "https://api.github.com/repos/Arthur-001/AniGooli/releases/latest"
private const val APP_UPDATE_INTERVAL_MS = 24L * 60L * 60L * 1000L
private const val CONNECT_TIMEOUT_MS = 8_000
private const val READ_TIMEOUT_MS = 8_000

/** Metadata for a newer AniGooli GitHub release. */
data class AppUpdateInfo(
    val versionTag: String,
    val versionName: String,
    val releaseUrl: String,
    val apkDownloadUrl: String?,
    val releaseNotes: String,
)

/** Result of checking GitHub Releases for a newer app version. */
sealed interface AppUpdateCheckResult {
    /** Skipped because a check already succeeded within the last 24 hours. */
    data object Skipped : AppUpdateCheckResult

    /** Current app version is already up to date. */
    data class UpToDate(val currentVersion: String) : AppUpdateCheckResult

    /** A newer release was found on GitHub. */
    data class UpdateAvailable(val info: AppUpdateInfo) : AppUpdateCheckResult

    /** Check failed due to network, HTTP error, or malformed response. */
    data class Error(val message: String) : AppUpdateCheckResult
}

/**
 * Checks GitHub Releases (`Arthur-001/AniGooli`) for newer app versions,
 * throttled to at most once every 24 hours unless [force] is true.
 */
@Singleton
class AppUpdateChecker @Inject constructor(
    private val preferencesRepository: PreferencesRepository,
) {
    private val checkMutex = Mutex()

    /**
     * Checks GitHub for a newer release than [currentVersion].
     * When [force] is false, returns [AppUpdateCheckResult.Skipped] if checked within 24 hours.
     */
    suspend fun checkForUpdate(
        currentVersion: String,
        force: Boolean = false,
    ): AppUpdateCheckResult = checkMutex.withLock {
        withContext(Dispatchers.IO) {
            val lastChecked = preferencesRepository.appUpdateLastCheckedMs.firstOrNull() ?: 0L
            val now = System.currentTimeMillis()
            if (!force && lastChecked > 0L && now - lastChecked < APP_UPDATE_INTERVAL_MS) {
                return@withContext AppUpdateCheckResult.Skipped
            }

            val response = runCatching { fetchLatestReleaseJson() }
            val body = response.getOrElse { err ->
                return@withContext AppUpdateCheckResult.Error(
                    err.message ?: "Unable to reach GitHub",
                )
            }

            val parsed = parseReleaseJson(body, currentVersion)
            if (parsed !is AppUpdateCheckResult.Error) {
                preferencesRepository.setAppUpdateLastCheckedMs(System.currentTimeMillis())
            }
            parsed
        }
    }

    private fun fetchLatestReleaseJson(): String {
        val connection = (URL(GITHUB_LATEST_RELEASE_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "AniGooli-Android")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                error("GitHub returned HTTP $code")
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    internal companion object {
        private val json = Json { ignoreUnknownKeys = true }

        @Serializable
        private data class GitHubReleaseDto(
            @SerialName("tag_name") val tagName: String = "",
            @SerialName("html_url") val htmlUrl: String = "",
            val body: String = "",
            val assets: List<GitHubAssetDto> = emptyList(),
        )

        @Serializable
        private data class GitHubAssetDto(
            val name: String = "",
            @SerialName("browser_download_url") val browserDownloadUrl: String = "",
        )

        internal fun parseReleaseJson(
            jsonBody: String,
            currentVersion: String,
        ): AppUpdateCheckResult {
            return runCatching {
                val release = json.decodeFromString<GitHubReleaseDto>(jsonBody)
                val tagName = release.tagName.trim()
                val htmlUrl = release.htmlUrl.trim()
                if (tagName.isEmpty() || htmlUrl.isEmpty()) {
                    return AppUpdateCheckResult.Error("Invalid GitHub release payload")
                }

                val normalizedVersion = tagName.removePrefix("v").removePrefix("V")
                if (!isNewerVersion(currentVersion, normalizedVersion)) {
                    return AppUpdateCheckResult.UpToDate(currentVersion)
                }

                val apkUrl = release.assets.firstOrNull { asset ->
                    asset.name.endsWith(".apk", ignoreCase = true) &&
                        asset.browserDownloadUrl.isNotBlank()
                }?.browserDownloadUrl

                AppUpdateCheckResult.UpdateAvailable(
                    AppUpdateInfo(
                        versionTag = tagName,
                        versionName = normalizedVersion,
                        releaseUrl = htmlUrl,
                        apkDownloadUrl = apkUrl,
                        releaseNotes = release.body.trim(),
                    )
                )
            }.getOrElse { err ->
                AppUpdateCheckResult.Error(err.message ?: "Failed to parse release info")
            }
        }

        internal fun isNewerVersion(currentVersion: String, latestVersion: String): Boolean {
            val currentParts = parseVersionParts(currentVersion)
            val latestParts = parseVersionParts(latestVersion)
            val maxLen = maxOf(currentParts.size, latestParts.size)
            for (i in 0 until maxLen) {
                val cur = currentParts.getOrElse(i) { 0 }
                val lat = latestParts.getOrElse(i) { 0 }
                if (lat > cur) return true
                if (lat < cur) return false
            }
            return false
        }

        private fun parseVersionParts(raw: String): List<Int> {
            val clean = raw.trim()
                .removePrefix("v")
                .removePrefix("V")
                .substringBefore('-')
                .substringBefore('+')
            return clean.split('.')
                .mapNotNull { it.toIntOrNull() }
        }
    }
}
