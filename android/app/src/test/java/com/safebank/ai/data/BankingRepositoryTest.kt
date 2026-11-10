package com.safebank.ai.data

import com.google.gson.Gson
import com.safebank.ai.data.idempotency.IdempotencyKeyManager
import com.safebank.ai.data.model.BankingResult
import com.safebank.ai.data.model.PendingTransferStatus
import com.safebank.ai.data.model.ReconcileState
import com.safebank.ai.data.model.RepositoryMode
import com.safebank.ai.data.network.BankingApiService
import com.safebank.ai.data.network.dto.AccountDto
import com.safebank.ai.data.network.dto.ReconcileResponseDto
import com.safebank.ai.data.network.dto.TransferRequestDto
import com.safebank.ai.data.network.dto.TransferResponseDto
import com.safebank.ai.data.repository.BankingApiDataSource
import com.safebank.ai.data.repository.BankingRepositoryImpl
import com.safebank.ai.data.repository.MockBankingDataSource
import com.safebank.ai.data.security.SecureSessionManager
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaTypeOrNull
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
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.math.BigDecimal
import java.net.SocketTimeoutException

class BankingRepositoryTest {

    private val gson = Gson()
    private lateinit var sessionManager: SecureSessionManager
    private lateinit var idempotencyManager: IdempotencyKeyManager
    private lateinit var mockDataSource: MockBankingDataSource

    // Interceptor state for dynamic test mocking
    private var simulatedResponseCode: Int = 200
    private var simulatedResponseBody: String = ""
    private var simulatedException: IOException? = null
    private var lastRecordedIdempotencyHeader: String? = null
    private var lastRecordedRequestBody: String? = null

    private lateinit var fakeApiService: BankingApiService
    private lateinit var apiDataSource: BankingApiDataSource
    private lateinit var repository: BankingRepositoryImpl

    @Before
    fun setUp() {
        sessionManager = SecureSessionManager.createInMemory()
        sessionManager.saveSession("test_token_123", "usr_alex_chen")

        idempotencyManager = IdempotencyKeyManager(inMemoryOnly = true)
        mockDataSource = MockBankingDataSource()

        simulatedResponseCode = 200
        simulatedResponseBody = ""
        simulatedException = null
        lastRecordedIdempotencyHeader = null
        lastRecordedRequestBody = null

        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request: Request = chain.request()
                lastRecordedIdempotencyHeader = request.header("Idempotency-Key")

                simulatedException?.let { throw it }

                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(simulatedResponseCode)
                    .message("Mock HTTP $simulatedResponseCode")
                    .body(simulatedResponseBody.toResponseBody("application/json".toMediaTypeOrNull()))
                    .build()
            }
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl("http://10.0.2.2:8000/api/v1/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        fakeApiService = retrofit.create(BankingApiService::class.java)
        apiDataSource = BankingApiDataSource(fakeApiService, gson)

        repository = BankingRepositoryImpl(
            apiDataSource = apiDataSource,
            mockDataSource = mockDataSource,
            idempotencyKeyManager = idempotencyManager,
            initialMode = RepositoryMode.BACKEND_CONNECTED
        )
    }

    @Test
    fun testModeSwitching_demoMockVsBackendConnected() = runBlocking {
        // 1. DEMO_MOCK mode
        repository.setRepositoryMode(RepositoryMode.DEMO_MOCK)
        assertEquals(RepositoryMode.DEMO_MOCK, repository.getRepositoryMode())

        val mockResult = repository.getAccounts()
        assertTrue(mockResult is BankingResult.Success)
        val mockAccounts = (mockResult as BankingResult.Success).data
        assertEquals(2, mockAccounts.size)
        assertEquals("•••• 9481", mockAccounts[0].maskedAccountNumber)
        assertEquals(BigDecimal("124500.80"), mockAccounts[0].balance)

        // 2. Switch explicitly to BACKEND_CONNECTED mode
        repository.setRepositoryMode(RepositoryMode.BACKEND_CONNECTED)
        assertEquals(RepositoryMode.BACKEND_CONNECTED, repository.getRepositoryMode())

        simulatedResponseCode = 200
        simulatedResponseBody = """
            [
                {
                    "id": "acc-backend-1",
                    "masked_account_number": "•••• 1111",
                    "account_type": "checking",
                    "balance": "99900.50",
                    "currency": "INR",
                    "status": "active",
                    "is_primary": true
                }
            ]
        """.trimIndent()

        val backendResult = repository.getAccounts()
        assertTrue(backendResult is BankingResult.Success)
        val backendAccounts = (backendResult as BankingResult.Success).data
        assertEquals(1, backendAccounts.size)
        assertEquals("•••• 1111", backendAccounts[0].maskedAccountNumber)
        assertEquals(BigDecimal("99900.50"), backendAccounts[0].balance)
    }

