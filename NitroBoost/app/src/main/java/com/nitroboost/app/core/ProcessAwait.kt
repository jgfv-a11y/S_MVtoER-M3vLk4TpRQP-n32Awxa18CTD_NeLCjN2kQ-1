package com.nitroboost.app.core

import android.os.Build
import java.util.concurrent.TimeUnit

/**
 * Timeout-bounded process wait that is safe on API 24/25: the timed
 * [Process.waitFor] overload only exists from API 26 and throws
 * NoSuchMethodError on Android 7.x. On old APIs this polls [Process.exitValue];
 * interruption is reported as "not finished" with the interrupt flag
 * restored, matching the previous call sites' handling.
 */
fun Process.awaitExit(timeoutMs: Long): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        return try {
            waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }
    val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    while (true) {
        try {
            exitValue()
            return true
        } catch (e: IllegalThreadStateException) {
            if (System.nanoTime() >= deadlineNanos) return false
            try {
                Thread.sleep(10L)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
    }
}
