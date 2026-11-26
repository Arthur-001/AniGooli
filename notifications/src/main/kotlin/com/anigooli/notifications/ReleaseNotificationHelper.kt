package com.anigooli.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val CHANNEL_EPISODES = "anigooli_episodes"
private const val CHANNEL_SEASONS = "anigooli_seasons"
private const val CHANNEL_OVAS = "anigooli_ovas"
private const val CHANNEL_MANGAS = "anigooli_mangas"

/** Creates notification channels and dispatches release notifications with deep-links into AniGooli. */
@Singleton
class ReleaseNotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Creates the 4 release notification channels safely on Android 8+. */
    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channels = listOf(
            NotificationChannel(
                CHANNEL_EPISODES,
                context.getString(R.string.notif_channel_episodes),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
            NotificationChannel(
                CHANNEL_SEASONS,
                context.getString(R.string.notif_channel_seasons),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
            NotificationChannel(
                CHANNEL_OVAS,
                context.getString(R.string.notif_channel_ovas),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
            NotificationChannel(
                CHANNEL_MANGAS,
                context.getString(R.string.notif_channel_mangas),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        channels.forEach { ch ->
            if (manager.getNotificationChannel(ch.id) == null) {
                manager.createNotificationChannel(ch)
            }
        }
    }

    /** Posts a notification for [event] if permission is granted. */
    fun post(event: ReleaseEvent) {
        if (!hasPermission()) return
        ensureChannels()

        val (channelId, title, body) = when (event) {
            is ReleaseEvent.NewEpisode -> Triple(
                CHANNEL_EPISODES,
                "New Episode: ${event.animeTitle}",
                "Episode ${event.episodeNumber} has been published!",
            )
            is ReleaseEvent.NewSeasonOrMovie -> Triple(
                CHANNEL_SEASONS,
                "New ${event.formatLabel}: ${event.parentTitle}",
                "${event.relatedTitle} is now listed!",
            )
            is ReleaseEvent.NewOva -> Triple(
                CHANNEL_OVAS,
                "New ${event.formatLabel}: ${event.parentTitle}",
                "${event.relatedTitle} has been published!",
            )
            is ReleaseEvent.NewManga -> Triple(
                CHANNEL_MANGAS,
                "New ${event.formatLabel}: ${event.parentTitle}",
                "${event.relatedTitle} is now available!",
            )
        }

        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_MEDIA_ID, event.targetMediaId)
            putExtra(EXTRA_MEDIA_TYPE, event.targetMediaType)
        } ?: return

        val notifId = "${channelId}_${event.targetMediaId}_${body.hashCode()}".hashCode() and Int.MAX_VALUE
        val pendingIntent = PendingIntent.getActivity(
            context,
            notifId,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(notifId, notification)
        }
    }

    /** Returns true if notification posting is allowed on this Android version. */
    fun hasPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    companion object {
        /** Intent extra holding target AniList media ID when tapping a release notification. */
        const val EXTRA_MEDIA_ID = "anigooli_notif_media_id"
        /** Intent extra holding target media type ("ANIME" or "MANGA"). */
        const val EXTRA_MEDIA_TYPE = "anigooli_notif_media_type"
    }
}
