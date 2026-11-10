package com.safebank.ai.data.network

import com.safebank.ai.data.security.SessionTokenProvider
import okhttp3.Interceptor
import okhttp3.Response

/**
 * OkHttp Interceptor that injects Bearer token into outbound HTTP requests.
 *
 * Security Requirements:
 * 1. Bearer tokens must NEVER be logged to logcat or persistent logs.
 * 2. 401 Unauthorized responses trigger session expiration on [SessionTokenProvider].
 */
class AuthInterceptor(
    private val tokenProvider: SessionTokenProvider
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        val token = tokenProvider.getSessionToken()
        val authenticatedRequest = if (!token.isNullOrBlank()) {
            originalRequest.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else {
            originalRequest
        }

        val response = chain.proceed(authenticatedRequest)

        // Handle session expiry on 401 Unauthorized
        if (response.code == 401) {
            tokenProvider.onSessionExpired()
        }

        return response
    }
}
