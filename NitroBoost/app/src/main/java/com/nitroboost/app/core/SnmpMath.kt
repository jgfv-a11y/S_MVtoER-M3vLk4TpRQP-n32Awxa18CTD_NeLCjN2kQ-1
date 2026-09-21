package com.nitroboost.app.core

/**
 * Pure parsing helpers for /proc/net/snmp — unit-testable without Android.
 */
object SnmpMath {

    /**
     * Extract the cumulative Tcp RetransSegs counter from /proc/net/snmp lines.
     * Format:
     *   Tcp: RtoAlgorithm RtoMin ... RetransSegs InErrs ...
     *   Tcp: 1 200 ... 12 0 ...
     */
    fun retransmits(lines: List<String>): Int? {
        var header: List<String>? = null
        for (raw in lines) {
            val trimmed = raw.trim()
            if (!trimmed.startsWith("Tcp:")) continue
            val fields = trimmed.removePrefix("Tcp:").trim().split(Regex("\\s+"))
            if (fields.any { it == "RetransSegs" }) {
                header = fields
            } else if (header != null) {
                val idx = header!!.indexOf("RetransSegs")
                if (idx in fields.indices) {
                    return fields[idx].toIntOrNull()
                }
            }
        }
        return null
    }
}