    @Test
    fun testNoSilentFallback_networkFailureReturnsErrorState() = runBlocking {
        // Spec Requirement: In BACKEND_CONNECTED mode, failure must return explicit error state,
        // NEVER silently fall back to mock data.
        repository.setRepositoryMode(RepositoryMode.BACKEND_CONNECTED)

        // Simulate network connection failure
        simulatedException = SocketTimeoutException("Connection timed out to 10.0.2.2:8000")

        val result = repository.getAccounts()
        assertTrue("Must return BankingResult.Error on network failure", result is BankingResult.Error)

        // Verify no mock data was leaked or returned
        val error = result as BankingResult.Error
        assertTrue("Error must reflect timeout or network issue", error.message.contains("timed out") || error.code == "NETWORK_TIMEOUT")
        assertTrue("Network error should be marked retryable", error.isRetryable)
        assertNotNull(error.cause)
    }

    @Test
    fun testExecuteTransfer_successWithIdempotencyHeader() = runBlocking {
        repository.setRepositoryMode(RepositoryMode.BACKEND_CONNECTED)

        val fromAcc = "00000000-0000-4000-a000-000000000010"
        val toAcc = "00000000-0000-4000-a000-000000000011"
        val amount = BigDecimal("5000.00")

        simulatedResponseCode = 201
        simulatedResponseBody = """
            {
                "success": true,
                "transaction_id": "tx-success-001",
                "reference_number": "TX-REF-849201",
                "status": "completed",
                "from_account_id": "$fromAcc",
                "to_account_id": "$toAcc",
                "amount": "5000.00",
                "currency": "INR",
                "idempotency_key": "will_be_checked",
                "completed_at": "2026-11-10T12:00:00Z",
                "message": "Transfer completed successfully"
            }
        """.trimIndent()

        val result = repository.executeTransfer(fromAcc, toAcc, amount, "Savings goal")
        assertTrue("Transfer execution should succeed", result is BankingResult.Success)

        val receipt = (result as BankingResult.Success).data
        assertEquals("tx-success-001", receipt.transactionId)
        assertEquals("TX-REF-849201", receipt.referenceNumber)
        assertEquals(BigDecimal("5000.00"), receipt.amount)
        assertEquals("INR", receipt.currency)

        // Verify Idempotency-Key header was passed
        assertNotNull("Idempotency header must be sent", lastRecordedIdempotencyHeader)
        assertTrue(lastRecordedIdempotencyHeader!!.isNotBlank())

        // Verify idempotency status in manager was marked COMPLETED
        assertFalse("No transfer should remain in flight after success", idempotencyManager.isTransferInFlight())
    }

    @Test
    fun testExecuteTransfer_rejectsDuplicateConcurrentSubmission() = runBlocking {
        repository.setRepositoryMode(RepositoryMode.BACKEND_CONNECTED)

        val fromAcc = "acc-1"
        val toAcc = "acc-2"

        // Simulate a transfer that leaves the intent in flight
        idempotencyManager.startNewTransfer(fromAcc, toAcc, BigDecimal("1000.00"))
        assertTrue(idempotencyManager.isTransferInFlight())

        val duplicateResult = repository.executeTransfer(fromAcc, toAcc, BigDecimal("2000.00"))
        assertTrue("Duplicate submission must be rejected", duplicateResult is BankingResult.Error)

        val error = duplicateResult as BankingResult.Error
        assertEquals("DUPLICATE_SUBMISSION_BLOCKED", error.code)
        assertTrue(error.message.contains("in flight"))
    }

    @Test
    fun testExecuteTransfer_errorParsing_insufficientFunds() = runBlocking {
        repository.setRepositoryMode(RepositoryMode.BACKEND_CONNECTED)

        simulatedResponseCode = 422
        simulatedResponseBody = """
            {
                "detail": "INSUFFICIENT_FUNDS: Available balance ₹1,000.00 is less than requested ₹5,000.00",
                "retryable": false,
                "idempotency_key": "c4b8e21a-1234-4567-89ab-cdef01234567"
            }
        """.trimIndent()

        val result = repository.executeTransfer("acc-1", "acc-2", BigDecimal("5000.00"))
        assertTrue(result is BankingResult.Error)

        val error = result as BankingResult.Error
        assertEquals(422, error.httpStatus)
        assertTrue(error.message.contains("INSUFFICIENT_FUNDS"))
        assertFalse(error.isRetryable)

        // Terminal error must update intent status to FAILED (clearing active transfer)
        assertFalse(idempotencyManager.isTransferInFlight())
    }

