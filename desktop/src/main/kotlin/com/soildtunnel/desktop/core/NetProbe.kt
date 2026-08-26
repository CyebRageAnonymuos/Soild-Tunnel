package com.soildtunnel.desktop.core

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread

/** Public IP + country as reported by a geolocation endpoint. */
data class IpInfo(val ip: String, val countryCode: String?)

/** IP shown in the UI, tagged with whether it came through the tunnel. */
data class IpEndpoint(val ip: String, val countryCode: String?, val viaTunnel: Boolean)

/**
 * Low-level, dependency-free network probes.
 *
 * These use RAW sockets on purpose:
 * A hand-rolled SOCKS5 client lets us send the destination as a DOMAIN
 * (ATYP=0x03) so DNS is resolved REMOTELY by the engine — exactly what a
 * real browser tab does through the tunnel. That makes the probe a true
 * end-to-end test of DNS + TCP + the proxy path in one shot.
 *
 * A direct socket bypasses the tunnel (→ operator/real IP) while a socket routed
 * through 127.0.0.1:1819 exits via the connected server (→ server IP).
 */
object NetProbe {

    /**
     * Geolocation endpoints, tried IN ORDER until one answers.
     *
     * ROOT-CAUSE NOTE: relying on a single provider (ip-api.com over plain
     * HTTP:80) was a single point of failure — on many operator networks that
     * host/port simply never answers, so the IP badge silently showed nothing
     * even while fully disconnected. Cloudflare's /cdn-cgi/trace is served
     * from an anycast edge (reachable on nearly every network) and is used as
     * the fallback — once via TLS with SNI, and once via the bare IP literal
     * .1 which needs no DNS at all.
     */
    private data class GeoProvider(
        val host: String,
        val port: Int,
        val path: String,
        val tls: Boolean,
        /** false = host is an IPv4 literal (SOCKS ATYP=0x01, no DNS involved). */
        val hostIsDomain: Boolean,
    )

    private val GEO_PROVIDERS = listOf(
        GeoProvider("ip-api.com", 80, "/json/?fields=status,query,countryCode", tls = false, hostIsDomain = true),
        GeoProvider("www.cloudflare.com", 443, "/cdn-cgi/trace", tls = true, hostIsDomain = true),
        GeoProvider("1.1.1.1", 80, "/cdn-cgi/trace", tls = false, hostIsDomain = false),
    )

    // ---- Public: geolocation --------------------------------------------

    /** Real/operator IP (direct, bypasses the tunnel). */
    fun fetchIpInfoDirect(timeoutMs: Int = 8000): IpInfo? {
        for (p in GEO_PROVIDERS) {
            val info = runCatching {
                openDirectIpv4(p.host, p.port, timeoutMs).use { s ->
                    val io = if (p.tls) tlsWrap(s, p.host, p.port, timeoutMs) else s
                    parseIpInfo(httpGet(io, p.host, p.path))
                }
            }.onFailure {
                DiagnosticsLog.d("netprobe", "direct geo ${p.host} failed: ${it.message}")
            }.getOrNull()
            if (info != null) {
                return refineCountry(info, p) { host, port ->
                    openDirectIpv4(host, port, timeoutMs)
                }
            }
        }
        return null
    }

    /**
     * Opens a DIRECT (non-proxied) socket, forcing IPv4.
     *
     * ROOT-CAUSE FIX (MCI / Hamrah-e-Aval cellular): on dual-stack mobile data
     * the default dual-stack connect prefers IPv6, so the "your IP" badge
     * surfaced a scoped/temporary IPv6 address instead of the operator's real
     * public IPv4 (on Wi-Fi, often IPv4-only, it already looked correct). We
     * resolve the host and connect to its IPv4 address explicitly, so the geo
     * endpoint always sees — and reports — the real IPv4.
     */
    private fun openDirectIpv4(host: String, port: Int, timeoutMs: Int): Socket {
        val addr = resolveIpv4(host)
        return Socket().apply {
            connect(InetSocketAddress(addr, port), timeoutMs)
            soTimeout = timeoutMs
        }
    }

    /** Resolves [host] to an IPv4 address (falls back to the first address). */
    private fun resolveIpv4(host: String): InetAddress {
        val all = InetAddress.getAllByName(host)
        return all.firstOrNull { it is Inet4Address } ?: all.first()
    }

