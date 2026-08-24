package com.soildtunnel.app.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * LAN sharing bridge: lets OTHER devices on the same Wi-Fi / hotspot use this
 * phone's SoildTunnel tunnel as a normal proxy.
 *
 * Two listeners are exposed while sharing is on:
 *  - SOCKS5  0.0.0.0:[SOCKS_SHARE_PORT] — a transparent TCP relay into the
 *    engine's local SOCKS5 (127.0.0.1:1819, loopback-only), so the full SOCKS5
 *    protocol (including remote DNS) is served by the engine itself.
 *  - HTTP    0.0.0.0:[HTTP_SHARE_PORT] — a minimal HTTP/1.1 proxy (CONNECT for
 *    HTTPS + absolute-form for plain HTTP) that dials upstream THROUGH that
 *    SOCKS5 proxy. This is what the "system proxy" settings on Windows/macOS
 *    (and most phones) expect, so laptops work out of the box.
 *
 * Loop safety: this code runs inside the app process, which is excluded from
 * the TUN via addDisallowedApplication(), so proxied traffic always leaves via
 * the engine and never re-enters the VPN.
 *
 * Security note: both listeners accept connections from ANY device on the
 * local network while sharing is enabled. The UI warns the user accordingly
 * and sharing is OFF by default.
 */
object ShareBridge {

    /**
     * FIXED local proxy ports. These NEVER change at runtime: users type them
     * once into another app (Psiphon, Telegram, a browser) and the address
     * keeps working across every reconnect.
     *
     * ### Why these are not the "classic" proxy ports
     *
     * The well-known 10808/10809 pair is claimed by several mainstream VPN
     * clients (SOCKS5/HTTP defaults), so users running more than one proxy
     * app at once would hit hard collisions:
     *
     *  - If another client bound first, SoildTunnel's sharing/proxy mode failed
     *    with EADDRINUSE and the user saw "could not open the proxy ports".
     *  - If SoildTunnel bound first, the other client failed to start — reported
     *    by users as one app "breaking" the other.
     *  - Worst case, an app configured for 127.0.0.1:10808 silently sent its
     *    traffic into whichever tunnel happened to own the port that minute —
     *    a routing conflict with real privacy consequences.
     *
     * SoildTunnel sits one slot up at **10810/10811**, which sit in the same
     * easy-to-remember block but are claimed by no mainstream client, and adds
     * an explicit pre-bind conflict check ([describePortHolder]) so a genuine
     * collision is reported in plain language instead of a bare stack trace.
     *
     * Note the engine's own SOCKS5 listener (127.0.0.1:1819, see TunnelConfig)
     * never overlapped with any mainstream client and is unchanged.
     */
    const val SOCKS_SHARE_PORT = 10810
    const val HTTP_SHARE_PORT = 10811

    /**
     * Ports owned by well-known neighbouring tunnels. Used only to produce a
     * helpful diagnostic message — SoildTunnel never binds these.
     */
    private val KNOWN_NEIGHBOUR_PORTS = mapOf(
        10808 to "a VPN client (SOCKS5)",
        10809 to "a VPN client (HTTP)",
        7890 to "Clash (mixed)",
        1080 to "Psiphon / generic SOCKS",
        8118 to "Privoxy",
    )

    private const val TAG = "share"
    private const val MAX_HEADER_BYTES = 64 * 1024
    private const val DIAL_TIMEOUT_MS = 10_000

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    /** Actual bound ports for the current session (null while that listener is down). */
    private val _socksPort = MutableStateFlow<Int?>(null)
    val socksPort: StateFlow<Int?> = _socksPort.asStateFlow()

    private val _httpPort = MutableStateFlow<Int?>(null)
    val httpPort: StateFlow<Int?> = _httpPort.asStateFlow()

    /**
     * Cumulative byte counters for THIS sharing session (reset on every
     * [startSync]). In proxy mode the system TUN (and therefore
     * hev-socks5-tunnel's stats API) is not running, so these counters are the
     * ONLY source of download/upload numbers for the traffic meter.
     *
     * Direction mapping matches HevTunnel.traffic():
     *  - upload   = bytes from proxy clients relayed INTO the engine (device -> internet)
     *  - download = bytes from the engine relayed BACK to proxy clients (internet -> device)
     */
    private val uploadBytesCounter = AtomicLong(0L)
    private val downloadBytesCounter = AtomicLong(0L)

    /** Snapshot of the bridge's cumulative session traffic. */
    data class Traffic(val downloadBytes: Long, val uploadBytes: Long)

    fun traffic(): Traffic = Traffic(
        downloadBytes = downloadBytesCounter.get(),
        uploadBytes = uploadBytesCounter.get(),
    )

    private var socksServer: ServerSocket? = null
    private var httpServer: ServerSocket? = null

