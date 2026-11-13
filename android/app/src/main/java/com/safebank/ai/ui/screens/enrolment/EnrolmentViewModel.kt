package com.safebank.ai.ui.screens.enrolment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.safebank.ai.data.network.BankingApiService
import com.safebank.ai.data.network.EnrolmentSample
import com.safebank.ai.data.network.EnrolmentStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class EnrolmentUiState(
    val status: EnrolmentStatus? = null,
    val busy: Boolean = false,
    val error: String? = null
)

class EnrolmentViewModel(private val api: BankingApiService, private val userId: String) : ViewModel() {
    private val mutableState = MutableStateFlow(EnrolmentUiState())
    val state = mutableState.asStateFlow()
    private var pending: EnrolmentSample? = null

    init { retry() }

    fun submit(stage: String, features: FloatArray, dataPoints: Int) {
        val current = mutableState.value
        val status = current.status ?: return
        if (current.busy || pending != null || status.current_stage != stage || status.completed) return
        if (features.size != 48 || features.any { !it.isFinite() } || features.all { it == 0f }) return
        val minimum = mapOf("TYPING" to 21, "TAPPING" to 9, "SWIPING" to 1, "MOTION" to 2)
        if (dataPoints < (minimum[stage] ?: return)) return
        pending = EnrolmentSample(stage, (status.counts[stage] ?: 0) + 1, features.toList(), dataPoints)
        retry()
    }

    fun retry() {
        if (mutableState.value.busy) return
        // Set synchronously, before launching, so repeated callbacks cannot enqueue requests.
        mutableState.value = mutableState.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                val response = pending?.let { api.enrolmentSample(it) } ?: api.enrolmentStatus()
                if (response.code() == 422) {
                    pending = null
                    error("Sample rejected. Reload progress and capture another sample.")
                }
                if (response.code() == 409) {
                    // Another device may have advanced this account. Reconcile before capturing again.
                    pending = null
                    val fresh = api.enrolmentStatus()
                    check(fresh.isSuccessful) { "Cannot reload progress" }
                    val status = checkNotNull(fresh.body())
                    check(status.user_id == userId) { "Enrolment owner mismatch" }
                    mutableState.value = EnrolmentUiState(status)
                } else {
                    check(response.isSuccessful) { "Unable to save enrolment (${response.code()}). Retry." }
                    val status = checkNotNull(response.body())
                    check(status.user_id == userId) { "Enrolment owner mismatch" }
                    pending = null
                    mutableState.value = EnrolmentUiState(status)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep the same ordinal on retry even when the server committed but its reply was lost.
                mutableState.value = mutableState.value.copy(busy = false, error = e.message ?: "Connection failed. Retry.")
            }
        }
    }
}
