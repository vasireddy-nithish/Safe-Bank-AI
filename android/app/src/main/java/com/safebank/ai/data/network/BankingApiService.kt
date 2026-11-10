package com.safebank.ai.data.network

import com.safebank.ai.data.network.dto.AccountDto
import com.safebank.ai.data.network.dto.ReconcileResponseDto
import com.safebank.ai.data.network.dto.TransferRequestDto
import com.safebank.ai.data.network.dto.TransferResponseDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Retrofit service definition for SafeBank Banking API endpoints.
 */
interface BankingApiService {

    /**
     * Lists accounts belonging to the authenticated user.
     * Masked account numbers only.
     */
    @GET("banking/accounts")
    suspend fun getAccounts(): Response<List<AccountDto>>

    /**
     * Executes an internal transfer between user's accounts.
     * Requires UUIDv4 Idempotency-Key header.
     */
    @POST("banking/transfers")
    suspend fun executeTransfer(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: TransferRequestDto
    ): Response<TransferResponseDto>

    /**
     * Reconciles transfer state using the original idempotency key.
     * Returns COMPLETED, PROCESSING, FAILED, or NOT_FOUND (404).
     */
    @GET("banking/transfers/reconcile/{idempotency_key}")
    suspend fun reconcileTransfer(
        @Path("idempotency_key") idempotencyKey: String
    ): Response<ReconcileResponseDto>
}
