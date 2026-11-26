package com.anigooli.player

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.anigooli.streaming.ExtractorConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val MPV_PACKAGE = "is.xyz.mpv"
private const val MPV_ACTIVITY = "is.xyz.mpv.MPVActivity"

/**
 * Launches external `mpv-android` (`is.xyz.mpv`) seamlessly for both locally downloaded
 * episodes (via `FileProvider`) and online streams (via [LocalHeaderProxy] when Referer/Origin
 * headers are required).
 */
@Singleton
class ExternalPlayerLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val headerProxy: LocalHeaderProxy,
) {
    /** Opens the stream or local file in external MPV. Returns false if MPV is not installed. */
    fun launchMpv(
        title: String,
        urlOrPath: String,
        isLocalFile: Boolean,
        referer: String = "",
        origin: String = "",
        audioUrl: String? = null,
        subtitleUrl: String? = null,
    ): Boolean {
        val authority = "${context.packageName}.fileprovider"
        val videoUri: Uri = if (isLocalFile) {
            FileProvider.getUriForFile(context, authority, File(urlOrPath))
        } else {
            val needsProxy = referer.isNotBlank() || origin.isNotBlank()
            val effectiveUrl = if (needsProxy) {
                headerProxy.registerStream(
                    targetUrl = urlOrPath,
                    referer = referer,
                    origin = origin,
                    userAgent = ExtractorConfig.DEFAULT_USER_AGENT,
                )
            } else {
                urlOrPath
            }
            Uri.parse(effectiveUrl)
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setComponent(ComponentName(MPV_PACKAGE, MPV_ACTIVITY))
            setDataAndType(videoUri, "video/*")
            putExtra("title", title)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (isLocalFile) {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (!subtitleUrl.isNullOrBlank()) {
                val subUri = if (isLocalFile && !subtitleUrl.startsWith("http")) {
                    FileProvider.getUriForFile(context, authority, File(subtitleUrl))
                } else {
                    val proxiedSub = if (referer.isNotBlank() || origin.isNotBlank()) {
                        headerProxy.registerStream(subtitleUrl, referer, origin)
                    } else {
                        subtitleUrl
                    }
                    Uri.parse(proxiedSub)
                }
                putExtra("subs", arrayOf(subUri))
                putExtra("subs.enable", arrayOf(subUri))
            }
            if (!audioUrl.isNullOrBlank()) {
                val effAudio = if (referer.isNotBlank() || origin.isNotBlank()) {
                    headerProxy.registerStream(audioUrl, referer, origin)
                } else {
                    audioUrl
                }
                putExtra("audio", Uri.parse(effAudio))
            }
        }

        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
