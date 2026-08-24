package com.soildtunnel.app.core

import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Maps a TUN flow (local/remote endpoint pair) back to the owning app UID.
 * Merged into SoildTunnel: uses ConnectivityManager.getConnectionOwnerUid on
 * Android 10+ and a /proc/net parser on older releases (minSdk 26).
 */
class ConnectionOwnerResolver(
    private val connectivityManager: ConnectivityManager
) {
    fun resolve(protocol: Int, local: InetSocketAddress, remote: InetSocketAddress): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return runCatching {
                connectivityManager.getConnectionOwnerUid(protocol, local, remote)
            }.getOrDefault(Process.INVALID_UID)
        }
        return resolveLegacy(protocol, local, remote)
    }

    private fun resolveLegacy(protocol: Int, local: InetSocketAddress, remote: InetSocketAddress): Int {
        val files = when (protocol) {
            6 -> listOf("/proc/net/tcp", "/proc/net/tcp6")
            17 -> listOf("/proc/net/udp", "/proc/net/udp6")
            else -> return Process.INVALID_UID
        }
        val localAddress = encodeAddress(local.address)
        val remoteAddress = encodeAddress(remote.address)
        val localPort = local.port.toString(16).uppercase().padStart(4, '0')
        val remotePort = remote.port.toString(16).uppercase().padStart(4, '0')

        for (file in files) {
            val uid = runCatching {
                java.io.File(file).useLines { lines ->
                    lines.drop(1).firstNotNullOfOrNull { line ->
                        val columns = line.trim().split(Regex("\\s+"))
                        if (columns.size < 8) return@firstNotNullOfOrNull null
                        val localEndpoint = columns[1].split(':')
                        val remoteEndpoint = columns[2].split(':')
                        if (localEndpoint.size != 2 || remoteEndpoint.size != 2) return@firstNotNullOfOrNull null
                        val localMatches = localEndpoint[0].equals(localAddress, true) && localEndpoint[1].equals(localPort, true)
                        val remoteMatches = remoteEndpoint[0].equals(remoteAddress, true) && remoteEndpoint[1].equals(remotePort, true)
                        if (localMatches && remoteMatches) columns[7].toIntOrNull() else null
                    }
                }
            }.getOrNull()
            if (uid != null) return uid
        }
        return Process.INVALID_UID
    }

    private fun encodeAddress(address: InetAddress): String {
        val bytes = address.address
        return if (bytes.size == 4) {
            bytes.reversedArray().joinToString("") { "%02X".format(it.toInt() and 0xFF) }
        } else {
            bytes.asList()
                .chunked(4)
                .flatMap { it.reversed() }
                .joinToString("") { "%02X".format(it.toInt() and 0xFF) }
        }
    }
}