    /** Exit/server IP (routed through the local SOCKS5 proxy). */
    fun fetchIpInfoViaSocks(
        socksHost: String,
        socksPort: Int,
        timeoutMs: Int = 9000,
    ): IpInfo? {
        for (p in GEO_PROVIDERS) {
            val info = runCatching {
                socks5Connect(socksHost, socksPort, p.host, p.port, useDomain = p.hostIsDomain, timeoutMs).use { s ->
                    val io = if (p.tls) tlsWrap(s, p.host, p.port, timeoutMs) else s
                    parseIpInfo(httpGet(io, p.host, p.path))
                }
            }.onFailure {
                DiagnosticsLog.d("netprobe", "proxied geo ${p.host} failed: ${it.message}")
            }.getOrNull()
            if (info != null) {
                return refineCountry(info, p) { host, port ->
                    socks5Connect(socksHost, socksPort, host, port, useDomain = true, timeoutMs)
                }
            }
        }
        return null
    }


    /**
     * SPEED FIX: races ALL geolocation providers through the proxy in
     * PARALLEL and returns the first success. The serial fallback chain in
     * [fetchIpInfoViaSocks] burns up to one full timeout per filtered
     * provider — and which provider is filtered/slow varies per operator and
     * region (DPI variance), so some users waited tens of seconds before the
     * provider that actually works on their network was even tried. The race
     * always completes as fast as the FASTEST provider for the current user.
     */
    fun fetchIpInfoViaSocksRaced(
        socksHost: String,
        socksPort: Int,
        timeoutMs: Int = 6000,
    ): IpInfo? {
        val winner = AtomicReference<IpInfo?>(null)
        val remaining = AtomicInteger(GEO_PROVIDERS.size)
        val done = CountDownLatch(1)
        for (p in GEO_PROVIDERS) {
            // CPU/MEMORY FIX: run the race on a SHARED, recycled pool
            // instead of spawning brand-new OS threads on every call. This
            // function runs on connect, on every reconnect and on each IP
            // refresh; each invocation used to create one raw thread per
            // provider (each with its own stack) that was then thrown away.
            // A cached pool reuses those workers, so repeated refreshes stop
            // churning thread stacks and the GC.
            geoPool.execute {
                val info = runCatching {
                    socks5Connect(socksHost, socksPort, p.host, p.port, useDomain = p.hostIsDomain, timeoutMs).use { s ->
                        val io = if (p.tls) tlsWrap(s, p.host, p.port, timeoutMs) else s
                        parseIpInfo(httpGet(io, p.host, p.path))
                    }
                }.onFailure {
                    DiagnosticsLog.d("netprobe", "raced geo ${p.host} failed: ${it.message}")
                }.getOrNull()
                if (info != null) {
                    val refined = runCatching {
                        refineCountry(info, p) { host, port ->
                            socks5Connect(socksHost, socksPort, host, port, useDomain = true, timeoutMs)
                        }
                    }.getOrDefault(info)
                    if (winner.compareAndSet(null, refined)) done.countDown()
                }
                if (remaining.decrementAndGet() == 0) done.countDown()
            }
        }
        // Small headroom over the per-connection timeout for TLS + HTTP I/O.
        done.await(timeoutMs.toLong() + 4_000L, TimeUnit.MILLISECONDS)
        return winner.get()
    }

    /**
     * Shared worker pool for the geolocation race (see
     * [fetchIpInfoViaSocksRaced]). Threads are daemons and idle ones are
     * reclaimed after 30 s, so the pool costs nothing while disconnected.
     */
    private val geoPool = Executors.newCachedThreadPool(
        object : ThreadFactory {
            private val counter = AtomicInteger(0)
            override fun newThread(r: Runnable): Thread =
                Thread(r, "geo-race-${counter.incrementAndGet()}").apply {
                    isDaemon = true
                    priority = Thread.NORM_PRIORITY - 1
                }
        },
    )

    /** Wraps an already-connected socket in TLS (SNI = [host]). */
    private fun tlsWrap(socket: Socket, host: String, port: Int, timeoutMs: Int): Socket {
        // getDefault is statically typed as the plain SocketFactory, which
        // lacks the (socket, host, port, autoClose) overload — cast first.
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val ssl = factory.createSocket(socket, host, port, true) as SSLSocket
        ssl.soTimeout = timeoutMs
        ssl.startHandshake()
        // SECURITY FIX (MitM): SSLSocket.startHandshake validates the chain
        // against the system trust store but does NOT check that the
        // certificate actually belongs to [host]. Without this check an
        // attacker holding a valid certificate for ANY domain could intercept
        // the TLS geolocation probe. Enforce standard hostname verification.
        val verifier = HttpsURLConnection.getDefaultHostnameVerifier()
        if (!verifier.verify(host, ssl.session)) {
            runCatching { ssl.close() }
            throw SSLPeerUnverifiedException("Certificate does not match host $host (possible MitM)")
        }
        return ssl
    }

