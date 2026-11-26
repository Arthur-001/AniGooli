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
        ).addMigrations(migration1To2).addCallback(
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
