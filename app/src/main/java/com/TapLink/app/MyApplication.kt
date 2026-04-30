package com.TapLinkX3.app

import android.app.Application
import com.ffalcon.mercury.android.sdk.MercurySDK

// Create a custom Application class

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize MercurySDK with the Application context
        try {
            MercurySDK.init(this)
        } catch (t: Throwable) {
            DebugLog.e("MyApplication", "MercurySDK initialization failed", t)
        }
    }
}