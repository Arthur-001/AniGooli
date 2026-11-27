package com.anigooli.library

/**
 * Normalizes anime/manga titles to their whole-series base name and detects
 * duplicate or related franchise entries (seasons, OVAs, movies, manga) so
 * My List contains at most one entry per anime franchise.
 */
object AnimeTitleNormalizer {

    private val parentheticalFormatRegex = Regex(
        """(?i)\s*\((?:tv|ova|oad|ona|movie|specials?|manga|light\s+novel|one[- ]?shot)\)\s*$"""
    )
    private val seasonKeywordRegex = Regex(
        """(?i)\s*[-:–—]?\s*(?:the\s+)?(?:final\s+)?(?:season|series|part|cour)\s*\d*\s*$"""
    )
    private val ordinalSeasonRegex = Regex(
        """(?i)\s*[-:–—]?\s*(\d+)(?:st|nd|rd|th)\s+(?:season|part|cour)\s*$"""
    )
    private val shortSeasonRegex = Regex("""(?i)\s*[-:–—]?\s*S(\d{1,2})\s*$""")
    private val romanSuffixRegex = Regex("""\s*[-:–—]?\s*(II|III|IV|V|VI|VII|VIII|IX|X)\s*$""")
    private val mediaSuffixRegex = Regex(
        """(?i)\s*[-:–—]?\s*(?:the\s+movie|movie|ova|oad|specials?)\s*$"""
    )
    private val trailingNumberRegex = Regex("""\s+(\d{1,2})\s*$""")
    private val nonWordRegex = Regex("""[^\p{L}\p{N}\s]+""")
    private val multiSpaceRegex = Regex("""\s+""")

    private const val MIN_PREFIX_LEN = 3

    /**
     * Strips trailing season, part, cour, OVA, movie, and format suffixes so that
     * e.g. `"Attack on Titan Season 2"` becomes `"Attack on Titan"`.
     */
    fun displayWholeTitle(rawTitle: String?): String? {
        val original = rawTitle?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        var s = original
        var changed = true
        while (changed) {
            changed = false
            for (regex in listOf(
                parentheticalFormatRegex,
                ordinalSeasonRegex,
                seasonKeywordRegex,
                shortSeasonRegex,
                romanSuffixRegex,
                mediaSuffixRegex,
            )) {
                val next = regex.replace(s, "").trim()
                if (next != s && next.length >= MIN_PREFIX_LEN) {
                    s = next
                    changed = true
                }
            }
            val numMatch = trailingNumberRegex.find(s)
            if (numMatch != null) {
                val prefix = s.substring(0, numMatch.range.first).trim()
                if (prefix.length >= MIN_PREFIX_LEN) {
                    s = prefix
                    changed = true
                }
            }
        }
        return s.ifBlank { original }
    }

    /** Produces a punctuation-insensitive lowercase key for duplicate comparison. */
    fun canonicalKey(rawTitle: String?): String {
        val whole = displayWholeTitle(rawTitle) ?: return ""
        return nonWordRegex.replace(whole.lowercase(), " ")
            .let { multiSpaceRegex.replace(it, " ") }
            .trim()
    }

    /** Returns true if any non-empty title in [titlesA] shares a canonical key with [titlesB]. */
    fun titlesMatch(titlesA: List<String?>, titlesB: List<String?>): Boolean {
        val keysA = titlesA.map(::canonicalKey).filter { it.isNotEmpty() }.toSet()
        if (keysA.isEmpty()) return false
        val keysB = titlesB.map(::canonicalKey).filter { it.isNotEmpty() }
        return keysB.any { it in keysA }
    }
}
