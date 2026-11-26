package com.anigooli.library

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.UUID
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

private const val CURRENT_SCHEMA_VERSION = 1
private const val KEY_PAYLOAD = "payload"
private const val KEY_LISTS = "lists"
private const val KEY_ENTRIES = "entries"

/** Serializable representation of a custom genre inside a backup bundle. */
@Serializable
data class BackupGenre(
    /** Original genre ID. */
    val id: String,
    /** Genre name. */
    val name: String,
    /** True if user-created custom genre. */
    val isCustom: Boolean = true,
    /** Hex accent color. */
    val colorHex: String = "#5C6BC0",
)

/** Serializable representation of an anime entry inside a backup bundle. */
@Serializable
data class BackupAnimeEntry(
    /** Local UUID if from AniGooli backup. */
    val localId: String = "",
    /** Linked AniList ID if known. */
    val anilistId: Int? = null,
    /** English title. */
    val titleEnglish: String? = null,
    /** Romaji title. */
    val titleRomaji: String? = null,
    /** Native Japanese title. */
    val titleNative: String? = null,
    /** Cover image URL. */
    val coverUrl: String? = null,
    /** Watch status string. */
    val status: String = OfflineWatchStatus.PLAN_TO_WATCH.name,
    /** Episodes watched. */
    val episodesWatched: Int = 0,
    /** Total episodes if known. */
    val totalEpisodes: Int? = null,
    /** Score (0..10). */
    val score: Float? = null,
    /** User notes. */
    val notes: String = "",
    /** Assigned genre names (default or custom). */
    val genreNames: List<String> = emptyList(),
    /** Last updated epoch millis. */
    val updatedAt: Long = 0L,
)

/** Serializable snapshot of user preferences stored inside an AniGooli backup. */
@Serializable
data class BackupPreferencesSnapshot(
    /** Persisted title language preference. */
    val titleLanguage: String? = null,
    /** Persisted offline anime list column count (2, 3, or 4). */
    val personalListColumns: Int? = null,
    /** Persisted manga relations sort order. */
    val mangaRelationsSortOrder: String? = null,
    /** Persisted default streaming quality. */
    val defaultStreamQuality: String? = null,
    /** Persisted default audio track. */
    val defaultAudioTrack: String? = null,
    /** Persisted default English subtitles flag. */
    val defaultSubtitlesEnabled: Boolean? = null,
    /** Persisted preferred player. */
    val preferredPlayer: String? = null,
    /** Persisted pre-buffer size cap in MB. */
    val preBufferMaxMb: Int? = null,
    /** Persisted notifications toggle. */
    val notificationsEnabled: Boolean? = null,
)

/** Versioned payload protected by SHA-256 checksum inside [AniGooliBackupBundle]. */
@Serializable
data class BackupPayload(
    /** Offline anime entries included in backup. */
    val animes: List<BackupAnimeEntry> = emptyList(),
    /** Custom genres included in backup. */
    val customGenres: List<BackupGenre> = emptyList(),
    /** User preferences snapshot included in backup. */
    val preferences: BackupPreferencesSnapshot? = null,
)

/** Top-level versioned backup envelope exported and restored by AniGooli. */
@Serializable
data class AniGooliBackupBundle(
    /** Backup format schema version. */
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    /** Export timestamp in epoch millis. */
    val exportedAt: Long,
    /** Hex SHA-256 checksum over canonical JSON serialization of [payload]. */
    val sha256: String,
    /** Exported user data and preferences payload. */
    val payload: BackupPayload,
)

/** Parsed result of a fault-tolerant import operation before conflict matching. */
data class ParsedImportResult(
    /** Successfully parsed anime entries. */
    val validAnimes: List<BackupAnimeEntry>,
    /** Successfully parsed custom genres. */
    val validCustomGenres: List<BackupGenre>,
    /** Optional preferences snapshot if present in AniGooli backup. */
    val preferences: BackupPreferencesSnapshot?,
    /** True when the bundle carried a SHA-256 checksum that did not match the payload. */
    val isChecksumMismatch: Boolean,
    /** Descriptions of individual malformed items isolated/quarantined without aborting valid records. */
    val quarantinedItems: List<String>,
)

