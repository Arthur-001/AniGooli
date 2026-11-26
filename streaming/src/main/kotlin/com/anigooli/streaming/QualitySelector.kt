package com.anigooli.streaming

/**
 * Selects the best [StreamSource] matching the user's preferred quality,
 * automatically falling back to the highest available resolution when absent.
 */
object QualitySelector {

    private val resolutionRegex = Regex("""(\d{3,4})p?\b""")
    private val matrixRegex = Regex("""\d{3,4}x(\d{3,4})""")

    /** Available global quality options shown in Settings. */
    val standardQualities: List<String> = listOf("360p", "480p", "720p", "1080p", "best")

    /** Extracts vertical pixel height from a quality label such as "1080p" or "1920x1080". */
    fun parseHeight(label: String?): Int {
        if (label.isNullOrBlank()) return 0
        matrixRegex.find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        resolutionRegex.find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        return 0
    }

    /** Sorts [sources] from highest resolution to lowest, preferring sources with subtitles on ties. */
    fun sortSources(sources: List<StreamSource>, preferSubtitles: Boolean = true): List<StreamSource> {
        return sources.sortedWith(
            compareByDescending<StreamSource> { it.height }
                .thenByDescending { if (preferSubtitles && !it.subtitleUrl.isNullOrBlank()) 1 else 0 }
                .thenByDescending { if (it.isHls) 1 else 0 }
                .thenBy { it.serverName }
        )
    }

    /**
     * Picks the source matching [preferredQuality] (e.g. "1080p", "720p", "360p", "best").
     * When [preferredQuality] is unavailable, automatically falls back to the highest available source.
     */
    fun selectSource(
        sources: List<StreamSource>,
        preferredQuality: String = "1080p",
        preferSubtitles: Boolean = true,
    ): StreamSource? {
        if (sources.isEmpty()) return null
        val ordered = sortSources(sources, preferSubtitles)
        val normalized = preferredQuality.trim().lowercase()
        if (normalized.isEmpty() || normalized == "best" || normalized == "auto") {
            return ordered.firstOrNull()
        }
        if (normalized == "worst") {
            return ordered.lastOrNull { it.height > 0 } ?: ordered.lastOrNull()
        }
        val targetHeight = parseHeight(normalized)
        if (targetHeight > 0) {
            ordered.firstOrNull { it.height == targetHeight }?.let { return it }
        }
        ordered.firstOrNull { it.qualityLabel.equals(normalized, ignoreCase = true) }?.let { return it }
        // Fallback: highest quality available
        return ordered.firstOrNull()
    }
}
