package com.safebank.ai

import android.app.Application
import android.util.Log

class SafeBankApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "SafeBank AI Application initialized (Stage 1 Foundation)")
    }

    companion object {
        const val TAG = "SafeBankApp"
    }
}
