package com.safebank.ai.data.di

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.safebank.ai.data.idempotency.IdempotencyKeyManager
import com.safebank.ai.data.network.BankingApiClient
import com.safebank.ai.data.repository.BankingApiDataSource
import com.safebank.ai.data.repository.BankingRepository
import com.safebank.ai.data.repository.BankingRepositoryImpl
import com.safebank.ai.data.repository.MockBankingDataSource
import com.safebank.ai.data.security.SecureSessionManager

/**
 * Single shared repository provider supporting production singleton access
 * and explicit test injection/isolation without stale state inheritance.
 */
object BankingRepositoryProvider {

    @Volatile
    private var instance: BankingRepository? = null

    /**
     * Initializes or returns the shared BankingRepository singleton.
     */
    fun getRepository(context: Context? = null): BankingRepository {
        return instance ?: synchronized(this) {
            instance ?: createDefaultRepository(context).also { instance = it }
        }
    }

    /**
     * Explicit test injection to override the repository.
     */
    @VisibleForTesting
    fun setRepositoryForTesting(repository: BankingRepository) {
        synchronized(this) {
            instance = repository
        }
    }

    /**
     * Resets the singleton instance so unit/instrumentation tests do not inherit stale state.
     */
    @VisibleForTesting
    fun resetForTesting() {
        synchronized(this) {
            instance = null
        }
    }

    private fun createDefaultRepository(context: Context?): BankingRepository {
        val appContext = context?.applicationContext
        val sessionManager = if (appContext != null) {
            SecureSessionManager.getInstance(appContext)
        } else {
            SecureSessionManager.createInMemory()
        }

        val apiClient = BankingApiClient(tokenProvider = sessionManager)
        val apiDataSource = BankingApiDataSource(apiClient.apiService)
        val mockDataSource = MockBankingDataSource()
        val idempotencyKeyManager = if (appContext != null) {
            IdempotencyKeyManager(context = appContext)
        } else {
            IdempotencyKeyManager(inMemoryOnly = true)
        }

        return BankingRepositoryImpl(
            apiDataSource = apiDataSource,
            mockDataSource = mockDataSource,
            idempotencyKeyManager = idempotencyKeyManager
        )
    }
}
