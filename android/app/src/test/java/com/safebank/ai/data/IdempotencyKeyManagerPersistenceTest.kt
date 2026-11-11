package com.safebank.ai.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.safebank.ai.data.idempotency.IdempotencyKeyManager
import com.safebank.ai.data.idempotency.PersistenceException
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
import org.robolectric.RobolectricTestRunner
import java.math.BigDecimal

/**
 * Persistence & Durability test using REAL Android Framework SharedPreferences
 * (executing via Robolectric on Android bytecode).
 */
@RunWith(RobolectricTestRunner::class)
class IdempotencyKeyManagerPersistenceTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Clean out any existing SharedPreferences file before each test
        context.getSharedPreferences(IdempotencyKeyManager.PREFS_FILE, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun testRealSharedPreferences_processRecreationAndFieldDurability() {
        // Phase 1: Process 1 initializes with real Android context
        val managerProcess1 = IdempotencyKeyManager(context = context, useEncryptionIfAvailable = false)
        assertFalse(managerProcess1.isTransferInFlight())
        assertNull(managerProcess1.getActiveTransfer())

        val fromAccount = "00000000-0000-4000-a000-000000000010"
        val toAccount = "00000000-0000-4000-a000-000000000011"
        val amount = BigDecimal("12450.80")
        val description = "Medical bill settlement"

        // 1. Persist a transfer intent atomically before dispatch
        val pending = managerProcess1.startNewTransfer(
            fromAccountId = fromAccount,
            toAccountId = toAccount,
            amount = amount,
            description = description
        )
        val originalKey = pending.idempotencyKey
        assertNotNull(originalKey)
        assertTrue(managerProcess1.isTransferInFlight())

        // Simulate network timeout: mark RECONCILIATION_REQUIRED
        managerProcess1.markReconciliationRequired(originalKey)

        // 2. Terminate Process 1 completely: discard managerProcess1 instance from memory

        // 3. Process 2: App process restarts / device reboots. New instance reads real Android SharedPreferences
        val managerProcess2 = IdempotencyKeyManager(context = context, useEncryptionIfAvailable = false)

        // 4. Verify the original idempotency key, all four financial fields, and transfer status are restored
        assertTrue("Transfer must remain in-flight after process recreation", managerProcess2.isTransferInFlight())
        val restored = managerProcess2.getActiveTransfer()
        assertNotNull("Pending intent must be restored from real storage", restored)

        assertEquals("Original idempotency key must be preserved exactly", originalKey, restored?.idempotencyKey)
        assertEquals("Source account must match exactly", fromAccount, restored?.fromAccountId)
        assertEquals("Destination account must match exactly", toAccount, restored?.toAccountId)
        assertEquals("Amount must match with exact BigDecimal precision", amount, restored?.amount)
        assertEquals("Currency must be INR", "INR", restored?.currency)
        assertEquals("Description must match", description, restored?.description)
        assertEquals("Status must remain RECONCILIATION_REQUIRED", PendingTransferStatus.RECONCILIATION_REQUIRED, restored?.status)

        // 5. Verify that a new transfer is blocked while the original outcome remains uncertain
        try {
            managerProcess2.startNewTransfer(fromAccount, toAccount, BigDecimal("500.00"))
            fail("Must block new transfer submission while previous transfer outcome is uncertain")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("already in flight"))
            assertTrue(e.message!!.contains(originalKey))
        }

        // 6. Verify that retry uses the exact original key and payload
        val retryTransfer = managerProcess2.getActiveTransfer()
        assertEquals(originalKey, retryTransfer?.idempotencyKey)
        assertEquals(fromAccount, retryTransfer?.fromAccountId)
        assertEquals(toAccount, retryTransfer?.toAccountId)
        assertEquals(amount, retryTransfer?.amount)
        assertEquals("INR", retryTransfer?.currency)

        // 7. Complete the transfer upon terminal result
        managerProcess2.updateStatus(originalKey, PendingTransferStatus.COMPLETED)
        assertFalse(managerProcess2.isTransferInFlight())
        assertNull(managerProcess2.getActiveTransfer())

        // Process 3: Recreate again after completion
        val managerProcess3 = IdempotencyKeyManager(context = context, useEncryptionIfAvailable = false)
        assertFalse(managerProcess3.isTransferInFlight())
        assertNull(managerProcess3.getActiveTransfer())

        // Historical record still preserved in storage
        val historical = managerProcess3.getTransfer(originalKey)
        assertNotNull(historical)
        assertEquals(PendingTransferStatus.COMPLETED, historical?.status)
    }

    @Test
    fun testRealSharedPreferences_commitFailureBlocksDispatch() {
        // Create SharedPreferences that returns false on commit()
        val failingEditor = object : android.content.SharedPreferences.Editor {
            override fun putString(key: String?, value: String?) = this
            override fun putStringSet(key: String?, values: MutableSet<String>?) = this
            override fun putInt(key: String?, value: Int) = this
            override fun putLong(key: String?, value: Long) = this
            override fun putFloat(key: String?, value: Float) = this
            override fun putBoolean(key: String?, value: Boolean) = this
            override fun remove(key: String?) = this
            override fun clear() = this
            override fun apply() {}
            override fun commit(): Boolean = false // Simulate commit failure (disk full / IO error)
        }

        val failingPrefs = object : android.content.SharedPreferences {
            override fun getAll(): MutableMap<String, *> = HashMap<String, Any>()
            override fun getString(key: String?, defValue: String?): String? = defValue
            override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
            override fun getInt(key: String?, defValue: Int): Int = defValue
            override fun getLong(key: String?, defValue: Long): Long = defValue
            override fun getFloat(key: String?, defValue: Float): Float = defValue
            override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
            override fun contains(key: String?): Boolean = false
            override fun edit(): android.content.SharedPreferences.Editor = failingEditor
            override fun registerOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
            override fun unregisterOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
        }

        val manager = IdempotencyKeyManager(sharedPrefsOverride = failingPrefs)

        try {
            manager.startNewTransfer("acc-1", "acc-2", BigDecimal("100.00"))
            fail("startNewTransfer must throw PersistenceException when commit() returns false")
        } catch (e: PersistenceException) {
            assertTrue(e.message!!.contains("Atomic persistence failed"))
        }

        assertFalse("No transfer must be marked in-flight if persistence failed", manager.isTransferInFlight())
        assertNull("Active transfer must remain null if persistence failed", manager.getActiveTransfer())
    }
}
