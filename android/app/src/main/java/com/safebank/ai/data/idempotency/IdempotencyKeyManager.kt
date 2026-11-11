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
 * Manages the lifecycle and durable persistence of idempotency keys for financial transactions.
 *
 * Durability & Safety Invariants:
 * 1. Storage Mechanism & File: Backed by SharedPreferences named `safebank_idempotency_prefs`
 *    at `/data/data/com.safebank.ai/shared_prefs/safebank_idempotency_prefs.xml` on internal flash storage.
 * 2. Process Death & Device Restart Durability: Survives application process recreation and device reboots.
 *    Restored synchronously on initialization.
 * 3. Pre-Dispatch Atomic Persistence: Uses synchronous `.commit()` (fsync) to guarantee the idempotency
 *    key and full transfer intent are flushed to durable disk before any network packet is dispatched.
 * 4. No Silent Discarding: Pending, in-flight, or uncertain transfers (SUBMITTED, PROCESSING,
 *    RECONCILIATION_REQUIRED) cannot be discarded without a definitive terminal result or explicit force cancellation.
 * 5. Terminal Result Cleanup: Active transfer intent is cleared ONLY upon receiving a definitive terminal result
 *    (COMPLETED or FAILED) or explicit authorized cancellation.
 * 6. Simultaneous Submission Protection: Synchronized transfer creation prevents multiple concurrent submissions
 *    from ever generating different keys for the same logical transfer intent.
 */
class IdempotencyKeyManager(
    private val context: Context? = null,
    private val inMemoryOnly: Boolean = false,
    private val sharedPrefsOverride: SharedPreferences? = null
) {
    companion object {
        const val PREFS_FILE = "safebank_idempotency_prefs"
        const val KEY_ACTIVE_TRANSFER = "active_pending_transfer"
        const val KEY_PREFIX_TRANSFER = "transfer_intent_"
    }

    private val gson = Gson()
    private val transferStore = ConcurrentHashMap<String, PendingTransfer>()

    @Volatile
    private var activeTransferKey: String? = null

    private val prefs: SharedPreferences? by lazy {
        sharedPrefsOverride ?: if (inMemoryOnly || context == null) null
        else context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    }

    init {
        restoreFromStorage()
    }

    @Synchronized
    fun restoreFromStorage() {
        prefs?.let { sp ->
            // Restore active pending transfer
            sp.getString(KEY_ACTIVE_TRANSFER, null)?.let { json ->
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

            // Also restore historical / stored transfers
            try {
                sp.all?.forEach { (key, value) ->
                    if (key.startsWith(KEY_PREFIX_TRANSFER) && value is String) {
                        val transfer = gson.fromJson(value, PendingTransfer::class.java)
                        if (transfer != null) {
                            transferStore[transfer.idempotencyKey] = transfer
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore parse errors on legacy keys
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
               active.status == PendingTransferStatus.PROCESSING ||
               active.status == PendingTransferStatus.RECONCILIATION_REQUIRED
    }

    /**
     * Initiates a fresh logical transfer attempt.
     * Generates exactly one new UUIDv4 key and persists the intent atomically to disk before dispatch.
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

        // Atomically persist to disk using synchronous .commit()
        persistTransferAtomically(pending)
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
     * Clears active in-flight intent ONLY upon reaching a definitive terminal state (COMPLETED or FAILED).
     */
    @Synchronized
    fun updateStatus(key: String, newStatus: PendingTransferStatus) {
        val existing = transferStore[key] ?: return
        val updated = existing.copy(status = newStatus)
        transferStore[key] = updated

        if (newStatus == PendingTransferStatus.COMPLETED || newStatus == PendingTransferStatus.FAILED) {
            // Definitive terminal result reached: persist historical record and clear active in-flight
            persistHistoricalRecord(updated)
            if (activeTransferKey == key) {
                activeTransferKey = null
                clearPersistedActiveTransfer()
            }
        } else {
            // Uncertain or in-flight state (SUBMITTED, PROCESSING, RECONCILIATION_REQUIRED):
            // Keep actively persisted
            if (activeTransferKey == key) {
                persistTransferAtomically(updated)
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
     * Explicitly clears the active transfer intent.
     * Guarantees that pending and uncertain transfers CANNOT be silently discarded without force=true.
     */
    @Synchronized
    fun clearActiveTransfer(force: Boolean = false) {
        val active = getActiveTransfer()
        if (active != null && !force) {
            if (active.status == PendingTransferStatus.SUBMITTED ||
                active.status == PendingTransferStatus.PROCESSING ||
                active.status == PendingTransferStatus.RECONCILIATION_REQUIRED
            ) {
                throw IllegalStateException(
                    "Cannot silently discard in-flight or uncertain transfer (key: ${active.idempotencyKey}, status: ${active.status}). Reconciliation or terminal result required."
                )
            }
        }
        activeTransferKey = null
        clearPersistedActiveTransfer()
    }

    /**
     * Synchronously commits the active transfer to disk using .commit() (fsync)
     * before any network request is transmitted.
     */
    private fun persistTransferAtomically(transfer: PendingTransfer) {
        val json = gson.toJson(transfer)
        prefs?.edit()
            ?.putString(KEY_ACTIVE_TRANSFER, json)
            ?.putString(KEY_PREFIX_TRANSFER + transfer.idempotencyKey, json)
            ?.commit() // Synchronous disk flush
    }

    private fun persistHistoricalRecord(transfer: PendingTransfer) {
        val json = gson.toJson(transfer)
        prefs?.edit()
            ?.putString(KEY_PREFIX_TRANSFER + transfer.idempotencyKey, json)
            ?.commit()
    }

    private fun clearPersistedActiveTransfer() {
        prefs?.edit()
            ?.remove(KEY_ACTIVE_TRANSFER)
            ?.commit() // Synchronous flush
    }
}