/** Conflicting anime entry where local and incoming versions differ or collide. */
data class AnimeConflict(
    /** Unique key identifying this conflict row in the UI. */
    val conflictId: String,
    /** Existing local anime + genres on device. */
    val existing: AnimeWithGenres,
    /** Incoming anime entry from backup/import. */
    val incoming: BackupAnimeEntry,
)

/** Conflicting custom genre where normalized name collides with a local genre. */
data class GenreConflict(
    /** Unique key identifying this genre conflict row. */
    val conflictId: String,
    /** Existing local genre. */
    val existing: GenreEntity,
    /** Incoming custom genre. */
    val incoming: BackupGenre,
)

/** Complete staged restore plan produced prior to user conflict resolution. */
data class RestorePlan(
    /** Non-conflicting anime entries ready to insert directly. */
    val newAnimes: List<BackupAnimeEntry>,
    /** Conflicting anime entries requiring user Keep/Skip selection. */
    val animeConflicts: List<AnimeConflict>,
    /** Non-conflicting custom genres ready to insert directly. */
    val newGenres: List<BackupGenre>,
    /** Conflicting custom genres requiring user Keep/Skip selection. */
    val genreConflicts: List<GenreConflict>,
    /** Optional preferences snapshot from AniGooli backup. */
    val preferences: BackupPreferencesSnapshot?,
    /** Whether SHA-256 checksum mismatched (warn user while still allowing restore). */
    val isChecksumMismatch: Boolean,
    /** Individual malformed items skipped during fault-tolerant parsing. */
    val quarantinedItems: List<String>,
) {
    /** True when any anime or genre conflict requires user choice. */
    val hasConflicts: Boolean
        get() = animeConflicts.isNotEmpty() || genreConflicts.isNotEmpty()
}

/** Final counts reported after executing an import/restore transaction. */
data class RestoreSummary(
    /** Newly added anime entries. */
    val importedCount: Int,
    /** Existing anime entries overwritten with incoming data. */
    val updatedCount: Int,
    /** Conflicting entries skipped (kept local version). */
    val skippedCount: Int,
    /** Custom genres added or updated. */
    val genresImportedCount: Int,
    /** Malformed records isolated during fault-tolerant parsing. */
    val quarantinedItems: List<String>,
    /** Whether checksum mismatch warning was present. */
    val isChecksumMismatch: Boolean,
)

/**
 * Pure, fault-tolerant serializer, importer, checksum verifier, and conflict detector
 * supporting AniGooli JSON backups, MAL XML exports, and AniList JSON exports.
 */
