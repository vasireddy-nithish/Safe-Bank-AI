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
    @GET("auth/behaviour-enrolment")
    suspend fun enrolmentStatus(): Response<EnrolmentStatus>

    @POST("auth/behaviour-enrolment/samples")
    suspend fun enrolmentSample(@Body sample: EnrolmentSample): Response<EnrolmentStatus>

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
     */
    @POST("auth/register")
    suspend fun register(@Body request: RegisterRequest): Response<RegisterResponseDto>

    @POST("auth/dev-session/by-username")
    suspend fun demoLoginByUsername(@Body request: DevUsernameLoginRequest): Response<LoginResponse>

    // -------------------------------------------------------------------------
    // Duress API
    // -------------------------------------------------------------------------

    @PUT("duress/pin")
    suspend fun configureDuressPin(@Body request: DuressPinRequest): Response<Map<String, Boolean>>

    @POST("duress/activate")
    suspend fun activateDuress(@Body request: DuressActivationRequest): Response<Map<String, String>>

    // -------------------------------------------------------------------------
    // Risk & Security Events
    // -------------------------------------------------------------------------

    @GET("security/events")
    suspend fun getSecurityEvents(): Response<List<SecurityEventDto>>

    @POST("security/demo-scenario")
    suspend fun triggerDemoScenario(@Body request: DemoScenarioRequest): Response<DemoScenarioResponse>

    @POST("risk/submit")
    suspend fun submitRiskScore(@Body request: RiskSubmissionRequest): Response<RiskDecisionResponse>

    @POST("risk/step-up")
    suspend fun submitStepUp(@Body request: StepUpRequest): Response<StepUpResponse>
}

data class DemoLoginRequest(val user_id: String, val device_id: String)
data class DevUsernameLoginRequest(val username: String)
data class LoginResponse(val access_token: String, val user_id: String, val is_restricted: Boolean)
data class SecurityStatusResponse(val restricted: Boolean, val risk_level: String, val action: String, val server_score: Int? = null)

// Registration
data class RegisterRequest(
    val full_name: String,
    val username: String,
    val enrolment_code: String,
    val duress_pin_opt_in: Boolean,
    val duress_pin: String? = null
)

data class RegisterResponseDto(
    val user_id: String,
    val username: String,
    val duress_configured: Boolean = false,
    val message: String
)

// Duress
data class DuressPinRequest(val pin: String)
data class DuressActivationRequest(val username: String, val pin: String)

// Security events (matches backend GET /security/events)
data class SecurityEventDto(
    val id: String,
    val type: String,
    val severity: String,
    val timestamp: String
)

data class DemoScenarioRequest(val scenario: String)
data class DemoScenarioResponse(val simulated: Boolean, val action: String)

data class RiskSubmissionRequest(
    val genuine_probability: Float,
    val uncertain_probability: Float,
    val anomaly_probability: Float,
    val risk_score: Int,
    val is_from_live_model: Boolean = false,
    val session_data_points: Int = 0,
    val contract_version: String = "safebank.behaviour.v2",
    val transaction_context: Map<String, String>? = null
)

data class RiskDecisionResponse(
    val risk_level: String,
    val action: String,
    val server_score: Int
)

data class StepUpRequest(
    val method: String = "biometric_prompt",
    val verified: Boolean = true
)

data class StepUpResponse(
    val verified: Boolean,
    val action: String,
    val risk_level: String
)

// Only derived features leave the device; raw traces remain in memory.
data class EnrolmentStatus(
    val user_id: String,
    val counts: Map<String, Int>,
    val current_stage: String,
    val completed: Boolean,
    val baseline_features: List<Float>?
)
data class EnrolmentSample(val stage: String, val ordinal: Int,
    val features: List<Float>, val data_points: Int, val contract_version: String = "safebank.behaviour.v2")