    /**
     * FLAG-CORRECTNESS FIX: Cloudflare's /cdn-cgi/trace `loc=` is the country
     * Cloudflare attributes to the CLIENT — behind WARP that often differs
     * from the country the exit IP itself is registered in (which is what
     * ip-api reports). Depending on which provider happened to win, the badge
     * showed a different flag for the very same connection. Whenever a
     * non-ip-api provider supplied the IP, re-ask ip-api about THAT exact IP
     * over the same network path, so the flag always comes from one geo
     * database. Falls back to the provider's own country code if ip-api is
     * unreachable.
     */
    private fun refineCountry(
        info: IpInfo,
        provider: GeoProvider,
        open: (host: String, port: Int) -> Socket,
    ): IpInfo {
        if (provider.host == "ip-api.com") return info
        val cc = runCatching {
            open("ip-api.com", 80).use { s ->
                val body = httpGet(s, "ip-api.com", "/json/${info.ip}?fields=status,countryCode")
                Regex("\"countryCode\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
            }
        }.getOrNull()
        return if (cc != null) info.copy(countryCode = cc) else info
    }

    // ---- Retry wrappers -------------------------------------------------
    //
    // The IP badge used to fire a SINGLE lookup keyed on the connection phase.
    // Right after connect, the warp-in-warp tunnel is still cold, so the first
    // request loses the race and the badge is stuck on "unavailable" forever
    // (there was no retry). These helpers ride out that cold-start window. They
    // block between attempts, so call them off the main thread (Dispatchers.IO).

    /** [fetchIpInfoDirect] with retries for a flaky operator network. */
    fun fetchIpInfoDirectWithRetry(
        attempts: Int = 6,
        delayMs: Long = 2000,
        timeoutMs: Int = 8000,
    ): IpInfo? {
        repeat(attempts) { i ->
            fetchIpInfoDirect(timeoutMs)?.let { return it }
            if (i < attempts - 1) {
                try {
                    Thread.sleep(delayMs)
                } catch (_: InterruptedException) {
                    return null
                }
            }
        }
        return null
    }

    /** [fetchIpInfoViaSocks] with retries to cover the tunnel's cold start. */
    fun fetchIpInfoViaSocksWithRetry(
        socksHost: String,
        socksPort: Int,
        // SPEED FIX: raced providers + 1 s pacing instead of serial providers
        // + 3 s pacing — same total patience, far lower time-to-first-answer.
        attempts: Int = 12,
        delayMs: Long = 1000,
        timeoutMs: Int = 6000,
    ): IpInfo? {
        repeat(attempts) { i ->
            fetchIpInfoViaSocksRaced(socksHost, socksPort, timeoutMs)?.let { return it }
            if (i < attempts - 1) {
                try {
                    Thread.sleep(delayMs)
                } catch (_: InterruptedException) {
                    return null
                }
            }
        }
        return null
    }

    // ---- Public: connectivity self-tests --------------------------------

    /** SOCKS5 greeting only — proves the engine speaks SOCKS5. */
    fun checkSocksHandshake(socksHost: String, socksPort: Int, timeoutMs: Int = 4000): Boolean =
        runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress(socksHost, socksPort), timeoutMs)
                s.soTimeout = timeoutMs
                s.getOutputStream().apply { write(byteArrayOf(0x05, 0x01, 0x00)); flush() }
                val reply = ByteArray(2)
                DataInputStream(s.getInputStream()).readFully(reply)
                reply[0].toInt() == 0x05 && reply[1].toInt() == 0x00
            }
        }.getOrDefault(false)

    /** Full SOCKS5 CONNECT to an IP literal — proves TCP proxying works (no DNS). */
    fun checkTcpViaProxy(
        socksHost: String,
        socksPort: Int,
        destIp: String,
        destPort: Int,
        timeoutMs: Int = 6000,
    ): Boolean = runCatching {
        socks5Connect(socksHost, socksPort, destIp, destPort, useDomain = false, timeoutMs).use { true }
    }.getOrDefault(false)

    // ---- SOCKS5 core ----------------------------------------------------

    /**
     * Opens a TCP connection to [destHost]:[destPort] THROUGH the SOCKS5 proxy
     * and returns the live socket (caller closes). Throws on any failure.
     */
    private fun socks5Connect(
        socksHost: String,
        socksPort: Int,
        destHost: String,
        destPort: Int,
        useDomain: Boolean,
        timeoutMs: Int,
    ): Socket {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(socksHost, socksPort), timeoutMs)
            socket.soTimeout = timeoutMs
            val out = socket.getOutputStream()
            val input = DataInputStream(socket.getInputStream())

            // Greeting: VER=5, 1 method, NO-AUTH.
            out.write(byteArrayOf(0x05, 0x01, 0x00))
            out.flush()
            val greeting = ByteArray(2)
            input.readFully(greeting)
            if (greeting[0].toInt() != 0x05 || greeting[1].toInt() != 0x00) {
                throw IOException("SOCKS5 auth negotiation failed")
            }

            // Request: CONNECT.
            val req = ByteArrayOutputStream()
            req.write(0x05)
            req.write(0x01)
            req.write(0x00)
            if (useDomain) {
                val host = destHost.toByteArray(Charsets.US_ASCII)
                req.write(0x03)
                req.write(host.size)
                req.write(host)
            } else {
                val octets = destHost.split(".")
                if (octets.size != 4) throw IOException("bad IPv4 literal: $destHost")
                req.write(0x01)
                octets.forEach { req.write(it.toInt() and 0xFF) }
            }
            req.write((destPort ushr 8) and 0xFF)
            req.write(destPort and 0xFF)
            out.write(req.toByteArray())
            out.flush()

            // Reply: VER, REP, RSV, ATYP, BND.ADDR, BND.PORT.
            val head = ByteArray(4)
            input.readFully(head)
            if (head[1].toInt() != 0x00) {
                throw IOException("SOCKS5 CONNECT rejected (rep=${head[1].toInt() and 0xFF})")
            }
            val skip = when (head[3].toInt() and 0xFF) {
                0x01 -> 4
                0x04 -> 16
                0x03 -> {
                    val len = ByteArray(1)
                    input.readFully(len)
                    len[0].toInt() and 0xFF
                }
                else -> throw IOException("SOCKS5 bad ATYP in reply")
            }
            input.readFully(ByteArray(skip))
            input.readFully(ByteArray(2)) // BND.PORT
            return socket
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
    }

    // ---- Minimal HTTP/1.1 -----------------------------------------------

    private fun httpGet(socket: Socket, host: String, path: String): String {
        val request = buildString {
            append("GET ").append(path).append(" HTTP/1.1\r\n")
            append("Host: ").append(host).append("\r\n")
            append("User-Agent: SoildTunnel/1.0\r\n")
            append("Accept: */*\r\n")
            append("Connection: close\r\n\r\n")
        }
        socket.getOutputStream().apply {
            write(request.toByteArray(Charsets.US_ASCII))
            flush()
        }
        return socket.getInputStream().readBytes().toString(Charsets.UTF_8)
    }

    private fun parseIpInfo(response: String): IpInfo? {
        val body = response.substringAfter("\r\n\r\n", "")
        // Format 1: ip-api.com JSON  {"query":"1.2.3.4","countryCode":"DE"}
        val ip = Regex("\"query\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
        if (ip != null) {
            val cc = Regex("\"countryCode\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
            return IpInfo(ip, cc)
        }
        // Format 2: Cloudflare /cdn-cgi/trace key=value lines (ip=..., loc=...)
        val traceIp = Regex("(?m)^ip=(\\S+)").find(body)?.groupValues?.get(1)
        if (traceIp != null) {
            val loc = Regex("(?m)^loc=([A-Za-z]{2})").find(body)?.groupValues?.get(1)
            return IpInfo(traceIp, loc?.uppercase())
        }
        return null
    }

    /** Turns a 2-letter ISO country code into its flag emoji. */
    fun flagEmoji(countryCode: String?): String {
        if (countryCode == null || countryCode.length != 2) return "\uD83C\uDFF3\uFE0F"
        val cc = countryCode.uppercase()
        if (!cc.all { it in 'A'..'Z' }) return "\uD83C\uDFF3\uFE0F"
        val base = 0x1F1E6
        val first = base + (cc[0].code - 'A'.code)
        val second = base + (cc[1].code - 'A'.code)
        return String(Character.toChars(first)) + String(Character.toChars(second))
    }
}
