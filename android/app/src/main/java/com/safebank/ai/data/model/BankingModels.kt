package com.safebank.ai.data.model

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Bank Account domain model with strict financial precision.
 * Float and Double are strictly forbidden for monetary amounts.
 */
data class BankAccount(
    val id: String,
    val maskedAccountNumber: String,
    val accountType: String,
    val balance: BigDecimal,
    val currency: String = "INR",
    val status: String = "active",
    val isPrimary: Boolean = false
) {
    init {
        require(currency == "INR") { "Currency must be strictly INR, found: $currency" }
        require(balance >= BigDecimal.ZERO) { "Account balance cannot be negative" }
    }

    val formattedBalance: String
        get() = IndianCurrencyFormatter.formatInr(balance)
}

/**
 * Transfer execution receipt returned upon successful transaction completion.
 */
data class TransferReceipt(
    val transactionId: String,
    val referenceNumber: String,
    val status: String,
    val fromAccountId: String,
    val toAccountId: String,
    val amount: BigDecimal,
    val currency: String = "INR",
    val idempotencyKey: String,
    val completedAt: String,
    val message: String = "Transfer completed successfully"
) {
    init {
        require(currency == "INR") { "Currency must be strictly INR, found: $currency" }
        require(amount > BigDecimal.ZERO) { "Transfer amount must be positive" }
    }

    val formattedAmount: String
        get() = IndianCurrencyFormatter.formatInr(amount)
}

/**
 * Reconciliation states matching backend state machine.
 */
enum class ReconcileState {
    COMPLETED,
    PROCESSING,
    FAILED,
    NOT_FOUND
}

/**
 * Outcome of querying the reconciliation endpoint.
 */
data class ReconcileOutcome(
    val state: ReconcileState,
    val success: Boolean,
    val retryable: Boolean,
    val idempotencyKey: String,
    val transactionId: String? = null,
    val referenceNumber: String? = null,
    val status: String? = null,
    val amount: BigDecimal? = null,
    val currency: String? = null,
    val completedAt: String? = null,
    val receipt: TransferReceipt? = null,
    val detail: String? = null,
    val errorCode: String? = null
)

/**
 * Repository operational mode.
 * Switching between modes is explicit. Silent fallback is strictly forbidden.
 */
enum class RepositoryMode {
    DEMO_MOCK,
    BACKEND_CONNECTED
}

/**
 * Status of an in-flight or recorded transfer intent.
 */
enum class PendingTransferStatus {
    SUBMITTED,
    PROCESSING,
    COMPLETED,
    FAILED,
    RECONCILIATION_REQUIRED
}

/**
 * Locally persisted pending transfer intent for idempotency safety.
 * Exactly one UUIDv4 idempotency key per logical transfer attempt.
 */
data class PendingTransfer(
    val idempotencyKey: String,
    val fromAccountId: String,
    val toAccountId: String,
    val amount: BigDecimal,
    val currency: String = "INR",
    val description: String? = null,
    val createdAtTimestamp: Long = System.currentTimeMillis(),
    val status: PendingTransferStatus = PendingTransferStatus.SUBMITTED
)

/**
 * Typed Result for ViewModel consumption.
 */
sealed class BankingResult<out T> {
    data class Success<out T>(val data: T) : BankingResult<T>()
    data class Error(
        val message: String,
        val code: String? = null,
        val isRetryable: Boolean = false,
        val httpStatus: Int? = null,
        val cause: Throwable? = null
    ) : BankingResult<Nothing>()
    data class Processing(
        val idempotencyKey: String,
        val message: String = "Transfer is processing on server"
    ) : BankingResult<Nothing>()
}

/**
 * Utility for Indian Rupee (INR) formatting with the Indian numbering system
 * (e.g., ₹1,24,500.80 instead of Western $124,500.80).
 */
object IndianCurrencyFormatter {

    /**
     * Formats BigDecimal amount into Indian Rupee representation (e.g. ₹1,24,500.80).
     */
    fun formatInr(amount: BigDecimal): String {
        val scaled = amount.setScale(2, RoundingMode.HALF_UP)
        val plain = scaled.toPlainString()
        val parts = plain.split(".")
        val integerPart = parts[0]
        val fractionPart = if (parts.size > 1) parts[1] else "00"

        val formattedInt = formatIndianInteger(integerPart)
        return "₹$formattedInt.$fractionPart"
    }

    private fun formatIndianInteger(digits: String): String {
        val isNegative = digits.startsWith("-")
        val cleanDigits = if (isNegative) digits.substring(1) else digits

        if (cleanDigits.length <= 3) {
            return if (isNegative) "-$cleanDigits" else cleanDigits
        }

        val lastThree = cleanDigits.takeLast(3)
        val remaining = cleanDigits.dropLast(3)

        val sb = StringBuilder()
        var i = remaining.length
        while (i > 0) {
            val start = maxOf(0, i - 2)
            val chunk = remaining.substring(start, i)
            if (sb.isNotEmpty()) {
                sb.insert(0, ",")
            }
            sb.insert(0, chunk)
            i -= 2
        }

        val result = "$sb,$lastThree"
        return if (isNegative) "-$result" else result
    }

    /**
     * Converts a BigDecimal amount to an exact 2-decimal plain string for API requests.
     */
    fun toApiString(amount: BigDecimal): String {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString()
    }

    /**
     * Safely parses a decimal string into a BigDecimal with 2 decimal places.
     * Rejects invalid formats, negative numbers, or non-numeric strings.
     */
    fun parseAmount(value: String): BigDecimal? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        return try {
            val bd = BigDecimal(trimmed)
            if (bd <= BigDecimal.ZERO) null else bd.setScale(2, RoundingMode.HALF_UP)
        } catch (e: Exception) {
            null
        }
    }
}
