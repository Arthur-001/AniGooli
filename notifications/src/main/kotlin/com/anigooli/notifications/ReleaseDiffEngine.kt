package com.anigooli.notifications

import com.anigooli.library.ReleaseTrackerEntity
import com.imashnake.animite.api.anilist.sanitize.media.Media
import kotlinx.serialization.json.Json

/** Snapshot of one related media item attached to a tracked anime. */
data class TrackedRelationItem(
    /** Related AniList media ID. */
    val id: Int,
    /** Media type: ANIME or MANGA. */
    val type: Media.Small.Type,
    /** Media format: TV, MOVIE, OVA, SPECIAL, ONA, MANGA, NOVEL, ONE_SHOT, etc. */
    val format: Media.Format?,
    /** Display title of the related entry. */
    val title: String,
)

/** Snapshot of current AniList state for one tracked anime. */
data class RemoteAnimeSnapshot(
    /** Tracked AniList media ID. */
    val anilistId: Int,
    /** Display title of the tracked anime. */
    val title: String,
    /** Highest episode number currently aired. */
    val airedEpisodeCount: Int,
    /** Direct related entries attached on AniList. */
    val relations: List<TrackedRelationItem>,
)

/** Release notification events emitted by [ReleaseDiffEngine]. */
sealed interface ReleaseEvent {
    /** AniList ID to open when the notification is tapped. */
    val targetMediaId: Int
    /** AniList media type ("ANIME" or "MANGA") for navigation. */
    val targetMediaType: String

    /** Emitted when a tracked anime airs a new episode. */
    data class NewEpisode(
        override val targetMediaId: Int,
        /** Parent anime display title. */
        val animeTitle: String,
        /** Newly aired episode number. */
        val episodeNumber: Int,
    ) : ReleaseEvent {
        override val targetMediaType: String = "ANIME"
    }

    /** Emitted when a new TV season or movie appears in the franchise relations. */
    data class NewSeasonOrMovie(
        override val targetMediaId: Int,
        /** Parent anime display title. */
        val parentTitle: String,
        /** Related season/movie title. */
        val relatedTitle: String,
        /** Format label (e.g. "TV", "Movie"). */
        val formatLabel: String,
    ) : ReleaseEvent {
        override val targetMediaType: String = "ANIME"
    }

    /** Emitted when a new OVA, Special, or ONA appears in the franchise relations. */
    data class NewOva(
        override val targetMediaId: Int,
        /** Parent anime display title. */
        val parentTitle: String,
        /** Related OVA/Special title. */
        val relatedTitle: String,
        /** Format label (e.g. "OVA", "Special"). */
        val formatLabel: String,
    ) : ReleaseEvent {
        override val targetMediaType: String = "ANIME"
    }

    /** Emitted when a new Manga, Light Novel, or One-Shot appears in the franchise relations. */
    data class NewManga(
        override val targetMediaId: Int,
        /** Parent anime display title. */
        val parentTitle: String,
        /** Related manga title. */
        val relatedTitle: String,
        /** Format label (e.g. "Manga", "Novel"). */
        val formatLabel: String,
    ) : ReleaseEvent {
        override val targetMediaType: String = "MANGA"
    }
}

/** Result of diffing [RemoteAnimeSnapshot] against a stored [ReleaseTrackerEntity]. */
data class ReleaseDiffResult(
    /** Updated tracker entity to persist into Room. */
    val updatedTracker: ReleaseTrackerEntity,
    /** Newly detected release events (empty on initial baseline seed). */
    val events: List<ReleaseEvent>,
)

/**
 * Pure diff engine detecting newly aired episodes and newly published Seasons, Movies,
 * OVAs, and Mangas while preventing duplicate or initial-seed notification spam.
 */
object ReleaseDiffEngine {

    private val ovaFormats = setOf(Media.Format.OVA, Media.Format.SPECIAL, Media.Format.ONA)
    private val mangaFormats = setOf(Media.Format.MANGA, Media.Format.ONE_SHOT, Media.Format.NOVEL)
    private val seasonMovieFormats = setOf(Media.Format.TV, Media.Format.TV_SHORT, Media.Format.MOVIE)

    /** Computes newly published [ReleaseEvent]s and the next [ReleaseTrackerEntity] snapshot. */
    fun diff(
        previous: ReleaseTrackerEntity?,
        remote: RemoteAnimeSnapshot,
        nowMs: Long = System.currentTimeMillis(),
    ): ReleaseDiffResult {
        val currentRelIds = remote.relations.map { it.id }.distinct().sorted()
        val serializedIds = Json.encodeToString(currentRelIds)
        val nextTracker = ReleaseTrackerEntity(
            anilistId = remote.anilistId,
            title = remote.title,
            lastKnownAiredEpisode = maxOf(previous?.lastKnownAiredEpisode ?: 0, remote.airedEpisodeCount),
            knownRelationIdsJson = serializedIds,
            lastCheckedAt = nowMs,
        )

        // Initial seed: record baseline silently without spamming about already-aired items
        if (previous == null) {
            return ReleaseDiffResult(updatedTracker = nextTracker, events = emptyList())
        }

        val events = ArrayList<ReleaseEvent>()
        if (remote.airedEpisodeCount > previous.lastKnownAiredEpisode) {
            events.add(
                ReleaseEvent.NewEpisode(
                    targetMediaId = remote.anilistId,
                    animeTitle = remote.title,
                    episodeNumber = remote.airedEpisodeCount,
                )
            )
        }

        val knownIds = runCatching {
            Json.decodeFromString<List<Int>>(previous.knownRelationIdsJson).toSet()
        }.getOrDefault(emptySet())

        for (rel in remote.relations) {
            if (rel.id in knownIds) continue
            val fmtLabel = rel.format?.name?.replace('_', ' ') ?: rel.type.type
            when {
                rel.type == Media.Small.Type.MANGA || rel.format in mangaFormats -> {
                    events.add(
                        ReleaseEvent.NewManga(
                            targetMediaId = rel.id,
                            parentTitle = remote.title,
                            relatedTitle = rel.title,
                            formatLabel = fmtLabel,
                        )
                    )
                }
                rel.format in ovaFormats -> {
                    events.add(
                        ReleaseEvent.NewOva(
                            targetMediaId = rel.id,
                            parentTitle = remote.title,
                            relatedTitle = rel.title,
                            formatLabel = fmtLabel,
                        )
                    )
                }
                rel.format in seasonMovieFormats || rel.type == Media.Small.Type.ANIME -> {
                    events.add(
                        ReleaseEvent.NewSeasonOrMovie(
                            targetMediaId = rel.id,
                            parentTitle = remote.title,
                            relatedTitle = rel.title,
                            formatLabel = fmtLabel,
                        )
                    )
                }
            }
        }

        val mergedIds = (knownIds + currentRelIds).distinct().sorted()
        return ReleaseDiffResult(
            updatedTracker = nextTracker.copy(knownRelationIdsJson = Json.encodeToString(mergedIds)),
            events = events,
        )
    }
}
