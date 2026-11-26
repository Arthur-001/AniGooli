package com.anigooli.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anigooli.streaming.EpisodePlayerNavigator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Grouped UI representation of one anime and its downloaded/active episodes. */
data class AnimeDownloadsGroup(
    /** Parent anime metadata. */
    val anime: DownloadedAnimeEntity,
    /** Episodes sorted numerically/chronologically. */
    val episodes: List<DownloadEpisodeEntity>,
    /** Combined byte size of completed episodes in this group. */
    val totalBytes: Long,
)

/** ViewModel backing [DownloadsManagerScreen]. */
@HiltViewModel
class DownloadsManagerViewModel @Inject constructor(
    private val repository: DownloadRepository,
    private val playerNavigator: EpisodePlayerNavigator,
) : ViewModel() {

    /** Observable list of anime download groups sorted by most recently updated. */
    val groups: StateFlow<List<AnimeDownloadsGroup>> = combine(
        repository.observeDownloadedAnime(),
        repository.observeAllEpisodes(),
    ) { animeList, episodes ->
        val byAnime = episodes.groupBy { it.anilistId }
        animeList.mapNotNull { anime ->
            val eps = byAnime[anime.anilistId].orEmpty()
                .sortedBy { it.episodeNumber.toDoubleOrNull() ?: Double.MAX_VALUE }
            if (eps.isEmpty()) {
                null
            } else {
                AnimeDownloadsGroup(
                    anime = anime,
                    episodes = eps,
                    totalBytes = eps.sumOf { it.bytesDownloaded },
                )
            }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = emptyList(),
    )

    /** Pauses an active download task. */
    fun pause(episodeId: String) = viewModelScope.launch { repository.pauseDownload(episodeId) }

    /** Resumes or retries a paused or failed download task. */
    fun resume(episodeId: String) = viewModelScope.launch { repository.resumeDownload(episodeId) }

    /** Deletes a single downloaded/queued episode from storage. */
    fun deleteEpisode(episodeId: String) = viewModelScope.launch { repository.deleteEpisode(episodeId) }

    /** Deletes all downloaded episodes of an anime from storage. */
    fun deleteAnime(anilistId: Int) = viewModelScope.launch { repository.deleteAnime(anilistId) }

    /** Plays a completed offline episode inside AniGooli's in-app player. */
    fun playInApp(episode: DownloadEpisodeEntity) {
        val path = episode.videoFilePath ?: return
        playerNavigator.openLocalFile(
            anilistId = episode.anilistId,
            animeTitle = episode.animeTitle,
            episode = episode.episodeNumber,
            videoFilePath = path,
            subtitleFilePath = episode.subtitleFilePath,
        )
    }

    /** Plays a completed offline episode in external MPV (`is.xyz.mpv`). Returns false if MPV is unavailable. */
    fun playInMpv(episode: DownloadEpisodeEntity): Boolean {
        val path = episode.videoFilePath ?: return false
        return playerNavigator.openInExternalMpv(
            title = "${episode.animeTitle} - Ep ${episode.episodeNumber}",
            urlOrPath = path,
            isLocalFile = true,
            subtitleUrl = episode.subtitleFilePath,
        )
    }
}