    /**
     * Monotonic session id. Every [startSync] / [stop] bumps it, so a stale
     * asynchronous stop can never close the listeners of a NEWER session —
     * the race that used to fail rebinding with EADDRINUSE or leave
     * "sharing ON" with already-dead sockets after a quick reconnect.
     */
    private var session = 0

    /** Bind address for the current session: loopback-only or all interfaces. */
    @Volatile
    private var bindHost = "127.0.0.1"

    /**
     * Turn sharing on. Safe to call from ANY thread — including the UI thread:
     * binding sockets is a network operation and Android throws
     * NetworkOnMainThreadException when it happens on the main thread, so the
     * actual work runs on a short-lived background thread and [active] flips
     * to true once both listeners are ready.
     */
    fun start(localOnly: Boolean = false) {
        thread(name = "share-start", isDaemon = true) { startSync(localOnly) }
    }

    /**
     * Turn sharing on and WAIT until the listeners are bound. Returns true when
     * BOTH fixed listeners (SOCKS5 and HTTP) are actually accepting connections.
     * MUST be called from a background thread (binding is a network operation).
     *
     * Unlike the old fire-and-forget start, callers such as the VpnService can
     * use the return value as ground truth instead of assuming success — in
     * proxy mode these listeners ARE the product, so a swallowed bind failure
     * meant "connected" with nothing listening on 1080/8118.
     */
    fun startSync(localOnly: Boolean = false): Boolean = synchronized(this) {
        // Already up with a healthy listener? Nothing to do.
        if (_active.value && (socksServer?.isClosed == false || httpServer?.isClosed == false)) {
            return@synchronized true
        }

        // New session: invalidates any in-flight async [stop] and clears
        // leftovers so rebinding is deterministic.
        session++
        closeServers()
        uploadBytesCounter.set(0L)
        downloadBytesCounter.set(0L)

        // SECURITY: when not explicitly sharing to the LAN, bind loopback
        // only so no other device on the network can use us as an open
        // proxy. LAN exposure is opt-in via the user's "share" toggle.
        bindHost = if (localOnly) "127.0.0.1" else "0.0.0.0"

        // Bind the FIXED standard ports. NO fallback: the address users typed
        // into other apps must never silently change between sessions. A short
        // retry loop absorbs a transient EADDRINUSE from a just-closed listener
        // (TIME_WAIT / async teardown); a genuinely occupied port fails loudly.
        // : log which neighbouring tunnels are live BEFORE binding, so a
        // port clash is diagnosable from the in-app log alone.
        reportNeighbours()

        socksServer = bindWithRetry("SOCKS5", SOCKS_SHARE_PORT)
        httpServer = bindWithRetry("HTTP", HTTP_SHARE_PORT)
        _socksPort.value = socksServer?.localPort
        _httpPort.value = httpServer?.localPort

        if (socksServer == null || httpServer == null) {
            DiagnosticsLog.e(
                TAG,
                "Could not open the fixed proxy ports ($SOCKS_SHARE_PORT/$HTTP_SHARE_PORT) — " +
                    "close the app holding them and reconnect.",
            )
            closeServers()
            _active.value = false
            return@synchronized false
        }

        socksServer?.let { server -> acceptLoop("share-socks", server) { relayToLocalSocks(it) } }
        httpServer?.let { server -> acceptLoop("share-http", server) { serveHttpClient(it) } }
        _active.value = true

        val scope = if (bindHost == "127.0.0.1") "loopback only" else "all interfaces (LAN)"
        DiagnosticsLog.i(
            TAG,
            "Sharing ON — SOCKS5 :${_socksPort.value ?: "unavailable"} + HTTP :${_httpPort.value ?: "unavailable"} ($scope)",
        )
        true
    }

    /** Turn sharing off. Safe to call from any thread. */
    fun stop() {
        _active.value = false
        val stopSession = synchronized(this) { ++session }
        thread(name = "share-stop", isDaemon = true) {
            synchronized(this) {
                // Only close if no NEWER session started meanwhile: a stale
                // async stop must never kill a fresh session's listeners.
                if (session == stopSession) {
                    val hadServers = socksServer != null || httpServer != null
                    closeServers()
                    if (hadServers) DiagnosticsLog.i(TAG, "Sharing OFF")
                }
            }
        }
    }

    /** Best local (site-local IPv4) address other devices can reach us on. */
    fun lanAddress(): String? =
        runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                .filterNot { it.name.startsWith("tun") || it.name.startsWith("ppp") }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()

    // ------------------------------------------------------------- internals

