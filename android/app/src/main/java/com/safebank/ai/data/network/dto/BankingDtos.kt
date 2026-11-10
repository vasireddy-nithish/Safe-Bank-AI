package com.safebank.ai.data.network.dto

import com.google.gson.annotations.SerializedName

/**
 * Account DTO matching backend GET /api/v1/banking/accounts
 */
data class AccountDto(
    @SerializedName("id")
    val id: String,

    @SerializedName("masked_account_number")
    val maskedAccountNumber: String,

    @SerializedName("account_type")
    val accountType: String,

    @SerializedName("balance")
    val balance: String,

    @SerializedName("currency")
    val currency: String,

    @SerializedName("status")
    val status: String,

    @SerializedName("is_primary")
    val isPrimary: Boolean
)

/**
 * Transfer request DTO matching backend POST /api/v1/banking/transfers
 */
data class TransferRequestDto(
    @SerializedName("from_account_id")
    val fromAccountId: String,

    @SerializedName("to_account_id")
    val toAccountId: String,

    @SerializedName("amount")
    val amount: String,

    @SerializedName("currency")
    val currency: String = "INR",

    @SerializedName("description")
    val description: String? = null
)

/**
 * Transfer response DTO matching backend POST /api/v1/banking/transfers
 */
data class TransferResponseDto(
    @SerializedName("success")
    val success: Boolean,

    @SerializedName("transaction_id")
    val transactionId: String? = null,

    @SerializedName("reference_number")
    val referenceNumber: String? = null,

    @SerializedName("status")
    val status: String? = null,

    @SerializedName("from_account_id")
    val fromAccountId: String? = null,

    @SerializedName("to_account_id")
    val toAccountId: String? = null,

    @SerializedName("amount")
    val amount: String? = null,

    @SerializedName("currency")
    val currency: String? = null,

    @SerializedName("idempotency_key")
    val idempotencyKey: String? = null,

    @SerializedName("completed_at")
    val completedAt: String? = null,

    @SerializedName("message")
    val message: String? = null
)

/**
 * Transfer reconciliation response DTO matching GET /api/v1/banking/transfers/reconcile/{idempotency_key}
 */
data class ReconcileResponseDto(
    @SerializedName("state")
    val state: String,

    @SerializedName("success")
    val success: Boolean,

    @SerializedName("retryable")
    val retryable: Boolean = false,

    @SerializedName("idempotency_key")
    val idempotencyKey: String,

    @SerializedName("transaction_id")
    val transactionId: String? = null,

    @SerializedName("reference_number")
    val referenceNumber: String? = null,

    @SerializedName("status")
    val status: String? = null,

    @SerializedName("amount")
    val amount: String? = null,

    @SerializedName("currency")
    val currency: String? = null,

    @SerializedName("completed_at")
    val completedAt: String? = null,

    @SerializedName("detail")
    val detail: String? = null,

    @SerializedName("error_code")
    val errorCode: String? = null
)

/**
 * Structured API Error DTO parsed from error response body
 */
data class ApiErrorDto(
    @SerializedName("detail")
    val detail: Any? = null,

    @SerializedName("retryable")
    val retryable: Boolean? = null,

    @SerializedName("idempotency_key")
    val idempotencyKey: String? = null
) {
    fun getDetailMessage(): String {
        return when (detail) {
            is String -> detail
            is List<*> -> detail.joinToString("; ") { it.toString() }
            null -> "Unknown error"
            else -> detail.toString()
        }
    }
}
