package com.soildtunnel.desktop.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * How hostile the current network's filtering (DPI) looks, derived from the
 * direct probes in [NetworkFingerprinter.fingerprint]:
 *
 *  - OPEN          : UDP answers and TLS-with-SNI completes — a mostly clean path.
 *  - SNI_FILTERING : UDP is fine but a TLS handshake carrying an SNI stalls or
 * resets — classic SNI-based DPI. Obfuscation (noize) matters.
 *  - UDP_THROTTLED : TLS works but UDP gets no answers — the operator drops or
 * throttles UDP, which starves WireGuard/QUIC. TCP-shaped
 * transports (MASQUE over HTTP/2) are the way in.
 *  - HOSTILE       : both are broken — bring everything: TCP transport, heavy
 * obfuscation, fragmentation and ECH.
 */
enum class DpiClass { OPEN, SNI_FILTERING, UDP_THROTTLED, HOSTILE }

/** Everything Smart Auto learned about the current network before connecting. */
data class NetworkFingerprint(
    val dpiClass: DpiClass,
    val udpOk: Boolean,
    val tlsSniOk: Boolean,
    val operatorName: String,
    /** True on Android when on Iranian cellular (MCC 432); always false on desktop. */
    val iranCellular: Boolean,
    /** WARP range CIDR -> TCP connect latency in ms (-1 = unreachable). */
    val edgeLatencyMs: Map<String, Long>,
)

/**
 * Network fingerprinting for Smart Auto, desktop edition (no Context).
 *
 * 1. FINGERPRINT ([fingerprint]) — before the engine even launches, probe the
 * real network DIRECTLY (the probes run before any TUN is up, so they always
 * see the raw network path):
 *       - UDP health: real DNS queries over UDP/53 to 1.1.1.1 and 8.8.8.8.
 *       - SNI DPI: a full TLS handshake to 1.1.1.1:443 carrying the SNI
 * "www.cloudflare.com" (with hostname verification, no data sent).
 *       - WARP edge reachability: TCP connect latency to one representative
 * host in each [ServerCatalog] edge range.
 * 2. CLASSIFY the DPI behaviour into a [DpiClass] ([SmartAuto.buildPlan]
 * turns it into an ordered strategy ladder).
 *
 * Every probe result and every decision is written to the in-app log, so the
 * user can see exactly WHY Smart Auto picked what it picked.
 */
object NetworkFingerprinter {
    private const val TAG = "auto"
    private const val PROBE_TIMEOUT_MS = 3_000
    private const val TLS_PROBE_TIMEOUT_MS = 4_000

    /** No telephony/connectivity services on desktop: unknown operator. */
    private const val OPERATOR_NAME = ""
    private const val IRAN_CELLULAR = false

    /** Edge ranges under test + representative probe host, from the server catalog. */
    private val EDGES: List<Pair<String, String>> =
        ServerCatalog.nodes.map { it.rangeSpec to it.probeHost }

    // ---- Stage 1+2: probe the network and classify its DPI ----------------

    suspend fun fingerprint(): NetworkFingerprint = withContext(Dispatchers.IO) {
        DiagnosticsLog.i(TAG, "Fingerprinting the network…")
        val started = System.currentTimeMillis()
        val fp = coroutineScope {
            // All probes run in PARALLEL — the whole stage costs one timeout at worst.
            val udpCf = async { udpDnsProbe("1.1.1.1") }
            val udpGoog = async { udpDnsProbe("8.8.8.8") }
            val tls = async { tlsSniProbe() }
            val edgeJobs = EDGES.map { (cidr, probeIp) ->
                async { cidr to tcpLatencyMs(probeIp, 443) }
            }
            val udpOk = udpCf.await() || udpGoog.await()
            val tlsOk = tls.await()
            val edges = edgeJobs.awaitAll().toMap()
            val cls = when {
                udpOk && tlsOk -> DpiClass.OPEN
                udpOk -> DpiClass.SNI_FILTERING
                tlsOk -> DpiClass.UDP_THROTTLED
                else -> DpiClass.HOSTILE
            }
            NetworkFingerprint(cls, udpOk, tlsOk, OPERATOR_NAME, IRAN_CELLULAR, edges)
        }
        val edgeSummary = fp.edgeLatencyMs.entries.joinToString(", ") { (range, ms) ->
            "$range=${if (ms < 0) "unreachable" else "${ms}ms"}"
        }
        DiagnosticsLog.i(
            TAG,
            "DPI fingerprint ready in ${System.currentTimeMillis() - started} ms: " +
                "udp=${fp.udpOk} tlsSni=${fp.tlsSniOk} → ${fp.dpiClass} | edges: $edgeSummary",
        )
        fp
    }

