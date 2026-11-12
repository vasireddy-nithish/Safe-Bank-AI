package com.safebank.ai.ui.screens.transfers

import android.content.Context
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.safebank.ai.data.di.BankingRepositoryProvider
import com.safebank.ai.data.model.BankAccount
import com.safebank.ai.data.model.BankingResult
import com.safebank.ai.data.model.IndianCurrencyFormatter
import com.safebank.ai.data.model.ReconcileState
import com.safebank.ai.data.model.RepositoryMode
import com.safebank.ai.data.model.TransferReceipt
import com.safebank.ai.data.network.BankingApiClient
import com.safebank.ai.data.network.RiskSubmissionRequest
import com.safebank.ai.data.network.StepUpRequest
import com.safebank.ai.data.repository.BankingRepository
import com.safebank.ai.data.security.SecureSessionManager
import com.safebank.ai.data.state.TransferSubmissionUiState
import com.safebank.ai.ml.BehaviourSessionManager
import com.safebank.ai.security.BiometricStepUp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal

data class TransfersUiState(
    val availableAccounts: List<BankAccount> = emptyList(),
    val sourceAccount: BankAccount? = null,
    val destinationAccount: BankAccount? = null,
    val amountInput: String = "",
    val parsedAmount: BigDecimal? = null,
    val note: String = "",
    val amountError: String? = null,
    val accountError: String? = null,
    val isReviewing: Boolean = false,
    val submissionState: TransferSubmissionUiState = TransferSubmissionUiState.Idle,
    val riskScore: Int = 14,
    val isModeMock: Boolean = false
)

