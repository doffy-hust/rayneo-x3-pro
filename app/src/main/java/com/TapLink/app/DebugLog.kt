package com.TapLinkX3.app

/**
 * Utility object for debug logging that only logs in debug builds.
 * All Log.d calls should use this to ensure no debug logs in production.
 */
object DebugLog {
    fun i(tag: String, message: String) {
        // debug logging disabled
    }

    fun d(tag: String, message: String) {
        // debug logging disabled
    }

    fun d(tag: String, messageProvider: () -> String) {
        // debug logging disabled
    }

    fun w(tag: String, message: String) {
        // debug logging disabled
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        // debug logging disabled
    }

    /** Always forwarded to [Log.i], including release builds (may include user transcript content). */
    fun alwaysI(tag: String, message: String) {
        // debug logging disabled
    }

    /** Always forwarded to [Log.w], including release builds. */
    fun alwaysW(tag: String, message: String) {
        // debug logging disabled
    }
}
