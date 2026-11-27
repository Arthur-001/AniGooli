package com.anigooli.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupAndConflictEngineTest {

    private fun sampleLocalAnime(): List<AnimeWithGenres> {
        val genre = GenreEntity(id = "custom_1", name = "Dark Fantasy", isCustom = true, colorHex = "#EF5350")
        val anime = OfflineAnimeEntity(
            localId = "local-uuid-1",
            anilistId = 16498,
            titleEnglish = "Attack on Titan",
            titleRomaji = "Shingeki no Kyojin",
            status = OfflineWatchStatus.WATCHING.name,
            episodesWatched = 12,
            totalEpisodes = 25,
            score = 9.0f,
            createdAt = 100L,
            updatedAt = 200L,
        )
        return listOf(AnimeWithGenres(anime = anime, genres = listOf(genre)))
    }

    @Test
    fun `export and clean reimport preserves data with zero checksum mismatch`() {
        val local = sampleLocalAnime()
        val json = BackupEngine.exportToJson(
            animes = local,
            genres = local.flatMap { it.genres },
            preferences = BackupPreferencesSnapshot(
                titleLanguage = "ENGLISH",
                personalListColumns = 4,
                preBufferMaxMb = 2048,
            ),
            exportedAt = 12345L,
        )

        val parsed = BackupEngine.parseImport(json)
        assertFalse(parsed.isChecksumMismatch)
        assertTrue(parsed.quarantinedItems.isEmpty())
        assertEquals(1, parsed.validAnimes.size)
        assertEquals(1, parsed.validCustomGenres.size)
        assertEquals(4, parsed.preferences?.personalListColumns)
        assertEquals(2048, parsed.preferences?.preBufferMaxMb)
    }

    @Test
    fun `fault tolerant parser quarantines corrupt entries and detects tamper warning without crashing`() {
        val corruptedBundle = """
            {
              "schemaVersion": 1,
              "exportedAt": 1000,
              "sha256": "deadbeef",
              "payload": {
                "animes": [
                  {"titleEnglish": "Frieren", "status": "WATCHING", "episodesWatched": 5},
                  {"titleEnglish": "", "episodesWatched": 3},
                  "NOT_AN_OBJECT"
                ],
                "customGenres": [
                  {"id": "c1", "name": "Cozy", "isCustom": true},
                  {"id": "c2", "name": ""}
                ]
              }
            }
        """.trimIndent()

        val parsed = BackupEngine.parseImport(corruptedBundle)
        assertTrue(parsed.isChecksumMismatch)
        assertEquals(1, parsed.validAnimes.size)
        assertEquals("Frieren", parsed.validAnimes[0].titleEnglish)
        assertEquals(1, parsed.validCustomGenres.size)
        assertEquals("Cozy", parsed.validCustomGenres[0].name)
        assertEquals(3, parsed.quarantinedItems.size)
    }

    @Test
    fun `conflict detector identifies collisions by anilistId and normalized title`() {
        val local = sampleLocalAnime()
        val parsed = ParsedImportResult(
            validAnimes = listOf(
                BackupAnimeEntry(
                    localId = "other-id",
                    anilistId = 16498,
                    titleEnglish = "Attack on Titan",
                    status = OfflineWatchStatus.COMPLETED.name,
                    episodesWatched = 25,
                ),
                BackupAnimeEntry(
                    localId = "new-id",
                    anilistId = 21,
                    titleEnglish = "One Piece",
                    status = OfflineWatchStatus.WATCHING.name,
                    episodesWatched = 100,
                ),
            ),
            validCustomGenres = listOf(
                BackupGenre(id = "c_in", name = "Dark Fantasy", colorHex = "#29B6F6"),
            ),
            preferences = null,
            isChecksumMismatch = false,
            quarantinedItems = emptyList(),
        )

        val plan = BackupEngine.prepareRestorePlan(parsed, local, local.flatMap { it.genres })
        assertTrue(plan.hasConflicts)
        assertEquals(1, plan.newAnimes.size)
        assertEquals("One Piece", plan.newAnimes[0].titleEnglish)
        assertEquals(1, plan.animeConflicts.size)
        assertEquals(12, plan.animeConflicts[0].existing.anime.episodesWatched)
        assertEquals(25, plan.animeConflicts[0].incoming.episodesWatched)
        assertEquals(1, plan.genreConflicts.size)
    }

    @Test
    fun `mal xml import recovers valid entries and quarantines broken nodes`() {
        val xml = """
            <myanimelist>
              <anime>
                <series_title>Steins;Gate</series_title>
                <series_episodes>24</series_episodes>
                <my_watched_episodes>24</my_watched_episodes>
                <my_score>10</my_score>
                <my_status>Completed</my_status>
              </anime>
              <anime>
                <series_title></series_title>
              </anime>
            </myanimelist>
        """.trimIndent()

        val parsed = BackupEngine.parseImport(xml)
        assertEquals(1, parsed.validAnimes.size)
        assertEquals("Steins;Gate", parsed.validAnimes[0].titleEnglish)
        assertEquals(OfflineWatchStatus.COMPLETED.name, parsed.validAnimes[0].status)
        assertEquals(1, parsed.quarantinedItems.size)
    }

    @Test
    fun `prepareRestorePlan deduplicates seasons against local and within incoming import`() {
        val local = sampleLocalAnime()
        val parsed = ParsedImportResult(
            validAnimes = listOf(
                BackupAnimeEntry(titleEnglish = "Attack on Titan Season 2", anilistId = 20958),
                BackupAnimeEntry(titleEnglish = "Jujutsu Kaisen", anilistId = 113415),
                BackupAnimeEntry(titleEnglish = "Jujutsu Kaisen Season 2", anilistId = 145064),
                BackupAnimeEntry(titleEnglish = "Jujutsu Kaisen 0: The Movie", anilistId = 131573),
            ),
            validCustomGenres = emptyList(),
            preferences = null,
            isChecksumMismatch = false,
            quarantinedItems = emptyList(),
        )

        val plan = BackupEngine.prepareRestorePlan(parsed, local, emptyList())
        assertEquals(1, plan.animeConflicts.size)
        assertEquals("Attack on Titan", plan.animeConflicts[0].incoming.titleEnglish)
        assertEquals(1, plan.newAnimes.size)
        assertEquals("Jujutsu Kaisen", plan.newAnimes[0].titleEnglish)
    }
}
