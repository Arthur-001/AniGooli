package com.anigooli.player

import android.content.Context
import android.content.Intent
import com.anigooli.streaming.EpisodePlayerNavigator
import com.anigooli.streaming.EpisodeStreamBundle
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** In-memory payload holder passed cleanly to [PlayerActivity] without Binder transaction limits. */
@Singleton
class PlayerLaunchStore @Inject constructor() {
    /** Latest stream launch payload. */
    val pendingLaunch = MutableStateFlow<PlayerLaunchPayload?>(null)
}

/** Complete initial state required by [PlayerActivity] for online or local playback. */
data class PlayerLaunchPayload(
    /** AniList media ID (null only for non-AniList local playback). */
    val anilistId: Int?,
    /** Display anime title. */
    val animeTitle: String,
    /** Cover URL for watch history tracking. */
    val coverUrl: String?,
    /** Provider show slug for online episode switching. */
    val providerId: String?,
    /** Active episode identifier. */
    val episode: String,
    /** Ordered available episodes for Next/Prev navigation. */
    val allEpisodes: List<String>,
    /** True when playing a locally downloaded file offline. */
    val isLocalFile: Boolean,
    /** Local file path when [isLocalFile] is true. */
    val localVideoPath: String? = null,
    /** Local subtitle file path when [isLocalFile] is true. */
    val localSubtitlePath: String? = null,
    /** Resolved online streams when [isLocalFile] is false. */
    val streamBundle: EpisodeStreamBundle? = null,
)

/** Concrete [EpisodePlayerNavigator] bound into Hilt [SingletonComponent]. */
@Singleton
class PlayerNavigatorImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val launchStore: PlayerLaunchStore,
    private val externalLauncher: ExternalPlayerLauncher,
) : EpisodePlayerNavigator {

    override fun openOnlineStream(
        anilistId: Int,
        animeTitle: String,
        coverUrl: String?,
        providerId: String,
        episode: String,
        allEpisodes: List<String>,
        bundle: EpisodeStreamBundle,
    ) {
        launchStore.pendingLaunch.value = PlayerLaunchPayload(
            anilistId = anilistId,
            animeTitle = animeTitle,
            coverUrl = coverUrl,
            providerId = providerId,
            episode = episode,
            allEpisodes = allEpisodes,
            isLocalFile = false,
            streamBundle = bundle,
        )
        startPlayerActivity()
    }

    override fun openLocalFile(
        anilistId: Int?,
        animeTitle: String,
        episode: String,
        videoFilePath: String,
        subtitleFilePath: String?,
    ) {
        launchStore.pendingLaunch.value = PlayerLaunchPayload(
            anilistId = anilistId,
            animeTitle = animeTitle,
            coverUrl = null,
            providerId = null,
            episode = episode,
            allEpisodes = listOf(episode),
            isLocalFile = true,
            localVideoPath = videoFilePath,
            localSubtitlePath = subtitleFilePath,
        )
        startPlayerActivity()
    }

    override fun openInExternalMpv(
        title: String,
        urlOrPath: String,
        isLocalFile: Boolean,
        referer: String,
        origin: String,
        audioUrl: String?,
        subtitleUrl: String?,
    ): Boolean {
        return externalLauncher.launchMpv(
            title = title,
            urlOrPath = urlOrPath,
            isLocalFile = isLocalFile,
            referer = referer,
            origin = origin,
            audioUrl = audioUrl,
            subtitleUrl = subtitleUrl,
        )
    }

    private fun startPlayerActivity() {
        val intent = Intent(context, PlayerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

/** Hilt binding module connecting [EpisodePlayerNavigator] across feature modules. */
@Module
@InstallIn(SingletonComponent::class)
abstract class PlayerDiModule {
    /** Binds [PlayerNavigatorImpl] to [EpisodePlayerNavigator]. */
    @Binds
    abstract fun bindEpisodePlayerNavigator(impl: PlayerNavigatorImpl): EpisodePlayerNavigator
}