class TransfersViewModel(
    private val repository: BankingRepository = BankingRepositoryProvider.getRepository()
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        TransfersUiState(isModeMock = repository.getRepositoryMode() == RepositoryMode.DEMO_MOCK)
    )
    val uiState: StateFlow<TransfersUiState> = _uiState.asStateFlow()

    init {
        loadAccounts()
    }

    fun loadAccounts() {
        viewModelScope.launch {
            val isMock = repository.getRepositoryMode() == RepositoryMode.DEMO_MOCK
            _uiState.update { it.copy(isModeMock = isMock) }

            when (val result = repository.getAccounts()) {
                is BankingResult.Success -> {
                    val accounts = result.data
                    val source = accounts.find { it.isPrimary } ?: accounts.firstOrNull()
                    val dest = accounts.find { it.id != source?.id }
                    _uiState.update {
                        it.copy(
                            availableAccounts = accounts,
                            sourceAccount = it.sourceAccount ?: source,
                            destinationAccount = it.destinationAccount ?: dest,
                            accountError = if (accounts.size < 2) "At least 2 accounts are required for internal transfers." else null
                        )
                    }
                }
                is BankingResult.Error -> {
                    _uiState.update { it.copy(accountError = result.message) }
                }
                is BankingResult.Processing -> {}
            }
        }
    }

    fun onSourceAccountSelected(account: BankAccount) {
        _uiState.update { current ->
            val newDest = if (current.destinationAccount?.id == account.id) {
                current.availableAccounts.find { it.id != account.id }
            } else {
                current.destinationAccount
            }
            current.copy(sourceAccount = account, destinationAccount = newDest, accountError = null)
        }
    }

    fun onDestinationAccountSelected(account: BankAccount) {
        _uiState.update { current ->
            current.copy(destinationAccount = account, accountError = null)
        }
    }

    fun onAmountChanged(value: String) {
        val filtered = value.filter { it.isDigit() || it == '.' }
        val parsed = IndianCurrencyFormatter.parseAmount(filtered)
        _uiState.update {
            it.copy(
                amountInput = filtered,
                parsedAmount = parsed,
                amountError = null
            )
        }
    }

    fun onNoteChanged(value: String) {
        _uiState.update { it.copy(note = value) }
    }

    fun onReviewTransfer() {
        val current = _uiState.value
        val source = current.sourceAccount
        val dest = current.destinationAccount
        val amount = current.parsedAmount

        var hasError = false
        var accErr: String? = null
        var amtErr: String? = null

        if (source == null || dest == null) {
            accErr = "Both source and destination accounts are required."
            hasError = true
        } else if (source.id == dest.id) {
            accErr = "Source and destination accounts must be distinct."
            hasError = true
        }

        if (amount == null || amount <= BigDecimal.ZERO) {
            amtErr = "Enter a valid positive transfer amount in INR (₹)."
            hasError = true
        } else if (source != null && amount > source.balance) {
            amtErr = "Amount exceeds available balance (${source.formattedBalance})."
            hasError = true
        }

        if (hasError) {
            _uiState.update { it.copy(accountError = accErr, amountError = amtErr) }
        } else {
            _uiState.update { it.copy(isReviewing = true, accountError = null, amountError = null) }
        }
    }

    fun onDismissReview() {
        _uiState.update { it.copy(isReviewing = false) }
    }

    fun onConfirmTransfer(context: Context? = null) {
        val current = _uiState.value
        val source = current.sourceAccount ?: return
        val dest = current.destinationAccount ?: return
        val amount = current.parsedAmount ?: return

        // In-flight duplicate submission guard
        if (current.submissionState is TransferSubmissionUiState.Submitting) return

        _uiState.update {
            it.copy(
                isReviewing = false,
                submissionState = TransferSubmissionUiState.Submitting("in-flight")
            )
        }

        viewModelScope.launch {
            // In BACKEND_CONNECTED mode with Context, evaluate behavioural ML risk & step-up if required
            if (context != null && repository.getRepositoryMode() == RepositoryMode.BACKEND_CONNECTED) {
                try {
                    val sessions = SecureSessionManager.getInstance(context)
                    if (sessions.hasActiveSession()) {
                        val api = BankingApiClient(sessions).apiService
                        val statusResp = api.securityStatus()
                        if (statusResp.isSuccessful && statusResp.body()?.action == "step_up") {
                            val activity = context as? FragmentActivity
                            if (activity != null && BiometricStepUp.isAvailable(context)) {
                                var stepUpPassed = false
                                var stepUpError: String? = null
                                val latch = kotlinx.coroutines.CompletableDeferred<Boolean>()
                                BiometricStepUp.show(
                                    activity = activity,
                                    title = "Step-Up Verification",
                                    subtitle = "Confirm identity for this transfer",
                                    onSuccess = { stepUpPassed = true; latch.complete(true) },
                                    onFailure = { err -> stepUpError = err; latch.complete(false) }
                                )
                                latch.await()
                                if (!stepUpPassed) {
                                    _uiState.update {
                                        it.copy(
                                            submissionState = TransferSubmissionUiState.Failed(
                                                message = stepUpError ?: "Biometric step-up verification required.",
                                                code = "STEP_UP_REQUIRED",
                                                isRetryable = true
                                            )
                                        )
                                    }
                                    return@launch
                                }
                                api.submitStepUp(StepUpRequest("biometric_prompt", true))
                            }
                        } else {
                            // Submit current on-device behavioural ML evaluation
                            val bm = BehaviourSessionManager.getInstance(context)
                            val classification = bm.classifyWithBaseline()
                            _uiState.update { it.copy(riskScore = classification.riskScore) }
                            val riskResp = api.submitRiskScore(
                                RiskSubmissionRequest(
                                    genuine_probability = classification.genuineProbability,
                                    uncertain_probability = classification.uncertainProbability,
                                    anomaly_probability = classification.anomalyProbability,
                                    risk_score = classification.riskScore,
                                    is_from_live_model = classification.isFromLiveModel,
                                    session_data_points = bm.getDataPointCount()
                                )
                            )
                            if (riskResp.isSuccessful && riskResp.body()?.action == "step_up") {
                                val activity = context as? FragmentActivity
                                if (activity != null && BiometricStepUp.isAvailable(context)) {
                                    val latch = kotlinx.coroutines.CompletableDeferred<Boolean>()
                                    var errMsg: String? = null
                                    BiometricStepUp.show(
                                        activity = activity,
                                        title = "Step-Up Verification",
                                        subtitle = "Unusual behavioural pattern detected — verify identity",
                                        onSuccess = { latch.complete(true) },
                                        onFailure = { err -> errMsg = err; latch.complete(false) }
                                    )
                                    val ok = latch.await()
                                    if (!ok) {
                                        _uiState.update {
                                            it.copy(
                                                submissionState = TransferSubmissionUiState.Failed(
                                                    message = errMsg ?: "Step-up verification cancelled.",
                                                    code = "STEP_UP_FAILED",
                                                    isRetryable = true
                                                )
                                            )
                                        }
                                        return@launch
                                    }
                                    api.submitStepUp(StepUpRequest("biometric_prompt", true))
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Proceed to backend transfer call — backend enforces authoritative policy
                }
            }

            val result = repository.executeTransfer(
                fromAccountId = source.id,
                toAccountId = dest.id,
                amount = amount,
                description = current.note.ifBlank { "Internal transfer" }
            )

            when (result) {
                is BankingResult.Success -> {
                    val receipt = result.data
                    val isDuress = receipt.status == "simulated_duress" || receipt.message.contains("duress", ignoreCase = true)
                    val nextState = if (isDuress) {
                        TransferSubmissionUiState.RestrictedDuress(
                            message = "Simulated Transfer Isolated — zero ledger balance modified.",
                            receipt = receipt
                        )
                    } else {
                        TransferSubmissionUiState.Completed(receipt)
                    }
                    _uiState.update {
                        it.copy(
                            submissionState = nextState,
                            amountInput = "",
                            parsedAmount = null,
                            note = ""
                        )
                    }
                    loadAccounts()
                }
                is BankingResult.Processing -> {
                    _uiState.update {
                        it.copy(
                            submissionState = TransferSubmissionUiState.Processing(
                                idempotencyKey = result.idempotencyKey,
                                message = result.message
                            )
                        )
                    }
                }
                is BankingResult.Error -> {
                    if (result.code == "RECONCILIATION_REQUIRED") {
                        val activePending = repository.getActivePendingTransfer()
                        _uiState.update {
                            it.copy(
                                submissionState = TransferSubmissionUiState.ReconciliationRequired(
                                    idempotencyKey = activePending?.idempotencyKey ?: "",
                                    message = result.message
                                )
                            )
                        }
                    } else {
                        _uiState.update {
                            it.copy(
                                submissionState = TransferSubmissionUiState.Failed(
                                    message = result.message,
                                    code = result.code,
                                    isRetryable = result.isRetryable
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    fun onReconcile(idempotencyKey: String) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(submissionState = TransferSubmissionUiState.Processing(idempotencyKey, "Reconciling transfer state with server..."))
            }

            val outcome = repository.reconcileTransfer(idempotencyKey)
            when (outcome) {
                is BankingResult.Success -> {
                    val rec = outcome.data
                    when (rec.state) {
                        ReconcileState.COMPLETED -> {
                            val receipt = rec.receipt ?: TransferReceipt(
                                transactionId = rec.transactionId ?: "reconciled",
                                referenceNumber = rec.referenceNumber ?: "REF-REC",
                                status = rec.status ?: "completed",
                                fromAccountId = _uiState.value.sourceAccount?.id ?: "",
                                toAccountId = _uiState.value.destinationAccount?.id ?: "",
                                amount = rec.amount ?: BigDecimal.ZERO,
                                currency = rec.currency ?: "INR",
                                idempotencyKey = idempotencyKey,
                                completedAt = rec.completedAt ?: java.time.Instant.now().toString(),
                                message = "Transfer confirmed via reconciliation."
                            )
                            _uiState.update {
                                it.copy(submissionState = TransferSubmissionUiState.Completed(receipt))
                            }
                            loadAccounts()
                        }
                        ReconcileState.PROCESSING -> {
                            _uiState.update {
                                it.copy(submissionState = TransferSubmissionUiState.Processing(idempotencyKey, "Transfer is still processing on the server. Please check back shortly."))
                            }
                        }
                        ReconcileState.FAILED -> {
                            _uiState.update {
                                it.copy(submissionState = TransferSubmissionUiState.Failed(
                                    message = rec.detail ?: "Transfer failed during server execution.",
                                    code = rec.errorCode,
                                    isRetryable = false
                                ))
                            }
                        }
                        ReconcileState.NOT_FOUND -> {
                            _uiState.update {
                                it.copy(submissionState = TransferSubmissionUiState.Failed(
                                    message = "No reservation was found on server for this idempotency key. Safe to re-submit with original parameters.",
                                    code = "NOT_FOUND",
                                    isRetryable = true
                                ))
                            }
                        }
                    }
                }
                is BankingResult.Error -> {
                    _uiState.update {
                        it.copy(submissionState = TransferSubmissionUiState.Failed(
                            message = outcome.message,
                            code = outcome.code,
                            isRetryable = outcome.isRetryable
                        ))
                    }
                }
                is BankingResult.Processing -> {}
            }
        }
    }

    fun onDismissSubmissionResult() {
        _uiState.update { it.copy(submissionState = TransferSubmissionUiState.Idle) }
    }
}
