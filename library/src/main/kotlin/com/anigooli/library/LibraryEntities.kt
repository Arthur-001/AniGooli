package com.anigooli.library

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Junction
import androidx.room.PrimaryKey
import androidx.room.Relation

/** Watch status choices for offline personal anime list entries. */
enum class OfflineWatchStatus(val displayLabel: String) {
    WATCHING("Watching"),
    COMPLETED("Completed"),
    PLAN_TO_WATCH("Plan to Watch"),
    ON_HOLD("On Hold"),
    DROPPED("Dropped");

    companion object {
        /** Parses a raw or MAL/AniList status string safely into [OfflineWatchStatus]. */
        fun safeParse(raw: String?): OfflineWatchStatus {
            val normalized = raw?.trim()?.uppercase()?.replace(' ', '_') ?: return PLAN_TO_WATCH
            return when (normalized) {
                "CURRENT", "WATCHING" -> WATCHING
                "COMPLETED" -> COMPLETED
                "PLANNING", "PLAN_TO_WATCH", "PLAN_TO_READ" -> PLAN_TO_WATCH
                "PAUSED", "ON_HOLD", "ON-HOLD" -> ON_HOLD
                "DROPPED" -> DROPPED
                else -> entries.firstOrNull { it.name == normalized } ?: PLAN_TO_WATCH
            }
        }
    }
}

/** Standard AniList genres seeded into the offline library database. */
object DefaultGenres {
    /** Default genre names available offline out of the box. */
    val names: List<String> = listOf(
        "Action",
        "Adventure",
        "Comedy",
        "Drama",
        "Ecchi",
        "Fantasy",
        "Hentai",
        "Horror",
        "Mahou Shoujo",
        "Mecha",
        "Music",
        "Mystery",
        "Psychological",
        "Romance",
        "Sci-Fi",
        "Slice of Life",
        "Sports",
        "Supernatural",
        "Thriller",
    )

