package com.anigooli.downloads

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Hilt module providing [DownloadDatabase] and [DownloadDao]. */
@Module
@InstallIn(SingletonComponent::class)
object DownloadsDiModule {

    /** Creates or opens the Room [DownloadDatabase]. */
    @Provides
    @Singleton
    fun provideDownloadDatabase(@ApplicationContext context: Context): DownloadDatabase {
        return Room.databaseBuilder(
            context,
            DownloadDatabase::class.java,
            "anigooli_downloads.db",
        ).build()
    }

    /** Exposes [DownloadDao]. */
    @Provides
    fun provideDownloadDao(db: DownloadDatabase): DownloadDao = db.downloadDao()
}
