package com.safebank.ai.ml

import android.content.Context
import com.safebank.ai.data.security.SecureSessionManager
import com.safebank.ai.data.network.*

fun interface TransferRiskAssessor {
    suspend fun assess(context: Context?, transaction: Map<String,String>): RiskDecisionResponse
}

class LiveTransferRiskAssessor : TransferRiskAssessor {
    override suspend fun assess(context: Context?, transaction: Map<String,String>): RiskDecisionResponse {
        check(context != null) { "Authenticated context required" }
        val sessions=SecureSessionManager.getInstance(context)
        check(sessions.hasActiveSession()) { "Sign in again" }
        val manager=BehaviourSessionManager.getInstance(context)
        check(manager.collectionConsented) { "Enable behavioural collection before transferring" }
        val result=manager.classifyWithBaseline()
        check(result.isFromLiveModel && manager.getDataPointCount() >= 5) {
            "Fresh behavioural data and live inference are required (${result.inferenceStatus})"
        }
        val response=BankingApiClient(sessions).apiService.submitRiskScore(RiskSubmissionRequest(
            result.genuineProbability,result.uncertainProbability,result.anomalyProbability,
            result.riskScore,true,manager.getDataPointCount(),transaction_context=transaction))
        check(response.isSuccessful) { "Risk assessment failed (${response.code()})" }
        return checkNotNull(response.body())
    }
}
