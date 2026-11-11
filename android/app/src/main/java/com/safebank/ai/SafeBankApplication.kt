package com.safebank.ai

import android.app.Application
import android.util.Log
import com.safebank.ai.data.di.BankingRepositoryProvider

class SafeBankApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "SafeBank AI Application initialized (Stage 4 Banking Foundation)")
        BankingRepositoryProvider.getRepository(this)
    }

    companion object {
        const val TAG = "SafeBankApp"
    }
}
