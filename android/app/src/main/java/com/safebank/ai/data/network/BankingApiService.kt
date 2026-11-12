package com.safebank.ai.data.network

import com.safebank.ai.data.network.dto.AccountDto
import com.safebank.ai.data.network.dto.ReconcileResponseDto
import com.safebank.ai.data.network.dto.TransactionResponseDto
import com.safebank.ai.data.network.dto.TransferRequestDto
import com.safebank.ai.data.network.dto.TransferResponseDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

/**
 * Retrofit service definition for SafeBank Banking API endpoints.
 */
interface BankingApiService {
    @POST("auth/dev-session")
    suspend fun demoLogin(@Body request: DemoLoginRequest): Response<LoginResponse>

    @GET("security/status")
    suspend fun securityStatus(): Response<SecurityStatusResponse>

    @POST("security/logout")
    suspend fun logout(): Response<Map<String, Boolean>>

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

    /**
     * Lists transaction history for the authenticated user.
     */
    @GET("banking/transactions")
    suspend fun getTransactions(): Response<List<TransactionResponseDto>>

    // -------------------------------------------------------------------------
    // Registration
    // -------------------------------------------------------------------------

    /**
     * Registers a new demo account.
     * Backend endpoint (POST /api/v1/auth/register) added by backend agent.
     */
    @POST("auth/register")
    suspend fun register(@Body request: RegisterRequest): Response<Map<String, String>>

    // -------------------------------------------------------------------------
    // Duress API
    // -------------------------------------------------------------------------

    @PUT("duress/pin")
    suspend fun configureDuressPin(@Body request: DuressPinRequest): Response<Map<String, Boolean>>

    @POST("duress/activate")
    suspend fun activateDuress(@Body request: DuressActivationRequest): Response<Map<String, String>>

    // -------------------------------------------------------------------------
    // Security Events
    // -------------------------------------------------------------------------

    @GET("security/events")
    suspend fun getSecurityEvents(): Response<List<SecurityEventDto>>
}

data class DemoLoginRequest(val user_id: String, val device_id: String)
data class LoginResponse(val access_token: String, val user_id: String, val is_restricted: Boolean)
data class SecurityStatusResponse(val restricted: Boolean, val risk_level: String, val action: String)

// Registration
data class RegisterRequest(
    val full_name: String,
    val username: String,
    val enrolment_code: String,
    val duress_pin_opt_in: Boolean
)

// Duress
data class DuressPinRequest(val pin: String)
data class DuressActivationRequest(val username: String, val pin: String)

// Security events
data class SecurityEventDto(
    val event_id: String,
    val event_type: String,
    val timestamp: String,
    val risk_score: Double,
    val description: String
)
