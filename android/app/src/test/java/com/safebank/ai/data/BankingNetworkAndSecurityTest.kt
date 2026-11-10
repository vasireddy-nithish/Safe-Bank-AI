package com.safebank.ai.data

import com.google.gson.Gson
import com.safebank.ai.data.idempotency.IdempotencyKeyManager
import com.safebank.ai.data.model.IndianCurrencyFormatter
import com.safebank.ai.data.model.PendingTransferStatus
import com.safebank.ai.data.network.AuthInterceptor
import com.safebank.ai.data.network.BankingApiClient
import com.safebank.ai.data.network.dto.AccountDto
import com.safebank.ai.data.network.dto.ApiErrorDto
import com.safebank.ai.data.network.dto.ReconcileResponseDto
import com.safebank.ai.data.network.dto.TransferRequestDto
import com.safebank.ai.data.network.dto.TransferResponseDto
import com.safebank.ai.data.security.SecureSessionManager
import com.safebank.ai.data.security.SessionTokenProvider
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigDecimal
import java.util.UUID

class BankingNetworkAndSecurityTest {

    private val gson = Gson()

    @Test
    fun testIndianCurrencyFormatter_precisionAndFormatting() {
        val amount = BigDecimal("124500.80")
        val formatted = IndianCurrencyFormatter.formatInr(amount)
        assertTrue("Formatted string should contain ₹ symbol: $formatted", formatted.contains("₹"))
        assertTrue("Formatted string should contain 1,24,500.80: $formatted", formatted.contains("1,24,500.80"))

        val apiString = IndianCurrencyFormatter.toApiString(amount)
        assertEquals("124500.80", apiString)

        val smallAmount = BigDecimal("49.99")
        assertEquals("49.99", IndianCurrencyFormatter.toApiString(smallAmount))

        val parsed = IndianCurrencyFormatter.parseAmount(" 5000.50 ")
        assertNotNull(parsed)
        assertEquals(BigDecimal("5000.50"), parsed)

        assertNull("Negative amount must be rejected", IndianCurrencyFormatter.parseAmount("-100.00"))
        assertNull("Zero amount must be rejected", IndianCurrencyFormatter.parseAmount("0.00"))
        assertNull("Invalid string must be rejected", IndianCurrencyFormatter.parseAmount("abc"))
    }

    @Test
    fun testSecureSessionManager_tokenLifecycleAndListeners() {
        val sessionManager = SecureSessionManager.createInMemory()
        assertFalse(sessionManager.hasActiveSession())
        assertNull(sessionManager.getSessionToken())

        var authStateObserved = false
        sessionManager.addAuthListener { isAuthenticated ->
            authStateObserved = isAuthenticated
        }

        // Save session
        val testToken = "test_bearer_token_xyz_123"
        sessionManager.saveSession(testToken, "user-001")

        assertTrue(sessionManager.hasActiveSession())
        assertEquals(testToken, sessionManager.getSessionToken())
        assertEquals("user-001", sessionManager.getUserId())
        assertTrue("Auth listener should be notified of login", authStateObserved)

        // Expire session (e.g. 401)
        sessionManager.onSessionExpired()
        assertFalse(sessionManager.hasActiveSession())
        assertNull(sessionManager.getSessionToken())
        assertFalse("Auth listener should be notified of expiry", authStateObserved)
    }

    @Test
    fun testAuthInterceptor_injectsBearerToken() {
        var expiredCalled = false
        val provider = object : SessionTokenProvider {
            var token: String? = "secret_session_token_123"
            override fun getSessionToken(): String? = token
            override fun onSessionExpired() {
                expiredCalled = true
            }
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(provider))
            .addInterceptor { chain ->
                val req = chain.request()
                val authHeader = req.header("Authorization")
                assertEquals("Bearer secret_session_token_123", authHeader)

                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("{}".toResponseBody())
                    .build()
            }
            .build()

        val req = Request.Builder().url("https://example.com/api/test").build()
        val response = client.newCall(req).execute()
        assertEquals(200, response.code)
        assertFalse(expiredCalled)
    }

    @Test
    fun testAuthInterceptor_triggersSessionExpiredOn401() {
        var expiredCalled = false
        val provider = object : SessionTokenProvider {
            override fun getSessionToken(): String = "expired_token"
            override fun onSessionExpired() {
                expiredCalled = true
            }
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(provider))
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(401)
                    .message("Unauthorized")
                    .body("{\"detail\":\"Session expired\"}".toResponseBody())
                    .build()
            }
            .build()