    @Test
    fun testExecuteTransfer_errorParsing_dailyLimitExceeded() = runBlocking {
        repository.setRepositoryMode(RepositoryMode.BACKEND_CONNECTED)

        simulatedResponseCode = 422
        simulatedResponseBody = """
            {
                "detail": "DAILY_LIMIT_EXCEEDED: Daily transfer limit of ₹50,000.00 would be exceeded",
                "retryable": false
            }
        """.trimIndent()

        val result = repository.executeTransfer("acc-1", "acc-2", BigDecimal("60000.00"))
        assertTrue(result is BankingResult.Error)

        val error = result as BankingResult.Error
        assertEquals(422, error.httpStatus)
        assertTrue(error.message.contains("DAILY_LIMIT_EXCEEDED"))
        assertFalse(error.isRetryable)
    }

    @Test
    fun testExecuteTransfer_errorParsing_databaseContentionRetryable() = runBlocking {
        repository.setRepositoryMode(RepositoryMode.BACKEND_CONNECTED)

        simulatedResponseCode = 503
        simulatedResponseBody = """
            {
                "detail": "DATABASE_CONTENTION_RETRYABLE: Temporary database lock contention. Please retry.",
                "retryable": true,
                "idempotency_key": "c4b8e21a-1234-4567-89ab-cdef01234567"
            }
        """.trimIndent()

        val result = repository.executeTransfer("acc-1", "acc-2", BigDecimal("5000.00"))
        assertTrue(result is BankingResult.Error)

        val error = result as BankingResult.Error
        assertEquals(503, error.httpStatus)
        assertTrue(error.isRetryable)
        assertTrue(error.message.contains("DATABASE_CONTENTION_RETRYABLE"))
    }

    @Test
    fun testExecuteTransfer_errorParsing_sessionExpired401() = runBlocking {
        repository.setRepositoryMode(RepositoryMode.BACKEND_CONNECTED)

        simulatedResponseCode = 401
        simulatedResponseBody = """
            {
                "detail": "Session expired or invalid token"
            }
        """.trimIndent()

        val result = repository.executeTransfer("acc-1", "acc-2", BigDecimal("500.00"))
        assertTrue(result is BankingResult.Error)

        val error = result as BankingResult.Error
        assertEquals(401, error.httpStatus)
        assertEquals("UNAUTHORIZED", error.code)
    }

    @Test
    fun testExecuteTransfer_timeoutFollowedBySuccessfulReconciliation() = runBlocking {
        // Spec Requirement:
        // If transfer execution fails due to network timeout or connection drop, the repository must
        // reconcile the transfer state using the transfer reconciliation API with the original idempotency key,
        // NOT generate a mock receipt or assume failure.
        repository.setRepositoryMode(RepositoryMode.BACKEND_CONNECTED)

        // When executeTransfer is called, the network drops (SocketTimeoutException)
        // But when reconcileTransfer is called next, the server returns that it actually completed!
        var callCount = 0
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                callCount++
                val req = chain.request()
                if (callCount == 1) {
                    // First call: execute transfer times out
                    throw SocketTimeoutException("Read timed out on POST /banking/transfers")
                } else {
                    // Second call: GET /banking/transfers/reconcile/{key} succeeds
                    val body = """
                        {
                            "state": "COMPLETED",
                            "success": true,
                            "retryable": false,
                            "idempotency_key": "${req.url.pathSegments.last()}",
                            "transaction_id": "tx-reconciled-999",
                            "reference_number": "TX-REC-999",
                            "status": "completed",
                            "amount": "5000.00",
                            "currency": "INR",
                            "completed_at": "2026-11-10T12:00:05Z",
                            "detail": null,
                            "error_code": null
                        }
                    """.trimIndent()

                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/json".toMediaTypeOrNull()))
                        .build()
                }
            }
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl("http://10.0.2.2:8000/api/v1/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        val customApiService = retrofit.create(BankingApiService::class.java)
        val customApiDataSource = BankingApiDataSource(customApiService, gson)
        val customRepo = BankingRepositoryImpl(
            apiDataSource = customApiDataSource,
            mockDataSource = mockDataSource,
            idempotencyKeyManager = idempotencyManager,
            initialMode = RepositoryMode.BACKEND_CONNECTED
        )

        val result = customRepo.executeTransfer("acc-1", "acc-2", BigDecimal("5000.00"))
        assertTrue("Reconciled completed transfer must return BankingResult.Success", result is BankingResult.Success)

        val receipt = (result as BankingResult.Success).data
        assertEquals("tx-reconciled-999", receipt.transactionId)
        assertEquals("TX-REC-999", receipt.referenceNumber)
        assertEquals(BigDecimal("5000.00"), receipt.amount)
        assertFalse("Transfer should no longer be in flight", idempotencyManager.isTransferInFlight())
    }