@Suppress("TooManyFunctions", "NestedBlockDepth")
object BackupEngine {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }
    private val canonicalJson = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = true
    }

    /** Creates a checksummed JSON string exporting [animes], [genres] (custom), and [preferences]. */
    fun exportToJson(
        animes: List<AnimeWithGenres>,
        genres: List<GenreEntity>,
        preferences: BackupPreferencesSnapshot?,
        exportedAt: Long = System.currentTimeMillis(),
    ): String {
        val backupAnimes = animes.map { item ->
            BackupAnimeEntry(
                localId = item.anime.localId,
                anilistId = item.anime.anilistId,
                titleEnglish = item.anime.titleEnglish,
                titleRomaji = item.anime.titleRomaji,
                titleNative = item.anime.titleNative,
                coverUrl = item.anime.coverUrl,
                status = item.anime.status,
                episodesWatched = item.anime.episodesWatched,
                totalEpisodes = item.anime.totalEpisodes,
                score = item.anime.score,
                notes = item.anime.notes,
                genreNames = item.genres.map { it.name },
                updatedAt = item.anime.updatedAt,
            )
        }
        val customGenres = genres.filter { it.isCustom }.map { g ->
            BackupGenre(id = g.id, name = g.name, isCustom = true, colorHex = g.colorHex)
        }
        val payload = BackupPayload(
            animes = backupAnimes,
            customGenres = customGenres,
            preferences = preferences,
        )
        val digest = computePayloadSha256(payload)
        val bundle = AniGooliBackupBundle(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            exportedAt = exportedAt,
            sha256 = digest,
            payload = payload,
        )
        return json.encodeToString(bundle)
    }

    /**
     * Parses AniGooli JSON backup, AniList JSON export, or MAL XML export fault-tolerantly.
     * Malformed individual entries are isolated into [ParsedImportResult.quarantinedItems].
     */
    fun parseImport(rawText: String): ParsedImportResult {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) {
            return ParsedImportResult(
                validAnimes = emptyList(),
                validCustomGenres = emptyList(),
                preferences = null,
                isChecksumMismatch = false,
                quarantinedItems = listOf("Empty import file"),
            )
        }
        if (trimmed.startsWith("<")) {
            return parseMalXml(trimmed)
        }
        return parseJsonFaultTolerantly(trimmed)
    }

    /** Matches parsed incoming entries against existing local DB items to separate new vs conflicting items. */
    fun prepareRestorePlan(
        parsed: ParsedImportResult,
        existingAnimes: List<AnimeWithGenres>,
        existingGenres: List<GenreEntity>,
    ): RestorePlan {
        val existingByAnilistId = existingAnimes
            .mapNotNull { awg -> awg.anime.anilistId?.let { id -> id to awg } }
            .toMap()
        val existingByNormTitle = existingAnimes
            .flatMap { awg ->
                listOfNotNull(
                    awg.anime.titleEnglish,
                    awg.anime.titleRomaji,
                    awg.anime.titleNative,
                ).map { normalizeTitle(it) to awg }
            }.toMap()

        val newAnimes = ArrayList<BackupAnimeEntry>()
        val animeConflicts = ArrayList<AnimeConflict>()

        parsed.validAnimes.forEachIndexed { idx, incoming ->
            val matchedById = incoming.anilistId?.let { existingByAnilistId[it] }
            val matchedByTitle = listOfNotNull(
                incoming.titleEnglish,
                incoming.titleRomaji,
                incoming.titleNative,
            ).firstNotNullOfOrNull { t -> existingByNormTitle[normalizeTitle(t)] }

            val matched = matchedById ?: matchedByTitle
            if (matched != null) {
                animeConflicts.add(
                    AnimeConflict(
                        conflictId = "anime_${idx}_${matched.anime.localId}",
                        existing = matched,
                        incoming = incoming,
                    )
                )
            } else {
                newAnimes.add(incoming)
            }
        }

        val existingGenresByNorm = existingGenres.associateBy { normalizeTitle(it.name) }
        val newGenres = ArrayList<BackupGenre>()
        val genreConflicts = ArrayList<GenreConflict>()

        parsed.validCustomGenres.forEachIndexed { idx, incoming ->
            val existing = existingGenresByNorm[normalizeTitle(incoming.name)]
            if (existing != null) {
                if (existing.isCustom &&
                    (!existing.name.equals(incoming.name, false) || !existing.colorHex.equals(incoming.colorHex, true))
                ) {
                    genreConflicts.add(
                        GenreConflict(
                            conflictId = "genre_${idx}_${existing.id}",
                            existing = existing,
                            incoming = incoming,
                        )
                    )
                }
            } else {
                newGenres.add(incoming)
            }
        }

        return RestorePlan(
            newAnimes = newAnimes,
            animeConflicts = animeConflicts,
            newGenres = newGenres,
            genreConflicts = genreConflicts,
            preferences = parsed.preferences,
            isChecksumMismatch = parsed.isChecksumMismatch,
            quarantinedItems = parsed.quarantinedItems,
        )
    }

    /** Computes deterministic SHA-256 hex digest over [payload]. */
    fun computePayloadSha256(payload: BackupPayload): String {
        val raw = canonicalJson.encodeToString(payload)
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun parseJsonFaultTolerantly(trimmed: String): ParsedImportResult {
        val quarantined = ArrayList<String>()
        val rootElem = runCatching { json.parseToJsonElement(trimmed) }.getOrElse { err ->
            return ParsedImportResult(
                validAnimes = emptyList(),
                validCustomGenres = emptyList(),
                preferences = null,
                isChecksumMismatch = false,
                quarantinedItems = listOf("Invalid JSON root: ${err.message}"),
            )
        }

        if (rootElem is JsonArray) {
            val animes = parseAnimeJsonArray(rootElem, quarantined)
            return ParsedImportResult(animes, emptyList(), null, false, quarantined)
        }

        val obj = rootElem.jsonObject
        if (obj.containsKey(KEY_PAYLOAD) || obj.containsKey("schemaVersion")) {
            val expectedSha = obj["sha256"]?.jsonPrimitive?.contentOrNullCompat()
            val payloadObj = obj[KEY_PAYLOAD]?.jsonObject ?: obj
            val animes = parseAnimeJsonArray(payloadObj["animes"] as? JsonArray, quarantined)
            val genres = parseGenreJsonArray(payloadObj["customGenres"] as? JsonArray, quarantined)
            val prefs = payloadObj["preferences"]?.let { elem ->
                runCatching { json.decodeFromJsonElement(BackupPreferencesSnapshot.serializer(), elem) }
                    .onFailure { quarantined.add("Preferences snapshot ignored: ${it.message}") }
                    .getOrNull()
            }

            val mismatch = if (!expectedSha.isNullOrBlank()) {
                val parsedPayload = runCatching {
                    json.decodeFromJsonElement(BackupPayload.serializer(), obj[KEY_PAYLOAD] ?: JsonObject(emptyMap()))
                }.getOrNull()
                parsedPayload == null || !computePayloadSha256(parsedPayload).equals(expectedSha, ignoreCase = true)
            } else {
                false
            }
            return ParsedImportResult(animes, genres, prefs, mismatch, quarantined)
        }

        // Fallback: AniList JSON export formats ({"lists": [...]} or {"entries": [...]} or {"animes": [...]})
        val candidateArrays = collectAniListArrays(obj)
        val animes = candidateArrays.flatMap { parseAnimeJsonArray(it, quarantined) }
        return ParsedImportResult(animes, emptyList(), null, false, quarantined)
    }

    private fun collectAniListArrays(obj: JsonObject): List<JsonArray> {
        val result = ArrayList<JsonArray>()
        (obj["animes"] as? JsonArray)?.let { result.add(it) }
        (obj[KEY_ENTRIES] as? JsonArray)?.let { result.add(it) }
        (obj[KEY_LISTS] as? JsonArray)?.forEach { listElem ->
            runCatching {
                listElem.jsonObject[KEY_ENTRIES]?.jsonArray?.let { result.add(it) }
            }
        }
        runCatching {
            obj["data"]?.jsonObject?.get("MediaListCollection")?.jsonObject?.get(KEY_LISTS)?.jsonArray?.forEach { listElem ->
                listElem.jsonObject[KEY_ENTRIES]?.jsonArray?.let { result.add(it) }
            }
        }
        return result
    }

    private fun parseAnimeJsonArray(array: JsonArray?, quarantined: MutableList<String>): List<BackupAnimeEntry> {
        if (array == null) return emptyList()
        val out = ArrayList<BackupAnimeEntry>()
        array.forEachIndexed { idx, elem ->
            runCatching {
                val obj = elem.jsonObject
                if (obj.containsKey("media")) {
                    // AniList export wrapper entry
                    val mediaObj = obj["media"]!!.jsonObject
                    val titleObj = mediaObj["title"]?.jsonObject
                    val eng = titleObj?.get("english")?.jsonPrimitive?.contentOrNullCompat()
                    val rom = titleObj?.get("romaji")?.jsonPrimitive?.contentOrNullCompat()
                    val nat = titleObj?.get("native")?.jsonPrimitive?.contentOrNullCompat()
                    if (eng.isNullOrBlank() && rom.isNullOrBlank() && nat.isNullOrBlank()) {
                        error("Missing title")
                    }
                    BackupAnimeEntry(
                        localId = UUID.randomUUID().toString(),
                        anilistId = mediaObj["id"]?.jsonPrimitive?.intOrNull,
                        titleEnglish = eng,
                        titleRomaji = rom,
                        titleNative = nat,
                        coverUrl = mediaObj["coverImage"]?.jsonObject?.get("large")?.jsonPrimitive?.contentOrNullCompat(),
                        status = OfflineWatchStatus.safeParse(obj["status"]?.jsonPrimitive?.contentOrNullCompat()).name,
                        episodesWatched = obj["progress"]?.jsonPrimitive?.intOrNull ?: 0,
                        totalEpisodes = mediaObj["episodes"]?.jsonPrimitive?.intOrNull,
                        score = obj["score"]?.jsonPrimitive?.floatOrNull,
                        notes = obj["notes"]?.jsonPrimitive?.contentOrNullCompat().orEmpty(),
                        genreNames = mediaObj["genres"]?.jsonArray
                            ?.mapNotNull { it.jsonPrimitive.contentOrNullCompat() }
                            .orEmpty(),
                    )
                } else {
                    val entry = json.decodeFromJsonElement(BackupAnimeEntry.serializer(), obj)
                    val hasAnyTitle = !entry.titleEnglish.isNullOrBlank() ||
                        !entry.titleRomaji.isNullOrBlank() ||
                        !entry.titleNative.isNullOrBlank()
                    if (!hasAnyTitle) error("Anime entry has no title")
                    entry.copy(
                        localId = entry.localId.ifBlank { UUID.randomUUID().toString() },
                        status = OfflineWatchStatus.safeParse(entry.status).name,
                        episodesWatched = entry.episodesWatched.coerceAtLeast(0),
                    )
                }
            }.onSuccess { out.add(it) }
                .onFailure { err ->
                    quarantined.add("Anime #${idx + 1}: ${err.message ?: "corrupted entry"}")
                }
        }
        return out
    }

    private fun parseGenreJsonArray(array: JsonArray?, quarantined: MutableList<String>): List<BackupGenre> {
        if (array == null) return emptyList()
        val out = ArrayList<BackupGenre>()
        array.forEachIndexed { idx, elem ->
            runCatching {
                val g = json.decodeFromJsonElement(BackupGenre.serializer(), elem)
                if (g.name.isBlank()) error("Blank genre name")
                g.copy(id = g.id.ifBlank { "custom_${UUID.randomUUID()}" }, isCustom = true)
            }.onSuccess { out.add(it) }
                .onFailure { err ->
                    quarantined.add("Genre #${idx + 1}: ${err.message ?: "corrupted genre"}")
                }
        }
        return out
    }

    private fun parseMalXml(xmlText: String): ParsedImportResult {
        val quarantined = ArrayList<String>()
        val entries = ArrayList<BackupAnimeEntry>()
        val doc = runCatching {
            val factory = DocumentBuilderFactory.newInstance().apply {
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
            factory.newDocumentBuilder().parse(ByteArrayInputStream(xmlText.toByteArray(Charsets.UTF_8)))
        }.getOrElse { err ->
            return ParsedImportResult(
                validAnimes = emptyList(),
                validCustomGenres = emptyList(),
                preferences = null,
                isChecksumMismatch = false,
                quarantinedItems = listOf("Malformed MAL XML: ${err.message}"),
            )
        }

        val animeNodes = doc.getElementsByTagName("anime")
        for (i in 0 until animeNodes.length) {
            val elem = animeNodes.item(i) as? Element ?: continue
            runCatching {
                val title = elem.childText("series_title")?.trim()
                    ?: error("Missing <series_title>")
                if (title.isEmpty()) error("Empty <series_title>")
                val watched = elem.childText("my_watched_episodes")?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val total = elem.childText("series_episodes")?.toIntOrNull()?.takeIf { it > 0 }
                val score = elem.childText("my_score")?.toFloatOrNull()?.takeIf { it > 0f }
                val status = OfflineWatchStatus.safeParse(elem.childText("my_status")).name
                val notes = elem.childText("my_comments").orEmpty().trim()
                BackupAnimeEntry(
                    localId = UUID.randomUUID().toString(),
                    anilistId = null,
                    titleEnglish = title,
                    titleRomaji = title,
                    status = status,
                    episodesWatched = watched,
                    totalEpisodes = total,
                    score = score,
                    notes = notes,
                )
            }.onSuccess { entries.add(it) }
                .onFailure { err ->
                    quarantined.add("MAL XML anime #${i + 1}: ${err.message}")
                }
        }
        return ParsedImportResult(entries, emptyList(), null, false, quarantined)
    }

    private fun Element.childText(tag: String): String? {
        val list = getElementsByTagName(tag)
        if (list.length == 0) return null
        return list.item(0)?.textContent
    }

    private fun JsonPrimitive.contentOrNullCompat(): String? = if (isString) content else content.takeIf { it != "null" }

    /** Normalizes titles for conflict comparison across backups and manual/external imports. */
    fun normalizeTitle(title: String): String =
        title.trim().lowercase().replace(Regex("""\s+"""), " ")
}