        val req = Request.Builder().url("https://example.com/api/test").build()
        val response = client.newCall(req).execute()
        assertEquals(401, response.code)
        assertTrue("onSessionExpired() must be invoked on 401 Unauthorized", expiredCalled)
    }

    @Test
    fun testBankingApiClient_baseUrlSanitizationAndConfiguration() {
        val sessionManager = SecureSessionManager.createInMemory()
        val client = BankingApiClient(sessionManager, customBaseUrl = "http://192.168.1.100:8000/api/v1")
        assertEquals("http://192.168.1.100:8000/api/v1/", client.getBaseUrl())

        client.setBaseUrl("http://10.0.2.2:8000/api/v1/")
        assertEquals("http://10.0.2.2:8000/api/v1/", client.getBaseUrl())
        assertFalse("Must not use localhost", client.getBaseUrl().contains("localhost"))
    }

    @Test
    fun testIdempotencyKeyManager_singleKeyLifecycleAndInFlightGuards() {
        val manager = IdempotencyKeyManager(inMemoryOnly = true)
        assertFalse(manager.isTransferInFlight())
        assertNull(manager.getActiveTransfer())

        val amount = BigDecimal("5000.00")
        val pending1 = manager.startNewTransfer("acc-1", "acc-2", amount, "Test transfer")

        assertNotNull(pending1.idempotencyKey)
        // Verify valid UUID
        UUID.fromString(pending1.idempotencyKey)
        assertTrue(manager.isTransferInFlight())
        assertEquals(pending1.idempotencyKey, manager.getActiveTransfer()?.idempotencyKey)

        // Attempting a second concurrent transfer while one is in flight must throw IllegalStateException
        try {
            manager.startNewTransfer("acc-1", "acc-3", BigDecimal("1000.00"))
            fail("Duplicate transfer submission must be blocked while one is in flight")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("already in flight"))
        }

        // Complete the transfer
        manager.updateStatus(pending1.idempotencyKey, PendingTransferStatus.COMPLETED)
        assertFalse(manager.isTransferInFlight())
        assertNull(manager.getActiveTransfer())

        // Verified persisted record still retrievable by key
        val retrieved = manager.getTransfer(pending1.idempotencyKey)
        assertNotNull(retrieved)
        assertEquals(PendingTransferStatus.COMPLETED, retrieved?.status)
    }

    @Test
    fun testIdempotencyKeyManager_markReconciliationRequiredPreservesKey() {
        val manager = IdempotencyKeyManager(inMemoryOnly = true)
        val pending = manager.startNewTransfer("acc-1", "acc-2", BigDecimal("2500.00"))
        val originalKey = pending.idempotencyKey

        manager.markReconciliationRequired(originalKey)
        val active = manager.getActiveTransfer()
        assertNotNull(active)
        assertEquals(originalKey, active?.idempotencyKey)
        assertEquals(PendingTransferStatus.RECONCILIATION_REQUIRED, active?.status)
    }

    @Test
    fun testDtoSerializationAndPrecision() {
        // 1. AccountDto deserialization
        val accountJson = """
            {
                "id": "00000000-0000-4000-a000-000000000010",
                "masked_account_number": "•••• 9481",
                "account_type": "checking",
                "balance": "124500.80",
                "currency": "INR",
                "status": "active",
                "is_primary": true
            }
        """.trimIndent()

        val accountDto = gson.fromJson(accountJson, AccountDto::class.java)
        assertEquals("00000000-0000-4000-a000-000000000010", accountDto.id)
        assertEquals("•••• 9481", accountDto.maskedAccountNumber)
        assertEquals("124500.80", accountDto.balance)
        assertEquals("INR", accountDto.currency)
        assertTrue(accountDto.isPrimary)

        // 2. TransferRequestDto serialization
        val requestDto = TransferRequestDto(
            fromAccountId = "acc-from",
            toAccountId = "acc-to",
            amount = "5000.00",
            currency = "INR",
            description = "Monthly rent"
        )
        val reqJson = gson.toJson(requestDto)
        assertTrue(reqJson.contains("\"amount\":\"5000.00\""))
        assertTrue(reqJson.contains("\"currency\":\"INR\""))

        // 3. TransferResponseDto deserialization
        val responseJson = """
            {
                "success": true,
                "transaction_id": "tx-12345",
                "reference_number": "TX-REF-001",
                "status": "completed",
                "from_account_id": "acc-from",
                "to_account_id": "acc-to",
                "amount": "5000.00",
                "currency": "INR",
                "idempotency_key": "c4b8e21a-1234-4567-89ab-cdef01234567",
                "completed_at": "2026-11-10T12:00:00Z",
                "message": "Transfer completed successfully"
            }
        """.trimIndent()
        val responseDto = gson.fromJson(responseJson, TransferResponseDto::class.java)
        assertTrue(responseDto.success)
        assertEquals("tx-12345", responseDto.transactionId)
        assertEquals("5000.00", responseDto.amount)

        // 4. ReconcileResponseDto deserialization
        val reconcileJson = """
            {
                "state": "COMPLETED",
                "success": true,
                "retryable": false,
                "idempotency_key": "c4b8e21a-1234-4567-89ab-cdef01234567",
                "transaction_id": "tx-12345",
                "reference_number": "TX-REF-001",
                "status": "completed",
                "amount": "5000.00",
                "currency": "INR",
                "completed_at": "2026-11-10T12:00:00Z",
                "detail": null,
                "error_code": null
            }
        """.trimIndent()
        val reconcileDto = gson.fromJson(reconcileJson, ReconcileResponseDto::class.java)
        assertEquals("COMPLETED", reconcileDto.state)
        assertTrue(reconcileDto.success)
        assertFalse(reconcileDto.retryable)

        // 5. ApiErrorDto parsing
        val errorJson = """
            {
                "detail": "INSUFFICIENT_FUNDS: Available balance 1000.00 is less than requested 5000.00",
                "retryable": false,
                "idempotency_key": "c4b8e21a-1234-4567-89ab-cdef01234567"
            }
        """.trimIndent()
        val errorDto = gson.fromJson(errorJson, ApiErrorDto::class.java)
        assertTrue(errorDto.getDetailMessage().contains("INSUFFICIENT_FUNDS"))
        assertFalse(errorDto.retryable ?: true)
    }
}
