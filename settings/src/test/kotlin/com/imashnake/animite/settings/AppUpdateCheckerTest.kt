package com.imashnake.animite.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateCheckerTest {
    @Test
    fun `isNewerVersion detects minor and patch increments`() {
        assertTrue(AppUpdateChecker.isNewerVersion("1.1.5", "v1.2.0"))
        assertTrue(AppUpdateChecker.isNewerVersion("1.1.5", "1.1.6"))
        assertTrue(AppUpdateChecker.isNewerVersion("1.1.5", "2.0.0"))
        assertFalse(AppUpdateChecker.isNewerVersion("1.2.0", "v1.2.0"))
        assertFalse(AppUpdateChecker.isNewerVersion("1.2.0", "1.1.5"))
    }

    @Test
    fun `parseReleaseJson extracts apk asset when newer`() {
        val json = """
            {
              "tag_name": "v1.2.0",
              "html_url": "https://github.com/Arthur-001/AniGooli/releases/tag/v1.2.0",
              "body": "Added daily app update check.",
              "assets": [
                {
                  "name": "AniGooli-v1.2.0.apk",
                  "browser_download_url": "https://github.com/Arthur-001/AniGooli/releases/download/v1.2.0/AniGooli-v1.2.0.apk"
                }
              ]
            }
        """.trimIndent()

        val result = AppUpdateChecker.parseReleaseJson(json, currentVersion = "1.1.5")
        assertTrue(result is AppUpdateCheckResult.UpdateAvailable)
        val info = (result as AppUpdateCheckResult.UpdateAvailable).info
        assertEquals("v1.2.0", info.versionTag)
        assertEquals("1.2.0", info.versionName)
        assertEquals(
            "https://github.com/Arthur-001/AniGooli/releases/download/v1.2.0/AniGooli-v1.2.0.apk",
            info.apkDownloadUrl,
        )
    }

    @Test
    fun `parseReleaseJson returns UpToDate when version matches`() {
        val json = """
            {
              "tag_name": "v1.2.0",
              "html_url": "https://github.com/Arthur-001/AniGooli/releases/tag/v1.2.0",
              "body": "",
              "assets": []
            }
        """.trimIndent()

        val result = AppUpdateChecker.parseReleaseJson(json, currentVersion = "1.2.0")
        assertEquals(AppUpdateCheckResult.UpToDate("1.2.0"), result)
    }
}
