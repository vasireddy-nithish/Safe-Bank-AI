package com.safebank.ai.data.security

/**
 * Interface for providing and managing the authenticated session token.
 */
interface SessionTokenProvider {
    /**
     * Retrieves the current active session token, or null if unauthenticated.
     */
    fun getSessionToken(): String?

    /**
     * Called when a 401 Unauthorized response is received, notifying that
     * the session token has expired or is invalid.
     */
    fun onSessionExpired()
}