    private fun bind(port: Int): ServerSocket =
        ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(bindHost, port), 32)
        }

    /**
     * Binds a FIXED port, retrying briefly so a listener that is still being
     * torn down by the previous session never pushes users onto a different
     * port. The port either opens or sharing fails loudly — it NEVER moves.
     */
    private fun bindWithRetry(
        label: String,
        port: Int,
        attempts: Int = 10,
        delayMs: Long = 300,
    ): ServerSocket? {
        var lastError: Exception? = null
        repeat(attempts) { attempt ->
            try {
                return bind(port)
            } catch (e: Exception) {
                lastError = e
                if (attempt < attempts - 1) Thread.sleep(delayMs)
            }
        }
        DiagnosticsLog.e(
            TAG,
            "$label port $port is busy${describePortHolder(port)}: $lastError",
        )
        return null
    }

    /** Names the usual suspect for [port], for human-readable diagnostics. */
    private fun describePortHolder(port: Int): String =
        KNOWN_NEIGHBOUR_PORTS[port]?.let { " — this port belongs to $it" }
            ?: " (held by another app?)"

    /**
     * Detects other local tunnels listening on the classic proxy ports and
     * notes them in the log. Purely informational:  deliberately binds
     * ports nobody else claims, so co-existing with other proxy apps is expected
     * to just work — this line simply proves it in the diagnostics.
     */
    private fun reportNeighbours() {
        val live = KNOWN_NEIGHBOUR_PORTS.filterKeys { port ->
            runCatching {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 120) }
                true
            }.getOrDefault(false)
        }.values.distinct()
        if (live.isEmpty()) return
        DiagnosticsLog.i(
            TAG,
            "Other local proxies detected (${live.joinToString(", ")}) — SoildTunnel uses " +
                "$SOCKS_SHARE_PORT/$HTTP_SHARE_PORT, so they can run side by side.",
        )
    }

    private fun closeServers() {
        runCatching { socksServer?.close() }
        socksServer = null
        runCatching { httpServer?.close() }
        httpServer = null
        _socksPort.value = null
        _httpPort.value = null
    }

    private fun acceptLoop(name: String, server: ServerSocket, handler: (Socket) -> Unit) {
        thread(name = name, isDaemon = true) {
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (_: Exception) {
                    break // server closed -> sharing stopped
                }
                thread(name = "$name-conn", isDaemon = true) {
                    try {
                        client.tcpNoDelay = true
                        handler(client)
                    } catch (_: Exception) {
                        // Per-connection errors are non-fatal by design.
                    } finally {
                        runCatching { client.close() }
                    }
                }
            }
        }
    }

    /** SOCKS5 share = byte-for-byte relay into the engine's loopback SOCKS5. */
    private fun relayToLocalSocks(client: Socket) {
        val upstream = Socket()
        try {
            upstream.tcpNoDelay = true
            upstream.connect(
                InetSocketAddress(TunnelConfig.SOCKS_HOST, TunnelConfig.SOCKS_PORT),
                DIAL_TIMEOUT_MS,
            )
            relay(client, upstream)
        } finally {
            runCatching { upstream.close() }
        }
    }

    /** Minimal HTTP proxy: CONNECT tunnels + absolute-form plain requests. */
    private fun serveHttpClient(client: Socket) {
        val input = client.getInputStream()
        val header = readHeaderBlock(input) ?: return
        val lines = header.toString(Charsets.ISO_8859_1.name()).split("\r\n")
        val requestLine = lines.firstOrNull().orEmpty()
        val parts = requestLine.split(" ")
        if (parts.size < 3) return

        val method = parts[0]
        val target = parts[1]

        if (method.equals("CONNECT", ignoreCase = true)) {
            val host = target.substringBeforeLast(':')
            val port = target.substringAfterLast(':').toIntOrNull() ?: 443
            val upstream = socksOpen(host, port) ?: run {
                client.getOutputStream().writeAscii("HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n")
                return
            }
            try {
                client.getOutputStream().writeAscii("HTTP/1.1 200 Connection Established\r\n\r\n")
                relay(client, upstream)
            } finally {
                runCatching { upstream.close() }
            }
            return
        }

        // Plain HTTP with an absolute URI, e.g. "GET http://example.com/x HTTP/1.1".
        val url = target.removePrefix("http://")
        if (url == target) { // https:// or malformed — TLS must use CONNECT
            client.getOutputStream().writeAscii("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n")
            return
        }
        val hostPort = url.substringBefore('/')
        val path = "/" + url.substringAfter('/', "")
        val host = hostPort.substringBefore(':')
        val port = hostPort.substringAfter(':', "80").toIntOrNull() ?: 80

        val upstream = socksOpen(host, port) ?: run {
            client.getOutputStream().writeAscii("HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n")
            return
        }
        try {
            val rebuilt = buildString {
                append("$method $path ${parts[2]}\r\n")
                lines.drop(1).forEach { line ->
                    if (line.isEmpty()) return@forEach
                    val lower = line.lowercase()
                    if (lower.startsWith("proxy-connection:") ||
                        lower.startsWith("proxy-authorization:") ||
                        lower.startsWith("connection:")
                    ) {
                        return@forEach
                    }
                    append(line).append("\r\n")
                }
                append("Connection: close\r\n\r\n")
            }
            upstream.getOutputStream().writeAscii(rebuilt)
            uploadBytesCounter.addAndGet(rebuilt.length.toLong())
            relay(client, upstream)
        } finally {
            runCatching { upstream.close() }
        }
    }

    /** Opens a TCP stream to host:port THROUGH the engine's SOCKS5 proxy. */
    private fun socksOpen(host: String, port: Int): Socket? {
        val socket = Socket()
        return try {
            socket.tcpNoDelay = true
            socket.connect(
                InetSocketAddress(TunnelConfig.SOCKS_HOST, TunnelConfig.SOCKS_PORT),
                DIAL_TIMEOUT_MS,
            )
            socket.soTimeout = 30_000
            val out = socket.getOutputStream()
            val inp = socket.getInputStream()

            // Greeting: version 5, one method, no-auth.
            out.write(byteArrayOf(0x05, 0x01, 0x00))
            out.flush()
            val greet = inp.readExact(2)
            if (greet == null || greet[0] != 5.toByte() || greet[1] != 0.toByte()) {
                throw IllegalStateException("SOCKS5 greeting failed")
            }

            // CONNECT with a DOMAIN address -> DNS resolves inside the tunnel.
            val hostBytes = host.toByteArray(Charsets.ISO_8859_1)
            val request = ByteArrayOutputStream().apply {
                write(byteArrayOf(0x05, 0x01, 0x00, 0x03))
                write(hostBytes.size)
                write(hostBytes)
                write((port shr 8) and 0xFF)
                write(port and 0xFF)
            }
            out.write(request.toByteArray())
            out.flush()

            val reply = inp.readExact(4) ?: throw IllegalStateException("SOCKS5 reply truncated")
            if (reply[1] != 0.toByte()) throw IllegalStateException("SOCKS5 connect refused (${reply[1]})")
            val remaining = when (reply[3].toInt()) {
                0x01 -> 4 + 2
                0x03 -> (inp.readExact(1)?.get(0)?.toInt()?.and(0xFF)
                    ?: throw IllegalStateException("SOCKS5 reply truncated")) + 2
                0x04 -> 16 + 2
                else -> throw IllegalStateException("Bad SOCKS5 address type")
            }
            inp.readExact(remaining) ?: throw IllegalStateException("SOCKS5 reply truncated")

            socket.soTimeout = 0
            socket
        } catch (e: Exception) {
            DiagnosticsLog.e(TAG, "Upstream dial failed for $host:$port — $e")
            runCatching { socket.close() }
            null
        }
    }

    /** Reads raw bytes up to and including the CRLFCRLF header terminator. */
    private fun readHeaderBlock(input: InputStream): ByteArrayOutputStream? {
        val buf = ByteArrayOutputStream()
        var run = 0
        while (buf.size() < MAX_HEADER_BYTES) {
            val b = input.read()
            if (b < 0) return null
            buf.write(b)
            run = when {
                b == '\r'.code && (run == 0 || run == 2) -> run + 1
                b == '\n'.code && (run == 1 || run == 3) -> run + 1
                else -> 0
            }
            if (run == 4) return buf
        }
        return null
    }

    /**
     * Full-duplex pipe between the proxy client and the engine; returns when
     * either side ends. Every relayed byte is added to the session traffic
     * counters so the UI meter works in proxy mode too:
     * client -> upstream = upload, upstream -> client = download.
     */
    private fun relay(client: Socket, upstream: Socket) {
        val reverse = thread(isDaemon = true) { pipe(upstream, client, downloadBytesCounter) }
        pipe(client, upstream, uploadBytesCounter)
        runCatching { reverse.join(1_000) }
    }

    private fun pipe(from: Socket, to: Socket, counter: AtomicLong) {
        val buffer = ByteArray(16 * 1024)
        try {
            val input = from.getInputStream()
            val output = to.getOutputStream()
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                output.write(buffer, 0, n)
                output.flush()
                counter.addAndGet(n.toLong())
            }
        } catch (_: Exception) {
        } finally {
            runCatching { to.shutdownOutput() }
            runCatching { from.shutdownInput() }
        }
    }

    private fun InputStream.readExact(n: Int): ByteArray? {
        val out = ByteArray(n)
        var done = 0
        while (done < n) {
            val r = read(out, done, n - done)
            if (r < 0) return null
            done += r
        }
        return out
    }

    private fun OutputStream.writeAscii(s: String) {
        write(s.toByteArray(Charsets.ISO_8859_1))
        flush()
    }
}
