package com.safebank.ai.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.safebank.ai.data.idempotency.IdempotencyKeyManager
import com.safebank.ai.data.model.PendingTransferStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal

/**
 * On-Device Android Instrumentation Test for IdempotencyKeyManager.
 *
 * Verifies real on-device Android SharedPreferences / EncryptedSharedPreferences durability,
 * process restart survival, field preservation, and in-flight duplicate submission blocking.
 */
@RunWith(AndroidJUnit4::class)
class IdempotencyKeyManagerInstrumentationTest {

    private lateinit var targetContext: Context

    @Before
    fun setUp() {
        targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        targetContext.getSharedPreferences(IdempotencyKeyManager.PREFS_FILE, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun testOnDevicePersistence_survivesProcessRecreationAndPreservesFields() {
        // 1. Process 1: Initialize manager with real Android device context
        val managerProcess1 = IdempotencyKeyManager(context = targetContext)
        assertFalse(managerProcess1.isTransferInFlight())
        assertNull(managerProcess1.getActiveTransfer())

        val fromAccount = "00000000-0000-4000-a000-000000000010"
        val toAccount = "00000000-0000-4000-a000-000000000011"
        val amount = BigDecimal("25000.00")
        val description = "Device Instrumentation Transfer"

        // 2. Persist intent atomically before dispatch
        val pending = managerProcess1.startNewTransfer(
            fromAccountId = fromAccount,
            toAccountId = toAccount,
            amount = amount,
            description = description
        )
        val originalKey = pending.idempotencyKey
        assertNotNull(originalKey)

        // Simulate network timeout leaving outcome uncertain
        managerProcess1.markReconciliationRequired(originalKey)

        // 3. Process 2: Simulate process recreation by creating a fresh manager reading from storage
        val managerProcess2 = IdempotencyKeyManager(context = targetContext)

        // 4. Verify all fields restored
        assertTrue(managerProcess2.isTransferInFlight())
        val restored = managerProcess2.getActiveTransfer()
        assertNotNull(restored)

        assertEquals(originalKey, restored?.idempotencyKey)
        assertEquals(fromAccount, restored?.fromAccountId)
        assertEquals(toAccount, restored?.toAccountId)
        assertEquals(amount, restored?.amount)
        assertEquals("INR", restored?.currency)
        assertEquals(description, restored?.description)
        assertEquals(PendingTransferStatus.RECONCILIATION_REQUIRED, restored?.status)

        // 5. Verify new transfer is blocked while outcome remains uncertain
        try {
            managerProcess2.startNewTransfer(fromAccount, toAccount, BigDecimal("100.00"))
            fail("Must block new transfer while uncertain transfer is pending")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("already in flight"))
        }

        // 6. Verify retry uses exact original key and payload
        val retryIntent = managerProcess2.getActiveTransfer()
        assertEquals(originalKey, retryIntent?.idempotencyKey)
        assertEquals(amount, retryIntent?.amount)

        // 7. Cleanup after terminal completion
        managerProcess2.updateStatus(originalKey, PendingTransferStatus.COMPLETED)
        assertFalse(managerProcess2.isTransferInFlight())
    }
}
