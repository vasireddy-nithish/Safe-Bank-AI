package com.safebank.ai.data.network

import com.safebank.ai.BuildConfig
import com.safebank.ai.data.security.SessionTokenProvider
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Networking client responsible for constructing Retrofit and OkHttp instances.
 *
 * Base URL rules:
 * - Default: http://10.0.2.2:8000/api/v1/ (Android Emulator to Host)
 * - Configurable at runtime for physical Android devices via [setBaseUrl].
 * - Never uses localhost (which resolves to the Android device loopback).
 * - Never logs Bearer tokens or sensitive headers.
 */
class BankingApiClient(
    private val tokenProvider: SessionTokenProvider,
    customBaseUrl: String? = null,
    customOkHttpClient: OkHttpClient? = null
) {
    companion object {
        const val DEFAULT_EMULATOR_BASE_URL = "http://10.0.2.2:8000/api/v1/"
        const val TIMEOUT_SECONDS = 15L
    }

    private var activeBaseUrl: String = sanitizeBaseUrl(
        customBaseUrl ?: try {
            BuildConfig.BASE_URL
        } catch (e: Throwable) {
            DEFAULT_EMULATOR_BASE_URL
        }
    )

    private val authInterceptor = AuthInterceptor(tokenProvider)

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = if (BuildConfig.DEBUG) {
            HttpLoggingInterceptor.Level.BASIC
        } else {
            HttpLoggingInterceptor.Level.NONE
        }
        // STRICT SECURITY REQUIREMENT: Never log authorization headers or tokens
        redactHeader("Authorization")
        redactHeader("Idempotency-Key")
    }

    val okHttpClient: OkHttpClient = customOkHttpClient ?: OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .addInterceptor(authInterceptor)
        .addInterceptor(loggingInterceptor)
        .retryOnConnectionFailure(false) // Let repository control retry logic with idempotency
        .build()

    private var retrofit: Retrofit = buildRetrofit(activeBaseUrl, okHttpClient)

    var apiService: BankingApiService = retrofit.create(BankingApiService::class.java)
        private set

    fun getBaseUrl(): String = activeBaseUrl

    fun setBaseUrl(newBaseUrl: String) {
        val sanitized = sanitizeBaseUrl(newBaseUrl)
        if (sanitized != activeBaseUrl) {
            activeBaseUrl = sanitized
            retrofit = buildRetrofit(activeBaseUrl, okHttpClient)
            apiService = retrofit.create(BankingApiService::class.java)
        }
    }

    private fun buildRetrofit(baseUrl: String, client: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    private fun sanitizeBaseUrl(url: String): String {
        val trimmed = url.trim()
        return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
    }
}