    @Test
    fun testExecuteTransfer_timeoutFollowedByNotFoundReconciliation() = runBlocking {
        // Transfer times out, reconcile endpoint returns 404 NOT_FOUND
        // Meaning the transfer never reached the database. Safe to retry with same key.
        var callCount = 0
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                callCount++
                val req = chain.request()
                if (callCount == 1) {
                    throw SocketTimeoutException("Connection timed out")
                } else {
                    // Reconcile returns 404 NOT_FOUND
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(404)
                        .message("Not Found")
                        .body("{\"detail\":\"No reservation found\",\"state\":\"NOT_FOUND\"}".toResponseBody("application/json".toMediaTypeOrNull()))
                        .build()
                }
            }
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl("http://10.0.2.2:8000/api/v1/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        val customApiService = retrofit.create(BankingApiService::class.java)
        val customApiDataSource = BankingApiDataSource(customApiService, gson)
        val customRepo = BankingRepositoryImpl(
            apiDataSource = customApiDataSource,
            mockDataSource = mockDataSource,
            idempotencyKeyManager = idempotencyManager,
            initialMode = RepositoryMode.BACKEND_CONNECTED
        )

        val result = customRepo.executeTransfer("acc-1", "acc-2", BigDecimal("1500.00"))
        assertTrue("Must return BankingResult.Error indicating safe to retry", result is BankingResult.Error)

        val error = result as BankingResult.Error
        assertEquals("TIMEOUT_NOT_RECEIVED", error.code)
        assertTrue("Must be marked retryable", error.isRetryable)
        assertTrue(error.message.contains("Safe to retry"))

        // Original idempotency key was preserved!
        val activeTransfer = customRepo.getActivePendingTransfer()
        assertNotNull(activeTransfer)
        assertEquals(BigDecimal("1500.00"), activeTransfer?.amount)
    }

    @Test
    fun testReconcileTransfer_processingState() = runBlocking {
        repository.setRepositoryMode(RepositoryMode.BACKEND_CONNECTED)

        val testKey = "c4b8e21a-1234-4567-89ab-cdef01234567"
        simulatedResponseCode = 202
        simulatedResponseBody = """
            {
                "state": "PROCESSING",
                "success": false,
                "retryable": true,
                "idempotency_key": "$testKey",
                "detail": "Transfer reservation is actively executing Phase 2"
            }
        """.trimIndent()

        val result = repository.reconcileTransfer(testKey)
        assertTrue(result is BankingResult.Success)

        val outcome = (result as BankingResult.Success).data
        assertEquals(ReconcileState.PROCESSING, outcome.state)
        assertTrue(outcome.retryable)
        assertEquals(testKey, outcome.idempotencyKey)
    }

    @Test
    fun testReconcileTransfer_failedState() = runBlocking {
        repository.setRepositoryMode(RepositoryMode.BACKEND_CONNECTED)

        val testKey = "c4b8e21a-1234-4567-89ab-cdef01234567"
        simulatedResponseCode = 200
        simulatedResponseBody = """
            {
                "state": "FAILED",
                "success": false,
                "retryable": false,
                "idempotency_key": "$testKey",
                "detail": "Transfer failed due to account freeze",
                "error_code": "ACCOUNT_FROZEN"
            }
        """.trimIndent()

        val result = repository.reconcileTransfer(testKey)
        assertTrue(result is BankingResult.Success)

        val outcome = (result as BankingResult.Success).data
        assertEquals(ReconcileState.FAILED, outcome.state)
        assertFalse(outcome.retryable)
        assertEquals("ACCOUNT_FROZEN", outcome.errorCode)
    }
}
