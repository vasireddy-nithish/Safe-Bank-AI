package com.safebank.ai.data.idempotency

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.safebank.ai.data.model.PendingTransfer
import com.safebank.ai.data.model.PendingTransferStatus
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages the lifecycle of idempotency keys for financial transactions.
 *
 * Invariants:
 * 1. Exactly one UUIDv4 idempotency key per logical transfer attempt.
 * 2. Persisted alongside the pending transfer intent before network transmission.
 * 3. On network timeout or connection drop, the SAME key is preserved for retry/reconciliation.
 * 4. A new key is generated ONLY when the user explicitly initiates a new logical transfer.
 * 5. Prevents duplicate concurrent transfer submissions from the UI while one is in flight.
 */
class IdempotencyKeyManager(
    private val context: Context? = null,
    private val inMemoryOnly: Boolean = false
) {
    companion object {
        private const val PREFS_FILE = "safebank_idempotency_prefs"
        private const val KEY_ACTIVE_TRANSFER = "active_pending_transfer"
    }

    private val gson = Gson()
    private val transferStore = ConcurrentHashMap<String, PendingTransfer>()

    @Volatile
    private var activeTransferKey: String? = null

    private val prefs: SharedPreferences? by lazy {
        if (inMemoryOnly || context == null) null
        else context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    }

    init {
        // Restore active pending transfer from disk if available
        prefs?.getString(KEY_ACTIVE_TRANSFER, null)?.let { json ->
            try {
                val stored = gson.fromJson(json, PendingTransfer::class.java)
                if (stored != null) {
                    transferStore[stored.idempotencyKey] = stored
                    if (stored.status == PendingTransferStatus.SUBMITTED ||
                        stored.status == PendingTransferStatus.PROCESSING ||
                        stored.status == PendingTransferStatus.RECONCILIATION_REQUIRED
                    ) {
                        activeTransferKey = stored.idempotencyKey
                    }
                }
            } catch (e: Exception) {
                // Ignore parse errors on corrupted state
            }
        }
    }

    /**
     * Checks if a transfer is currently in flight to prevent duplicate submissions.
     */
    @Synchronized
    fun isTransferInFlight(): Boolean {
        val key = activeTransferKey ?: return false
        val active = transferStore[key] ?: return false
        return active.status == PendingTransferStatus.SUBMITTED ||
               active.status == PendingTransferStatus.PROCESSING
    }

    /**
     * Initiates a fresh logical transfer attempt.
     * Generates exactly one new UUIDv4 key and persists the intent.
     * Throws IllegalStateException if a transfer is already in flight.
     */
    @Synchronized
    fun startNewTransfer(
        fromAccountId: String,
        toAccountId: String,
        amount: BigDecimal,
        description: String? = null
    ): PendingTransfer {
        if (isTransferInFlight()) {
            throw IllegalStateException(
                "A transfer is already in flight (key: $activeTransferKey). Duplicate submission blocked."
            )
        }

        val key = UUID.randomUUID().toString()
        val pending = PendingTransfer(
            idempotencyKey = key,
            fromAccountId = fromAccountId,
            toAccountId = toAccountId,
            amount = amount,
            currency = "INR",
            description = description,
            createdAtTimestamp = System.currentTimeMillis(),
            status = PendingTransferStatus.SUBMITTED
        )

        transferStore[key] = pending
        activeTransferKey = key
        persistActiveTransfer(pending)
        return pending
    }

    /**
     * Retrieves the current active pending transfer, if any.
     */
    @Synchronized
    fun getActiveTransfer(): PendingTransfer? {
        val key = activeTransferKey ?: return null
        return transferStore[key]
    }

    /**
     * Retrieves a stored transfer by idempotency key.
     */
    fun getTransfer(key: String): PendingTransfer? {
        return transferStore[key]
    }

    /**
     * Updates the status of an existing transfer intent.
     */
    @Synchronized
    fun updateStatus(key: String, newStatus: PendingTransferStatus) {
        val existing = transferStore[key] ?: return
        val updated = existing.copy(status = newStatus)
        transferStore[key] = updated

        if (newStatus == PendingTransferStatus.COMPLETED || newStatus == PendingTransferStatus.FAILED) {
            if (activeTransferKey == key) {
                activeTransferKey = null
                clearPersistedActiveTransfer()
            }
        } else {
            if (activeTransferKey == key) {
                persistActiveTransfer(updated)
            }
        }
    }

    /**
     * Explicitly marks that reconciliation is required for this transfer
     * because of an uncertain network outcome (timeout / dropped connection).
     */
    @Synchronized
    fun markReconciliationRequired(key: String) {
        updateStatus(key, PendingTransferStatus.RECONCILIATION_REQUIRED)
    }

    /**
     * Explicitly clears the active transfer intent (e.g. after terminal error or user discard).
     */
    @Synchronized
    fun clearActiveTransfer() {
        activeTransferKey = null
        clearPersistedActiveTransfer()
    }

    private fun persistActiveTransfer(transfer: PendingTransfer) {
        prefs?.edit()?.putString(KEY_ACTIVE_TRANSFER, gson.toJson(transfer))?.apply()
    }

    private fun clearPersistedActiveTransfer() {
        prefs?.edit()?.remove(KEY_ACTIVE_TRANSFER)?.apply()
    }
}
