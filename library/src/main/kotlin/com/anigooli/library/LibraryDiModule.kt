package com.anigooli.library

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Provider
import javax.inject.Singleton

/** Hilt module providing [LibraryDatabase] and [LibraryDao]. */
@Module
@InstallIn(SingletonComponent::class)
object LibraryDiModule {

    private val migration1To2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE anime_playback_prefs ADD COLUMN censorshipVersion TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE watch_history ADD COLUMN providerId TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE watch_history ADD COLUMN malId INTEGER DEFAULT NULL")
        }
    }

    private val migration2To3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `watched_episodes` (
                    `anilistId` INTEGER NOT NULL,
                    `episodeNumber` TEXT NOT NULL,
                    `activeWatchMs` INTEGER NOT NULL DEFAULT 0,
                    `durationMs` INTEGER NOT NULL DEFAULT 0,
                    `isWatched` INTEGER NOT NULL DEFAULT 0,
                    `watchedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`anilistId`, `episodeNumber`)
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_watched_episodes_anilistId` ON `watched_episodes` (`anilistId`)"
            )
        }
    }

    /** Builds or opens [LibraryDatabase] and seeds default genres on initial creation. */
    @Provides
    @Singleton
    fun provideLibraryDatabase(
        @ApplicationContext context: Context,
        daoProvider: Provider<LibraryDao>,
    ): LibraryDatabase {
        return Room.databaseBuilder(
            context,
            LibraryDatabase::class.java,
            "anigooli_library.db",
        ).addMigrations(migration1To2, migration2To3).addCallback(
            object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    super.onCreate(db)
                    CoroutineScope(Dispatchers.IO).launch {
                        daoProvider.get().insertGenresIgnore(
                            DefaultGenres.names.map { name ->
                                GenreEntity(
                                    id = DefaultGenres.defaultId(name),
                                    name = name,
                                    isCustom = false,
                                )
                            }
                        )
                    }
                }
            }
        ).build()
    }

    /** Exposes [LibraryDao]. */
    @Provides
    fun provideLibraryDao(db: LibraryDatabase): LibraryDao = db.libraryDao()
}
