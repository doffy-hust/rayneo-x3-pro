package com.TapLinkX3.app

import android.util.Log

/**
 * Utility object for debug logging that only logs in debug builds.
 * All Log.d calls should use this to ensure no debug logs in production.
 */
object DebugLog {
    fun i(tag: String, message: String) {
        if (BuildConfig.DEBUG) {
            Log.i(tag, message)
        }
    }

    fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(tag, message)
        }
    }

    fun d(tag: String, messageProvider: () -> String) {
        if (BuildConfig.DEBUG) {
            Log.d(tag, messageProvider())
        }
    }

    fun w(tag: String, message: String) {
        if (BuildConfig.DEBUG) {
            Log.w(tag, message)
        }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            Log.e(tag, message, throwable)
        }
    }

    /** Always forwarded to [Log.i], including release builds (may include user transcript content). */
    fun alwaysI(tag: String, message: String) {
        Log.i(tag, message)
    }

    /** Always forwarded to [Log.w], including release builds. */
    fun alwaysW(tag: String, message: String) {
        Log.w(tag, message)
    }
}
