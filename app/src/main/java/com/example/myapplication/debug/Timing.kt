package com.example.myapplication.debug

import android.os.SystemClock
import android.util.Log

/**
 * Timing logs, for finding where a command's time goes. Read them with
 * `adb logcat -s ZeroUITiming`. Kept out of classes the JVM unit tests run, where Log isn't there.
 */
object Timing {
    const val TAG = "ZeroUITiming"

    fun now(): Long = SystemClock.elapsedRealtime()

    fun log(message: String) {
        Log.d(TAG, message)
    }

    /** Runs [block] and logs how long it took, also when it fails or is stopped. */
    inline fun <T> measure(label: String, block: () -> T): T {
        val start = now()
        var finished = false
        try {
            return block().also { finished = true }
        } finally {
            log("$label: ${now() - start} ms${if (finished) "" else " (failed or stopped)"}")
        }
    }
}
