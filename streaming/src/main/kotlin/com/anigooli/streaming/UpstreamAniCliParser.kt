package com.anigooli.streaming

/** Parsed configuration values extracted directly from upstream pystardust/ani-cli shell script. */
data class UpstreamAniCliConfig(
    /** Upstream ani-cli semantic version string. */
    val versionNumber: String,
    /** Base URL of miruro configured in ani-cli. */
    val miruroBase: String,
    /** HTTP User-Agent string configured in ani-cli. */
    val userAgent: String,
)

/** Parses upstream `pystardust/ani-cli` shell script constants without executing shell code. */
object UpstreamAniCliParser {

    private val versionRegex = Regex("""^version_number="([^"]+)"""", RegexOption.MULTILINE)
    private val miruroRegex = Regex("""^miruro_base="([^"]+)"""", RegexOption.MULTILINE)
    private val agentRegex = Regex("""^agent="([^"]+)"""", RegexOption.MULTILINE)

    /** Extracts [UpstreamAniCliConfig] from raw `ani-cli` script text, or null if required fields are absent. */
    fun parse(scriptText: String): UpstreamAniCliConfig? {
        val version = versionRegex.find(scriptText)?.groupValues?.getOrNull(1)?.trim() ?: return null
        val miruroBase = miruroRegex.find(scriptText)?.groupValues?.getOrNull(1)?.trim()?.trimEnd('/')
            ?: return null
        val userAgent = agentRegex.find(scriptText)?.groupValues?.getOrNull(1)?.trim()
            ?: ExtractorConfig.DEFAULT_USER_AGENT
        if (!miruroBase.startsWith("http")) return null
        return UpstreamAniCliConfig(
            versionNumber = version,
            miruroBase = miruroBase,
            userAgent = userAgent,
        )
    }
}
