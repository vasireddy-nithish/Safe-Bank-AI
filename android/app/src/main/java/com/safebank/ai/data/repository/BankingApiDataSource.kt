package com.safebank.ai.data.repository

import com.google.gson.Gson
import com.safebank.ai.data.model.BankAccount
import com.safebank.ai.data.model.BankingResult
import com.safebank.ai.data.model.IndianCurrencyFormatter
import com.safebank.ai.data.model.ReconcileOutcome
import com.safebank.ai.data.model.ReconcileState
import com.safebank.ai.data.model.TransferReceipt
import com.safebank.ai.data.network.BankingApiService
import com.safebank.ai.data.network.dto.AccountDto
import com.safebank.ai.data.network.dto.ApiErrorDto
import com.safebank.ai.data.network.dto.ReconcileResponseDto
import com.safebank.ai.data.network.dto.TransferRequestDto
import com.safebank.ai.data.network.dto.TransferResponseDto
import retrofit2.Response
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Remote data source communicating directly with the SafeBank Banking REST API.
 */
class BankingApiDataSource(
    private val apiService: BankingApiService,
    private val gson: Gson = Gson()
) {

    /**
     * Fetches user accounts from GET /api/v1/banking/accounts.
     */
    suspend fun getAccounts(): BankingResult<List<BankAccount>> {
        return try {
            val response = apiService.getAccounts()
            if (response.isSuccessful) {
                val dtoList = response.body() ?: emptyList()
                val accounts = dtoList.map { dtoToBankAccount(it) }
                BankingResult.Success(accounts)
            } else {
                parseHttpError(response)
            }
        } catch (e: SocketTimeoutException) {
            BankingResult.Error(
                message = "Account listing request timed out. Please check connection.",
                code = "NETWORK_TIMEOUT",
                isRetryable = true,
                cause = e
            )
        } catch (e: UnknownHostException) {
            BankingResult.Error(
                message = "Unable to resolve server address. Please verify network.",
                code = "NETWORK_UNREACHABLE",
                isRetryable = true,
                cause = e
            )
        } catch (e: IOException) {
            BankingResult.Error(
                message = "Network communication error: ${e.message}",
                code = "NETWORK_IO_ERROR",
                isRetryable = true,
                cause = e
            )
        } catch (e: Throwable) {
            BankingResult.Error(
                message = "Unexpected error fetching accounts: ${e.message}",
                code = "UNEXPECTED_ERROR",
                isRetryable = false,
                cause = e
            )
        }
    }

    /**
     * Executes transfer via POST /api/v1/banking/transfers.
     * Note: Network exceptions (timeout, connection drop) are thrown so the repository
     * can orchestrate immediate reconciliation with the original idempotency key.
     */
    suspend fun executeTransfer(
        idempotencyKey: String,
        fromAccountId: String,
        toAccountId: String,
        amount: BigDecimal,
        description: String? = null
    ): BankingResult<TransferReceipt> {
        val requestDto = TransferRequestDto(
            fromAccountId = fromAccountId,
            toAccountId = toAccountId,
            amount = IndianCurrencyFormatter.toApiString(amount),
            currency = "INR",
            description = description
        )

        val response = apiService.executeTransfer(idempotencyKey, requestDto)
        return if (response.isSuccessful) {
            val body = response.body() ?: throw IllegalStateException("Empty response body from transfer API")
            val receipt = dtoToTransferReceipt(body, idempotencyKey, fromAccountId, toAccountId, amount)
            BankingResult.Success(receipt)
        } else {
            parseHttpError(response)
        }
    }

    /**
     * Reconciles transfer status via GET /api/v1/banking/transfers/reconcile/{idempotency_key}.
     */
    suspend fun reconcileTransfer(idempotencyKey: String): BankingResult<ReconcileOutcome> {
        return try {
            val response = apiService.reconcileTransfer(idempotencyKey)
            when (response.code()) {
                200 -> {
                    val body = response.body() ?: throw IllegalStateException("Empty response from reconcile")
                    val outcome = dtoToReconcileOutcome(body)
                    BankingResult.Success(outcome)
                }
                202 -> {
                    val body = response.body() ?: ReconcileResponseDto(
                        state = "PROCESSING",
                        success = false,
                        retryable = true,
                        idempotencyKey = idempotencyKey,
                        detail = "Transfer is processing on server"
                    )
                    val outcome = dtoToReconcileOutcome(body)
                    BankingResult.Success(outcome)
                }
                404 -> {
                    val outcome = ReconcileOutcome(
                        state = ReconcileState.NOT_FOUND,
                        success = false,
                        retryable = true,
                        idempotencyKey = idempotencyKey,
                        detail = "No transaction or reservation found for this idempotency key"
                    )
                    BankingResult.Success(outcome)
                }
                else -> {
                    parseHttpError(response)
                }
            }
        } catch (e: SocketTimeoutException) {
            BankingResult.Error(
                message = "Reconciliation request timed out.",
                code = "RECONCILE_TIMEOUT",
                isRetryable = true,
                cause = e
            )
        } catch (e: IOException) {
            BankingResult.Error(
                message = "Network error during reconciliation: ${e.message}",
                code = "RECONCILE_NETWORK_ERROR",
                isRetryable = true,
                cause = e
            )
        } catch (e: Throwable) {
            BankingResult.Error(
                message = "Unexpected error during reconciliation: ${e.message}",
                code = "RECONCILE_ERROR",
                isRetryable = false,
                cause = e
            )
        }
    }

    private fun dtoToBankAccount(dto: AccountDto): BankAccount {
        val balance = BigDecimal(dto.balance).setScale(2, RoundingMode.HALF_UP)
        return BankAccount(
            id = dto.id,
            maskedAccountNumber = dto.maskedAccountNumber,
            accountType = dto.accountType,
            balance = balance,
            currency = dto.currency,
            status = dto.status,
            isPrimary = dto.isPrimary
        )
    }

    private fun dtoToTransferReceipt(
        dto: TransferResponseDto,
        fallbackKey: String,
        fromAccountId: String,
        toAccountId: String,
        requestedAmount: BigDecimal
    ): TransferReceipt {
        val amount = if (!dto.amount.isNullOrBlank()) {
            BigDecimal(dto.amount).setScale(2, RoundingMode.HALF_UP)
        } else {
            requestedAmount
        }

        return TransferReceipt(
            transactionId = dto.transactionId ?: "tx-unknown",
            referenceNumber = dto.referenceNumber ?: "REF-UNKNOWN",
            status = dto.status ?: "completed",
            fromAccountId = dto.fromAccountId ?: fromAccountId,
            toAccountId = dto.toAccountId ?: toAccountId,
            amount = amount,
            currency = dto.currency ?: "INR",
            idempotencyKey = dto.idempotencyKey ?: fallbackKey,
            completedAt = dto.completedAt ?: java.time.Instant.now().toString(),
            message = dto.message ?: "Transfer completed successfully"
        )
    }

    private fun dtoToReconcileOutcome(dto: ReconcileResponseDto): ReconcileOutcome {
        val state = try {
            ReconcileState.valueOf(dto.state.uppercase())
        } catch (e: Exception) {
            ReconcileState.PROCESSING
        }

        val amount = dto.amount?.let {
            try { BigDecimal(it).setScale(2, RoundingMode.HALF_UP) } catch (e: Exception) { null }
        }

        val receipt = if (state == ReconcileState.COMPLETED && dto.transactionId != null) {
            TransferReceipt(
                transactionId = dto.transactionId,
                referenceNumber = dto.referenceNumber ?: "REF-REC",
                status = dto.status ?: "completed",
                fromAccountId = "",
                toAccountId = "",
                amount = amount ?: BigDecimal.ZERO,
                currency = dto.currency ?: "INR",
                idempotencyKey = dto.idempotencyKey,
                completedAt = dto.completedAt ?: java.time.Instant.now().toString(),
                message = "Reconciled completed transfer"
            )
        } else null

        return ReconcileOutcome(
            state = state,
            success = dto.success,
            retryable = dto.retryable,
            idempotencyKey = dto.idempotencyKey,
            transactionId = dto.transactionId,
            referenceNumber = dto.referenceNumber,
            status = dto.status,
            amount = amount,
            currency = dto.currency,
            completedAt = dto.completedAt,
            receipt = receipt,
            detail = dto.detail,
            errorCode = dto.errorCode
        )
    }

    private fun <T> parseHttpError(response: Response<*>): BankingResult<T> {
        val statusCode = response.code()
        val errorJson = try {
            response.errorBody()?.string()
        } catch (e: Exception) {
            null
        }

        var detailMessage = "Server returned HTTP $statusCode"
        var isRetryable = false
        var errorCode: String? = null

        if (!errorJson.isNullOrBlank()) {
            try {
                val errorDto = gson.fromJson(errorJson, ApiErrorDto::class.java)
                if (errorDto != null) {
                    detailMessage = errorDto.getDetailMessage()
                    isRetryable = errorDto.retryable ?: (statusCode == 503)
                }
            } catch (e: Exception) {
                detailMessage = errorJson
            }
        }

        when (statusCode) {
            400 -> errorCode = errorCode ?: "BAD_REQUEST"
            401 -> {
                errorCode = "UNAUTHORIZED"
                detailMessage = "Session expired or invalid. Please re-authenticate."
                isRetryable = false
            }
            403 -> errorCode = "FORBIDDEN"
            404 -> errorCode = "NOT_FOUND"
            409 -> errorCode = "IDEMPOTENCY_CONFLICT"
            422 -> errorCode = errorCode ?: "VALIDATION_OR_LIMIT_ERROR"
            503 -> {
                errorCode = "SERVICE_UNAVAILABLE_RETRYABLE"
                isRetryable = true
            }
            500 -> errorCode = "INTERNAL_SERVER_ERROR"
        }

        return BankingResult.Error(
            message = detailMessage,
            code = errorCode,
            isRetryable = isRetryable,
            httpStatus = statusCode
        )
    }
}
