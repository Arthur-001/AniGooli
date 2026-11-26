package com.anigooli.streaming

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

/** Hilt module providing shared networking dependencies from :streaming. */
@Module
@InstallIn(SingletonComponent::class)
object StreamingDiModule {
    /** Exposes the shared [OkHttpClient] with cookie support. */
    @Provides
    @Singleton
    fun provideOkHttpClient(client: CloudflareAwareHttpClient): OkHttpClient = client.okHttpClient
}
