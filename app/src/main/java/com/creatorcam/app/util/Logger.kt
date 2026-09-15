package com.creatorcam.app.util

import android.util.Log
import com.creatorcam.app.BuildConfig

/**
 * Structured, tag-scoped logging for camera / recording / export pipelines.
 * Debug builds log verbosely; release builds only log warnings and errors.
 * Never log file contents, identifiers, or anything user-sensitive.
 */
object Logger {
    private const val PREFIX = "CreatorCam"

    fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.d("$PREFIX:$tag", message)
    }

    fun i(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.i("$PREFIX:$tag", message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable == null) Log.w("$PREFIX:$tag", message)
        else Log.w("$PREFIX:$tag", message, throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable == null) Log.e("$PREFIX:$tag", message)
        else Log.e("$PREFIX:$tag", message, throwable)
    }
}
