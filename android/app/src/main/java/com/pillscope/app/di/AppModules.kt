package com.pillscope.app.di

import android.content.Context
import androidx.room.Room
import com.pillscope.app.BuildConfig
import com.pillscope.app.data.local.PillScopeDatabase
import com.pillscope.app.data.local.dao.MedicationDao
import com.pillscope.app.data.local.dao.ScanReportDao
import com.pillscope.app.data.remote.AuthTokenProvider
import com.pillscope.app.data.remote.BearerAuthInterceptor
import com.pillscope.app.data.remote.PillScopeApi
import com.pillscope.app.data.remote.SharedPrefsAuthTokenStore
import com.pillscope.app.data.remote.sse.OkHttpScanSseClient
import com.pillscope.app.data.remote.sse.ScanSseClient
import com.pillscope.app.data.remote.sse.ScanSseParser
import com.pillscope.app.ml.LiteRtLabelPreprocessor
import com.pillscope.app.ml.MlKitOcrEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun provideJson(): Json = ScanSseParser.defaultSseJson

    @Provides
    @Singleton
    fun provideAuthTokenProvider(@ApplicationContext context: Context): AuthTokenProvider =
        SharedPrefsAuthTokenStore(context)

    @Provides
    @Singleton
    fun provideOkHttpClient(tokenProvider: AuthTokenProvider): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }
        return OkHttpClient.Builder()
            .addInterceptor(BearerAuthInterceptor(tokenProvider))
            .addInterceptor(logging)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient, json: Json): Retrofit {
        val contentType = "application/json".toMediaType()
        return Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
    }

    @Provides
    @Singleton
    fun providePillScopeApi(retrofit: Retrofit): PillScopeApi =
        retrofit.create(PillScopeApi::class.java)

    @Provides
    @Singleton
    fun provideScanSseParser(json: Json): ScanSseParser = ScanSseParser(json)

    @Provides
    @Singleton
    fun provideScanSseClient(
        okHttpClient: OkHttpClient,
        parser: ScanSseParser,
        json: Json,
    ): ScanSseClient = OkHttpScanSseClient(
        okHttpClient = okHttpClient.newBuilder().readTimeout(0, TimeUnit.SECONDS).build(),
        baseUrl = BuildConfig.API_BASE_URL,
        parser = parser,
        json = json,
    )
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): PillScopeDatabase =
        Room.databaseBuilder(
            context,
            PillScopeDatabase::class.java,
            "pillscope_offline.db",
        ).fallbackToDestructiveMigration().build()

    @Provides
    fun provideMedicationDao(db: PillScopeDatabase): MedicationDao = db.medicationDao()

    @Provides
    fun provideScanReportDao(db: PillScopeDatabase): ScanReportDao = db.scanReportDao()
}

@Module
@InstallIn(SingletonComponent::class)
object MlModule {
    @Provides
    @Singleton
    fun provideLiteRtPreprocessor(@ApplicationContext context: Context): LiteRtLabelPreprocessor =
        LiteRtLabelPreprocessor(context)

    @Provides
    @Singleton
    fun provideMlKitOcrEngine(): MlKitOcrEngine = MlKitOcrEngine()
}
