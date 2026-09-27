package com.nitroboost.app.platform

import android.os.SystemClock
import com.nitroboost.app.core.SnmpMath
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Network quality samplers:
 *  - ping: TCP connect time to a fixed endpoint (no packets wasted)
 *  - retransmits: delta of Tcp RetransSegs from /proc/net/snmp
 */
class NetSampler {

    private var lastRetrans: Int? = null
    private var lastRetransTime = 0L

    fun pingMs(host: String = "1.1.1.1", port: Int = 53, timeoutMs: Int = 1500): Int? {
        return try {
            val t0 = SystemClock.elapsedRealtime()
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs)
            }
            (SystemClock.elapsedRealtime() - t0).toInt()
        } catch (e: Exception) {
            null
        }
    }

    fun retransPerSec(): Int? {
        val lines = try {
            File("/proc/net/snmp").readLines()
        } catch (e: Exception) {
            return null
        }
        val cur = SnmpMath.retransmits(lines) ?: return null
        val now = SystemClock.elapsedRealtime()
        val prev = lastRetrans
        val prevT = lastRetransTime
        lastRetrans = cur
        lastRetransTime = now
        if (prev == null || now - prevT < 500) return null
        val delta = cur - prev
        if (delta < 0) return null
        return (delta * 1000L / (now - prevT)).toInt().coerceAtMost(999)
    }
}