    // ---- Probes ------------------------------------------------------------

    /**
     * Sends a REAL DNS query (A record for example.com) over UDP/53 and waits
     * for any well-formed answer. An answer proves UDP round-trips survive on
     * this network; silence from BOTH resolvers means UDP is dropped/throttled.
     */
    private fun udpDnsProbe(server: String, timeoutMs: Int = PROBE_TIMEOUT_MS): Boolean = runCatching {
        DatagramSocket().use { sock ->
            sock.soTimeout = timeoutMs
            val query = byteArrayOf(
                0x1A, 0x2B, // transaction id
                0x01, 0x00, // standard query, recursion desired
                0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                7, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(),
                'm'.code.toByte(), 'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
                3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
                0,
                0x00, 0x01, // type A
                0x00, 0x01, // class IN
            )
            sock.send(DatagramPacket(query, query.size, InetAddress.getByName(server), 53))
            val buf = ByteArray(512)
            sock.receive(DatagramPacket(buf, buf.size))
            val ok = buf[0] == 0x1A.toByte() && buf[1] == 0x2B.toByte()
            DiagnosticsLog.d(TAG, "udp53 probe $server → ${if (ok) "answered" else "bad reply"}")
            ok
        }
    }.getOrElse {
        DiagnosticsLog.d(TAG, "udp53 probe $server → no answer (${it.message})")
        false
    }

    /** TCP connect latency to [ip]:[port] in ms, or -1 when unreachable. */
    private fun tcpLatencyMs(ip: String, port: Int, timeoutMs: Int = PROBE_TIMEOUT_MS): Long = runCatching {
        val start = System.nanoTime()
        Socket().use { it.connect(InetSocketAddress(ip, port), timeoutMs) }
        (System.nanoTime() - start) / 1_000_000
    }.getOrDefault(-1L)

    /**
     * Completes a full TLS handshake to 1.1.1.1:443 with the SNI
     * "www.cloudflare.com" (no payload is sent). SNI-based DPI middleboxes
     * kill exactly this step, so a failure here — while plain TCP connects
     * fine — is a strong SNI-filtering signal. Hostname verification is
     * enforced, same as the geolocation probes.
     */
    private fun tlsSniProbe(timeoutMs: Int = TLS_PROBE_TIMEOUT_MS): Boolean = runCatching {
        Socket().use { raw ->
            raw.connect(InetSocketAddress("1.1.1.1", 443), timeoutMs)
            raw.soTimeout = timeoutMs
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val ssl = factory.createSocket(raw, "www.cloudflare.com", 443, true) as SSLSocket
            ssl.soTimeout = timeoutMs
            ssl.startHandshake()
            val ok = HttpsURLConnection.getDefaultHostnameVerifier().verify("www.cloudflare.com", ssl.session)
            runCatching { ssl.close() }
            DiagnosticsLog.d(TAG, "tls-sni probe → ${if (ok) "handshake ok" else "hostname mismatch"}")
            ok
        }
    }.getOrElse {
        DiagnosticsLog.d(TAG, "tls-sni probe → failed (${it.message})")
        false
    }
}
