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

    @Test
    fun testIdempotencyKeyManager_processRecreationAndDurability() {
        val fakePrefs = FakeSharedPreferences()

        // Process 1: User initiates transfer and it enters uncertain state
        val manager1 = IdempotencyKeyManager(sharedPrefsOverride = fakePrefs)
        val pending1 = manager1.startNewTransfer(
            fromAccountId = "acc-chk-01",
            toAccountId = "acc-sav-02",
            amount = BigDecimal("7500.00"),
            description = "Rent payment"
        )
        val originalKey = pending1.idempotencyKey
        manager1.markReconciliationRequired(originalKey)

        // Process 1 dies (app killed / device restarted). manager1 discarded.

        // Process 2: App restarts, new instance reads from persistent storage
        val manager2 = IdempotencyKeyManager(sharedPrefsOverride = fakePrefs)
        assertTrue("Transfer must remain in-flight after process recreation", manager2.isTransferInFlight())

        val restoredActive = manager2.getActiveTransfer()
        assertNotNull("Active transfer intent must be restored from persistent storage", restoredActive)
        assertEquals(originalKey, restoredActive?.idempotencyKey)
        assertEquals(BigDecimal("7500.00"), restoredActive?.amount)
        assertEquals("INR", restoredActive?.currency)
        assertEquals(PendingTransferStatus.RECONCILIATION_REQUIRED, restoredActive?.status)

        // Verify trying to start a new transfer is blocked because an uncertain transfer is pending
        try {
            manager2.startNewTransfer("acc-chk-01", "acc-sav-02", BigDecimal("1000.00"))
            fail("Cannot start new transfer while uncertain transfer is restored and pending reconciliation")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("already in flight"))
        }

        // Terminal result received: mark COMPLETED
        manager2.updateStatus(originalKey, PendingTransferStatus.COMPLETED)
        assertFalse(manager2.isTransferInFlight())
        assertNull(manager2.getActiveTransfer())

        // Process 3: Recreate again to confirm completed transfer cleared active slot but preserved in history
        val manager3 = IdempotencyKeyManager(sharedPrefsOverride = fakePrefs)
        assertFalse(manager3.isTransferInFlight())
        assertNull(manager3.getActiveTransfer())
        val historical = manager3.getTransfer(originalKey)
        assertNotNull(historical)
        assertEquals(PendingTransferStatus.COMPLETED, historical?.status)
    }

    @Test
    fun testIdempotencyKeyManager_nonSilentDiscardProtection() {
        val manager = IdempotencyKeyManager(inMemoryOnly = true)
        val pending = manager.startNewTransfer("acc-1", "acc-2", BigDecimal("2000.00"))

        // Attempting to silently discard an in-flight transfer must fail
        try {
            manager.clearActiveTransfer(force = false)
            fail("Cannot silently discard in-flight transfer")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Cannot silently discard"))
        }

        manager.markReconciliationRequired(pending.idempotencyKey)
        // Attempting to discard uncertain reconciliation transfer must also fail
        try {
            manager.clearActiveTransfer(force = false)
            fail("Cannot silently discard uncertain transfer pending reconciliation")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Cannot silently discard"))
        }

        // Explicit force cancellation is allowed
        manager.clearActiveTransfer(force = true)
        assertFalse(manager.isTransferInFlight())
        assertNull(manager.getActiveTransfer())
    }

    @Test
    fun testIdempotencyKeyManager_concurrentSimultaneousSubmissions() {
        val manager = IdempotencyKeyManager(inMemoryOnly = true)
        val threadCount = 10
        val successKeys = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val blockedExceptions = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()

        val latch = java.util.concurrent.CountDownLatch(threadCount)
        val threads = (1..threadCount).map { i ->
            Thread {
                try {
                    val p = manager.startNewTransfer("acc-1", "acc-2", BigDecimal("100.00"), "Thread $i")
                    successKeys.add(p.idempotencyKey)
                } catch (t: Throwable) {
                    blockedExceptions.add(t)
                } finally {
                    latch.countDown()
                }
            }
        }

        threads.forEach { it.start() }
        latch.await()

        assertEquals("Exactly one thread must succeed in starting transfer", 1, successKeys.size)
        assertEquals("All other threads must be blocked", threadCount - 1, blockedExceptions.size)
        assertTrue(blockedExceptions.all { it is IllegalStateException && it.message!!.contains("already in flight") })
    }
}

class FakeSharedPreferences : android.content.SharedPreferences {
    private val data = java.util.concurrent.ConcurrentHashMap<String, Any>()

    override fun getAll(): MutableMap<String, *> = HashMap(data)
    override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun getInt(key: String?, defValue: Int): Int = data[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = data[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = data[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = data.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}

    override fun edit(): android.content.SharedPreferences.Editor = FakeEditor()

    inner class FakeEditor : android.content.SharedPreferences.Editor {
        private val pending = HashMap<String, Any?>()
        private var clear = false

        override fun putString(key: String?, value: String?): android.content.SharedPreferences.Editor {
            key?.let { pending[it] = value }
            return this
        }
        override fun putStringSet(key: String?, values: MutableSet<String>?): android.content.SharedPreferences.Editor = this
        override fun putInt(key: String?, value: Int): android.content.SharedPreferences.Editor = this
        override fun putLong(key: String?, value: Long): android.content.SharedPreferences.Editor = this
        override fun putFloat(key: String?, value: Float): android.content.SharedPreferences.Editor = this
        override fun putBoolean(key: String?, value: Boolean): android.content.SharedPreferences.Editor = this
        override fun remove(key: String?): android.content.SharedPreferences.Editor {
            key?.let { pending[it] = null }
            return this
        }
        override fun clear(): android.content.SharedPreferences.Editor {
            clear = true
            return this
        }
        override fun commit(): Boolean {
            apply()
            return true
        }
        override fun apply() {
            if (clear) data.clear()
            pending.forEach { (k, v) ->
                if (v == null) data.remove(k) else data[k] = v
            }
        }
    }
}
