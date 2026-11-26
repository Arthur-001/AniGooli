package com.anigooli.notifications

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Optional
import com.apollographql.cache.normalized.FetchPolicy
import com.apollographql.cache.normalized.fetchPolicy
import com.anigooli.library.LibraryDao
import com.imashnake.animite.api.anilist.ReleaseCheckBatchQuery
import com.imashnake.animite.api.anilist.sanitize.media.Media
import com.imashnake.animite.api.anilist.sanitize.media.Media.Format.Companion.sanitize
import com.imashnake.animite.api.anilist.type.MediaStatus
import com.imashnake.animite.api.preferences.PreferencesRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ReleaseCheckWorker"
private const val BATCH_SIZE = 50
private const val CHECK_INTERVAL_HOURS = 6L
private const val UNIQUE_WORK_NAME = "anigooli_release_check"

/**
 * Periodic WorkManager worker monitoring anime in the user's Offline List and Watch History
 * for newly aired episodes and newly published Seasons, Movies, OVAs, and Mangas.
 */
@HiltWorker
class ReleaseCheckWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val libraryDao: LibraryDao,
    private val apolloClient: ApolloClient,
    private val preferencesRepository: PreferencesRepository,
    private val notificationHelper: ReleaseNotificationHelper,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val enabled = preferencesRepository.notificationsEnabled.firstOrNull() ?: true
        if (!enabled || !notificationHelper.hasPermission()) {
            return@withContext Result.success()
        }

        val notifyEpisodes = preferencesRepository.notifyEpisodes.firstOrNull() ?: true
        val notifySeasons = preferencesRepository.notifySeasonsMovies.firstOrNull() ?: true
        val notifyOvas = preferencesRepository.notifyOvas.firstOrNull() ?: true
        val notifyMangas = preferencesRepository.notifyMangas.firstOrNull() ?: true

        val language = Media.Language.safeValueOf(preferencesRepository.language.firstOrNull())
        val trackedIds = libraryDao.getAllTrackedAnilistIds().distinct()
        if (trackedIds.isEmpty()) return@withContext Result.success()

        trackedIds.chunked(BATCH_SIZE).forEach { chunk ->
            runCatching {
                val resp = apolloClient.query(
                    ReleaseCheckBatchQuery(ids = Optional.present(chunk))
                )
                    .fetchPolicy(FetchPolicy.NetworkOnly)
                    .execute()

                resp.data?.page?.media.orEmpty().filterNotNull().forEach { media ->
                    val title = Media.resolveTitle(
                        english = media.title?.english,
                        nativeTitle = media.title?.native,
                        romaji = media.title?.romaji,
                        userPreferred = media.title?.userPreferred,
                        language = language,
                    ) ?: "Anime"

                    val aired = when {
                        media.status == MediaStatus.NOT_YET_RELEASED -> 0
                        media.nextAiringEpisode != null -> (media.nextAiringEpisode!!.episode - 1).coerceAtLeast(0)
                        else -> media.episodes ?: 0
                    }

                    val relations = media.relations?.edges.orEmpty().mapNotNull { edge ->
                        val node = edge?.node ?: return@mapNotNull null
                        val relTitle = Media.resolveTitle(
                            english = node.title?.english,
                            nativeTitle = node.title?.native,
                            romaji = node.title?.romaji,
                            userPreferred = node.title?.userPreferred,
                            language = language,
                        ) ?: return@mapNotNull null
                        val type = Media.Small.Type.entries.firstOrNull { it.name == node.type?.name }
                            ?: Media.Small.Type.UNKNOWN
                        TrackedRelationItem(
                            id = node.id,
                            type = type,
                            format = node.format?.sanitize(),
                            title = relTitle,
                        )
                    }

                    val previous = libraryDao.getReleaseTracker(media.id)
                    val diff = ReleaseDiffEngine.diff(
                        previous = previous,
                        remote = RemoteAnimeSnapshot(
                            anilistId = media.id,
                            title = title,
                            airedEpisodeCount = aired,
                            relations = relations,
                        ),
                    )
                    libraryDao.upsertReleaseTracker(diff.updatedTracker)

                    diff.events.forEach { event ->
                        val shouldNotify = when (event) {
                            is ReleaseEvent.NewEpisode -> notifyEpisodes
                            is ReleaseEvent.NewSeasonOrMovie -> notifySeasons
                            is ReleaseEvent.NewOva -> notifyOvas
                            is ReleaseEvent.NewManga -> notifyMangas
                        }
                        if (shouldNotify) {
                            notificationHelper.post(event)
                        }
                    }
                }
            }.onFailure { err ->
                Log.w(TAG, "Batch release check skipped: ${err.message}")
            }
        }
        Result.success()
    }
}

/** Schedules or cancels periodic background release checking according to user Settings. */
@Singleton
class ReleaseScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesRepository: PreferencesRepository,
) {
    /** Syncs periodic WorkManager schedule with current [PreferencesRepository.notificationsEnabled]. */
    suspend fun syncSchedule() {
        val enabled = preferencesRepository.notificationsEnabled.firstOrNull() ?: true
        val wm = WorkManager.getInstance(context)
        if (enabled) {
            val request = PeriodicWorkRequestBuilder<ReleaseCheckWorker>(
                CHECK_INTERVAL_HOURS,
                TimeUnit.HOURS,
            ).setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            ).build()
            wm.enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        } else {
            wm.cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }
}
