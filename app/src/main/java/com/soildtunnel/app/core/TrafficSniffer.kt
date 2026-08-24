package com.soildtunnel.app.core

import java.util.Locale

/**
 * Extracts the target domain from the first payload of a flow: TLS SNI on
 * port 443, HTTP Host header on port 80. Merged into SoildTunnel; used by the
 * userspace tunnel bridge for flow attribution.
 */
object TrafficSniffer {
    fun sniffDomain(data: ByteArray, port: Int): String? {
        if (data.isEmpty()) return null
        return when (port) {
            443 -> sniffSni(data)
            80 -> sniffHttpHost(data)
            else -> null
        }
    }

    private fun sniffSni(data: ByteArray): String? {
        try {
            if (data.size < 43) return null
            if (data[0] != 0x16.toByte()) return null

            var pos = 5
            val handshakeType = data[pos]
            if (handshakeType != 0x01.toByte()) return null

            pos += 4
            pos += 2
            pos += 32

            val sessionIDLen = data[pos].toInt() and 0xFF
            pos += 1 + sessionIDLen

            if (pos + 2 > data.size) return null
            val cipherSuiteLen = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
            pos += 2 + cipherSuiteLen

            if (pos + 1 > data.size) return null
            val compressionMethodLen = data[pos].toInt() and 0xFF
            pos += 1 + compressionMethodLen

            if (pos + 2 > data.size) return null
            val extensionsLen = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
            pos += 2

            val extensionsEnd = pos + extensionsLen
            while (pos + 4 <= extensionsEnd && pos + 4 <= data.size) {
                val extType = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
                val extLen = ((data[pos + 2].toInt() and 0xFF) shl 8) or (data[pos + 3].toInt() and 0xFF)
                pos += 4

                if (extType == 0x00) {
                    if (pos + 5 > data.size) return null
                    val listLen = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
                    pos += 2
                    if (pos + listLen > data.size) return null

                    val nameType = data[pos]
                    val nameLen = ((data[pos + 1].toInt() and 0xFF) shl 8) or (data[pos + 2].toInt() and 0xFF)
                    pos += 3
                    if (nameType == 0x00.toByte() && pos + nameLen <= data.size) {
                        return String(data, pos, nameLen)
                    }
                }
                pos += extLen
            }
        } catch (_: Exception) {}
        return null
    }

    private fun sniffHttpHost(data: ByteArray): String? {
        try {
            val text = String(data, 0, minOf(data.size, 2048))
            val lines = text.split("\r\n")
            for (line in lines) {
                if (line.startsWith("Host:", ignoreCase = true)) {
                    return line.substring(5).trim().split(":")[0].lowercase(Locale.ROOT)
                }
            }
        } catch (_: Exception) {}
        return null
    }
}