    /** Deterministic ID for a seeded default genre. */
    fun defaultId(name: String): String =
        "default_${name.lowercase().replace(Regex("""[^a-z0-9]+"""), "_")}"

    /** Color presets offered when creating or editing custom genres. */
    val customColorPresets: List<String> = listOf(
        "#EF5350",
        "#EC407A",
        "#AB47BC",
        "#5C6BC0",
        "#29B6F6",
        "#26A69A",
        "#66BB6A",
        "#FFA726",
        "#8D6E63",
    )
}

/** Offline personal anime list entry supporting both AniList-linked and manual custom entries. */
@Entity(
    tableName = "offline_anime",
    indices = [Index(value = ["anilistId"])],
)
data class OfflineAnimeEntity(
    /** Unique local UUID primary key. */
    @PrimaryKey val localId: String,
    /** Linked AniList ID when added from AniList, or null for completely custom entries. */
    val anilistId: Int? = null,
    /** English title if available (or user-entered title for custom entries). */
    val titleEnglish: String? = null,
    /** Romaji (Japanese-English) title if available. */
    val titleRomaji: String? = null,
    /** Native Japanese title if available. */
    val titleNative: String? = null,
    /** Remote cover image URL. */
    val coverUrl: String? = null,
    /** Optional local image path for custom entries. */
    val localCoverPath: String? = null,
    /** Watch status name ([OfflineWatchStatus.name]). */
    val status: String = OfflineWatchStatus.PLAN_TO_WATCH.name,
    /** Episodes watched so far. */
    val episodesWatched: Int = 0,
    /** Total episodes if known. */
    val totalEpisodes: Int? = null,
    /** Optional user rating (0..10 scale). */
    val score: Float? = null,
    /** Personal notes. */
    val notes: String = "",
    /** Creation timestamp in epoch millis. */
    val createdAt: Long,
    /** Last modified timestamp in epoch millis. */
    val updatedAt: Long,
)

/** Default or user-created custom genre entity. */
@Entity(
    tableName = "genres",
    indices = [Index(value = ["name"], unique = true)],
)
data class GenreEntity(
    /** Unique genre ID. */
    @PrimaryKey val id: String,
    /** Display name of the genre. */
    val name: String,
    /** True for user-created genres; false for built-in defaults. */
    val isCustom: Boolean,
    /** Hex accent color string (e.g. "#29B6F6"). */
    val colorHex: String = "#5C6BC0",
)

/** Many-to-many join table linking offline anime entries and genres. */
@Entity(
    tableName = "anime_genre_cross_ref",
    primaryKeys = ["localId", "genreId"],
    foreignKeys = [
        ForeignKey(
            entity = OfflineAnimeEntity::class,
            parentColumns = ["localId"],
            childColumns = ["localId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = GenreEntity::class,
            parentColumns = ["id"],
            childColumns = ["genreId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["genreId"])],
)
data class AnimeGenreCrossRef(
    /** Referenced [OfflineAnimeEntity.localId]. */
    val localId: String,
    /** Referenced [GenreEntity.id]. */
    val genreId: String,
)

/** Combined projection of an offline anime and all its assigned genres. */
data class AnimeWithGenres(
    /** Core anime entry. */
    @Embedded val anime: OfflineAnimeEntity,
    /** Default and custom genres assigned to this entry. */
    @Relation(
        parentColumn = "localId",
        entityColumn = "id",
        associateBy = Junction(
            value = AnimeGenreCrossRef::class,
            parentColumn = "localId",
            entityColumn = "genreId",
        ),
    )
    val genres: List<GenreEntity>,
)

/** Per-anime remembered audio track, subtitle preference, and resume position. */
@Entity(tableName = "anime_playback_prefs")
data class AnimePlaybackPrefsEntity(
    /** AniList ID of the anime. */
    @PrimaryKey val anilistId: Int,
    /** Audio track name: "JAPANESE" or "ENGLISH_DUB". */
    val audioTrack: String,
    /** Whether English subtitles are enabled for this anime. */
    val subtitlesEnabled: Boolean,
    /** Optional per-anime quality override (e.g. "720p"), or null to use global default. */
    val preferredQualityOverride: String? = null,
    /** Last played episode string. */
    val lastEpisode: String? = null,
    /** Saved playback resume position in milliseconds. */
    val lastPositionMs: Long = 0L,
    /** Remembered censorship version preference ("UNCENSORED" or "CENSORED"), or null for global default. */
    val censorshipVersion: String? = null,
    /** Last update timestamp in epoch millis. */
    val updatedAt: Long,
)

/** Record of anime streamed in AniGooli (used alongside offline list for release notifications). */
@Entity(tableName = "watch_history")
data class WatchHistoryEntity(
    /** AniList ID of the streamed anime (negative synthetic ID for provider-only entries). */
    @PrimaryKey val anilistId: Int,
    /** Display title of the anime. */
    val title: String,
    /** Optional cover image URL. */
    val coverUrl: String? = null,
    /** Last episode number watched. */
    val lastEpisodeWatched: String,
    /** Timestamp when last watched in epoch millis. */
    val lastWatchedAt: Long,
    /** Optional streaming provider ID for provider-only entries. */
    val providerId: String? = null,
    /** Optional MyAnimeList ID for fallback metadata resolution. */
    val malId: Int? = null,
)

/** Snapshot of known episodes & related media IDs for release notifications diffing. */
@Entity(tableName = "release_tracker")
data class ReleaseTrackerEntity(
    /** AniList ID of the tracked anime. */
    @PrimaryKey val anilistId: Int,
    /** Latest known anime display title. */
    val title: String,
    /** Highest aired episode number already seen/notified. */
    val lastKnownAiredEpisode: Int,
    /** JSON array of known related AniList IDs (seasons, movies, OVAs, mangas). */
    val knownRelationIdsJson: String,
    /** Epoch millis when this entry was last checked. */
    val lastCheckedAt: Long,
)
