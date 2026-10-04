package com.example.onedgeagent

import android.util.Log

object LogUtils {
    private const val TAG = "OnEdgeAgent"
    var IS_DEBUG = true // Toggle this for production release

    fun d(message: String) {
        if (IS_DEBUG) Log.d(TAG, message)
    }

    fun e(message: String, throwable: Throwable? = null) {
        if (IS_DEBUG) Log.e(TAG, message, throwable)
    }

    fun i(message: String) {
        if (IS_DEBUG) Log.i(TAG, message)
    }
}
