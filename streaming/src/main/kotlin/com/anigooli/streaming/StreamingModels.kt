package com.anigooli.streaming

import kotlinx.serialization.Serializable

/** Audio track choice supported by the streaming engine. */
enum class AudioTrack {
    JAPANESE,
    ENGLISH_DUB;

    /** Maps this audio track to the mode string expected by ani-cli / miruro. */
    fun toMode(): String = when (this) {
        JAPANESE -> "sub"
        ENGLISH_DUB -> "dub"
    }

    companion object {
        /** Safely parses a persisted audio track preference, defaulting to [JAPANESE]. */
        fun safeValueOf(raw: String?): AudioTrack = when (raw) {
            ENGLISH_DUB.name -> ENGLISH_DUB
            else -> JAPANESE
        }
    }
}

/** Censorship version choice for anime that have both uncensored and censored releases. */
enum class CensorshipVersion {
    /** Uncensored release (default). */
    UNCENSORED,
    /** Broadcast / censored release. */
    CENSORED;

    companion object {
        /** Safely parses a persisted censorship preference, defaulting to [UNCENSORED]. */
        fun safeValueOf(raw: String?): CensorshipVersion = when (raw?.uppercase()) {
            CENSORED.name -> CENSORED
            else -> UNCENSORED
        }
    }
}

/** Resolved provider entries separating uncensored and censored anime releases when available. */
data class ResolvedAnimeVersions(
    /** Uncensored provider entry if detected. */
    val uncensored: ProviderAnime? = null,
    /** Censored / standard broadcast provider entry if detected. */
    val censored: ProviderAnime? = null,
    /** Default entry prioritizing uncensored whenever available. */
    val defaultProvider: ProviderAnime,
) {
    /** True when both distinct uncensored and censored releases exist on the provider. */
    val hasMultipleVersions: Boolean
        get() = uncensored != null && censored != null && uncensored.providerId != censored.providerId

    /** Returns the provider entry matching [version], falling back to [defaultProvider]. */
    fun providerFor(version: CensorshipVersion): ProviderAnime = when (version) {
        CensorshipVersion.UNCENSORED -> uncensored ?: defaultProvider
        CensorshipVersion.CENSORED -> censored ?: defaultProvider
    }
}

/** Runtime scraper endpoint and header configuration. */
@Serializable
data class ExtractorConfig(
    /** Base URL of the miruro streaming site. */
    val miruroBase: String = DEFAULT_MIRURO_BASE,
    /** Browser-compatible User-Agent header. */
    val userAgent: String = DEFAULT_USER_AGENT,
) {
    companion object {
        /** Default upstream miruro base URL from ani-cli v5.2.0. */
        const val DEFAULT_MIRURO_BASE = "https://miruro.cx"
        /** Default desktop browser User-Agent string from ani-cli v5.2.0. */
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}

/** Remote manifest describing the auto-updating extractor script. */
@Serializable
data class ExtractorManifest(
    /** Semantic version of AniGooli's extractor script. */
    val version: String,
    /** Expected lowercase hex SHA-256 digest of extractor.js. */
    val sha256: String,
    /** Raw GitHub URL where extractor.js can be fetched. */
    val scriptUrl: String,
    /** Upstream pystardust/ani-cli version this script tracks. */
    val upstreamAniCliVersion: String = "5.2.0",
    /** Optional upstream scraper slice SHA-256 hash. */
    val upstreamScrapeHash: String = "",
    /** Minimum app versionCode compatible with this script. */
    val minAppVersion: Int = 1,
    /** Default scraper configuration shipped with the manifest. */
    val defaultConfig: ExtractorConfig = ExtractorConfig(),
)

/** Search result returned from the streaming provider. */
@Serializable
data class ProviderAnime(
    /** Provider-specific slug or ID used to fetch episodes. */
    val providerId: String,
    /** Linked AniList media ID if present on the provider. */
    val anilistId: Int? = null,
    /** Linked MyAnimeList ID if present on the provider. */
    val malId: Int? = null,
    /** Primary title from provider search results. */
    val title: String,
    /** Optional English title from provider search results. */
    val englishTitle: String? = null,
    /** Optional Romaji title from provider search results. */
    val romajiTitle: String? = null,
    /** Optional cover image URL extracted from provider search results. */
    val coverImage: String? = null,
)

/** A concrete playable/downloadable video source for a single resolution. */
@Serializable
data class StreamSource(
    /** Human-readable resolution label (e.g. "1080p", "720p", "default"). */
    val qualityLabel: String,
    /** Numeric vertical resolution in pixels (e.g. 1080), or 0 when unknown. */
    val height: Int,
    /** Name of the upstream streaming server. */
    val serverName: String,
    /** Direct MP4 or HLS playlist URL. */
    val url: String,
    /** Optional Referer header required by the CDN. */
    val referer: String = "",
    /** Optional Origin header required by the CDN. */
    val origin: String = "",
    /** Whether [url] points to an HLS (.m3u8) playlist. */
    val isHls: Boolean = false,
    /** Optional separate HLS audio track URL. */
    val audioUrl: String? = null,
    /** Optional English VTT subtitle URL. */
    val subtitleUrl: String? = null,
)

/** Resolved stream options and auto-selected source for one episode. */
data class EpisodeStreamBundle(
    /** Episode identifier string (e.g. "1", "12.5"). */
    val episode: String,
    /** Audio track initially requested by the user/settings. */
    val requestedAudioTrack: AudioTrack,
    /** Actual audio track resolved (may differ if dub was unavailable). */
    val actualAudioTrack: AudioTrack,
    /** True when English Dub was requested but fell back to Japanese Sub. */
    val isDubFallback: Boolean,
    /** All available stream sources sorted highest resolution first. */
    val sources: List<StreamSource>,
    /** Best matching source selected for the user's preferred quality. */
    val selectedSource: StreamSource,
)

/** Current status of the self-updating extractor engine. */
data class ExtractorStatus(
    /** Currently active script version. */
    val scriptVersion: String,
    /** Latest detected upstream pystardust/ani-cli version. */
    val upstreamAniCliVersion: String,
    /** Epoch millis when an update check last completed. */
    val lastCheckedMs: Long,
    /** True when using a remotely downloaded script rather than bundled assets. */
    val isFromRemoteCache: Boolean,
    /** Active miruro base URL currently in use. */
    val miruroBase: String,
)

/** Decoupled navigation bridge implemented by :player to launch in-app or external MPV playback. */
interface EpisodePlayerNavigator {
    /** Opens the in-app Media3 gesture player for an online stream. */
    fun openOnlineStream(
        anilistId: Int,
        animeTitle: String,
        coverUrl: String?,
        providerId: String,
        episode: String,
        allEpisodes: List<String>,
        bundle: EpisodeStreamBundle,
    )

    /** Opens the in-app Media3 gesture player for a locally downloaded episode file. */
    fun openLocalFile(
        anilistId: Int?,
        animeTitle: String,
        episode: String,
        videoFilePath: String,
        subtitleFilePath: String?,
    )

    /** Opens an online stream or local file directly in external MPV (`is.xyz.mpv`). Returns false if MPV is missing. */
    fun openInExternalMpv(
        title: String,
        urlOrPath: String,
        isLocalFile: Boolean,
        referer: String = "",
        origin: String = "",
        audioUrl: String? = null,
        subtitleUrl: String? = null,
    ): Boolean
}
