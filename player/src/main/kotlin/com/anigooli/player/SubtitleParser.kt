package com.anigooli.player

/** A single timed subtitle cue with [startMs]..[endMs] in milliseconds and plain [text]. */
data class SubtitleCue(
    /** Cue start timestamp in milliseconds. */
    val startMs: Long,
    /** Cue end timestamp in milliseconds. */
    val endMs: Long,
    /** Normalized display text for this cue. */
    val text: String,
)

/**
 * Lightweight parser for WebVTT (`.vtt`), SubRip (`.srt`), and ASS/SSA (`.ass`/`.ssa`) subtitles,
 * enabling reliable in-app Compose subtitle rendering without Media3 `MergingMediaSource` stalls.
 */
object SubtitleParser {

    private val timingLineRegex = Regex(
        """(\d{1,2}:)?(\d{1,2}):(\d{2})[.,](\d{2,3})\s*-->\s*(\d{1,2}:)?(\d{1,2}):(\d{2})[.,](\d{2,3})""",
    )
    private val assDialogueRegex = Regex(
        """^Dialogue:\s*\d+,(\d+):(\d{2}):(\d{2})\.(\d{2}),(\d+):(\d{2}):(\d{2})\.(\d{2}),[^,]*,[^,]*,\d+,\d+,\d+,[^,]*,(.*)$""",
    )
    private val htmlTagRegex = Regex("""<[^>]+>""")
    private val assTagRegex = Regex("""\{[^}]*\}""")

    /** Parses [rawText] (VTT, SRT, or ASS/SSA) into a time-sorted list of [SubtitleCue]. */
    fun parse(rawText: String): List<SubtitleCue> {
        val cleaned = rawText.removePrefix("\uFEFF").replace("\r\n", "\n").replace("\r", "\n")
        if (cleaned.isBlank()) return emptyList()

        return if (cleaned.contains("[Events]") || cleaned.contains("Dialogue:", ignoreCase = false)) {
            parseAss(cleaned)
        } else {
            parseVttOrSrt(cleaned)
        }
    }

    /** Returns all cue texts active at [positionMs]. */
    fun activeCueTexts(cues: List<SubtitleCue>, positionMs: Long): List<String> {
        if (cues.isEmpty() || positionMs < 0L) return emptyList()
        // Binary search to find first cue whose startMs > positionMs, then scan backwards slightly for overlaps
        var lo = 0
        var hi = cues.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (cues[mid].startMs <= positionMs) lo = mid + 1 else hi = mid
        }
        val result = ArrayList<String>()
        var idx = (lo - 1).coerceAtMost(cues.lastIndex)
        while (idx >= 0) {
            val cue = cues[idx]
            if (cue.endMs >= positionMs && cue.startMs <= positionMs) {
                result.add(cue.text)
            } else if (positionMs - cue.startMs > 30_000L && cue.endMs < positionMs) {
                break
            }
            idx--
        }
        return result.asReversed()
    }

    private fun parseVttOrSrt(text: String): List<SubtitleCue> {
        val lines = text.split("\n")
        val cues = ArrayList<SubtitleCue>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            val match = timingLineRegex.find(line)
            if (match != null) {
                val startMs = parseTimestamp(
                    hoursStr = match.groupValues[1].trimEnd(':'),
                    minutesStr = match.groupValues[2],
                    secondsStr = match.groupValues[3],
                    millisStr = match.groupValues[4],
                )
                val endMs = parseTimestamp(
                    hoursStr = match.groupValues[5].trimEnd(':'),
                    minutesStr = match.groupValues[6],
                    secondsStr = match.groupValues[7],
                    millisStr = match.groupValues[8],
                )
                i++
                val bodyLines = ArrayList<String>()
                while (i < lines.size && lines[i].isNotBlank()) {
                    bodyLines.add(lines[i].trim())
                    i++
                }
                val body = sanitizeText(bodyLines.joinToString("\n"))
                if (body.isNotEmpty() && endMs > startMs) {
                    cues.add(SubtitleCue(startMs, endMs, body))
                }
            } else {
                i++
            }
        }
        cues.sortBy { it.startMs }
        return cues
    }

    private fun parseAss(text: String): List<SubtitleCue> {
        val cues = ArrayList<SubtitleCue>()
        for (rawLine in text.split("\n")) {
            val line = rawLine.trim()
            val match = assDialogueRegex.find(line) ?: continue
            val startMs = parseTimestamp(
                hoursStr = match.groupValues[1],
                minutesStr = match.groupValues[2],
                secondsStr = match.groupValues[3],
                millisStr = "${match.groupValues[4]}0",
            )
            val endMs = parseTimestamp(
                hoursStr = match.groupValues[5],
                minutesStr = match.groupValues[6],
                secondsStr = match.groupValues[7],
                millisStr = "${match.groupValues[8]}0",
            )
            val rawBody = match.groupValues[9]
                .replace("\\N", "\n")
                .replace("\\n", "\n")
                .replace("\\h", " ")
            val body = sanitizeText(rawBody)
            if (body.isNotEmpty() && endMs > startMs) {
                cues.add(SubtitleCue(startMs, endMs, body))
            }
        }
        cues.sortBy { it.startMs }
        return cues
    }

    private fun parseTimestamp(
        hoursStr: String,
        minutesStr: String,
        secondsStr: String,
        millisStr: String,
    ): Long {
        val hours = hoursStr.toLongOrNull() ?: 0L
        val minutes = minutesStr.toLongOrNull() ?: 0L
        val seconds = secondsStr.toLongOrNull() ?: 0L
        val paddedMillis = millisStr.padEnd(3, '0').take(3).toLongOrNull() ?: 0L
        return ((hours * 3600L) + (minutes * 60L) + seconds) * 1000L + paddedMillis
    }

    private fun sanitizeText(raw: String): String {
        return raw
            .replace(assTagRegex, "")
            .replace(htmlTagRegex, "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .trim()
    }
}
