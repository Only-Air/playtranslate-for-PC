package io.github.only_air.screengloss.bridge

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executors

/**
 * The WebSocket listener, on its own loopback socket.
 *
 * ## Why this is not a route on [BridgeServer]'s HTTP server
 *
 * The obvious shape — `server.createContext("/ws")`, `sendResponseHeaders(101, -1)`
 * — does not work, and it fails in a way worth writing down because the symptom
 * is misleading. The JDK's `HttpExchange` documents `responseLength == -1` as
 * "no response body is being sent", so the stream handed back after a 101
 * accepts the `Sec-WebSocket-Accept` header and then swallows every frame
 * written to it. The client sees a perfect handshake and never a byte more.
 *
 * The first version of this bridge did exactly that, and the self-test caught it
 * (`websocket receives the hello frame` timed out with the headers already
 * parsed). The two ways out were a hand-rolled HTTP server on one socket, or an
 * upgraded connection on a socket of its own. This is the second: it keeps
 * `com.sun.net.httpserver` for the part it is good at — REST routing, static
 * files, header handling — and takes the one thing it cannot do onto a raw
 * `ServerSocket`.
 *
 * The cost is a second port. That is a real cost and it is paid explicitly: the
 * panel gets both ports in its launch fragment, and `/ws` on the HTTP server
 * answers `426 Upgrade Required` with the port to use, so a client that guesses
 * the wrong one is told where to go instead of hanging.
 *
 * Security is unchanged and is enforced in exactly one place: the token check
 * happens *before* the handshake is computed, on the same constant-time compare,
 * against the same per-launch secret. A WebSocket cannot carry an
 * `Authorization` header from a browser, which is why the token is a query
 * parameter here and a header there — the value is the same value.
 */
class WebSocketEndpoint(
    private val token: String,
    private val onLog: (String) -> Unit = {},
) {

    private val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
    private val pool = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "bridge-ws").apply { isDaemon = true }
    }
    private val connections = mutableListOf<WebSocketConnection>()
    private var running = true

    val port: Int get() = server.localPort

    fun start() {
        check(server.inetAddress.isLoopbackAddress) {
            "the websocket endpoint must bind loopback, got ${server.inetAddress}"
        }
        pool.submit {
            while (running) {
                val socket = try {
                    server.accept()
                } catch (_: Throwable) {
                    break  // closed
                }
                pool.submit { handle(socket) }
            }
        }
    }

    fun stop() {
        running = false
        runCatching { server.close() }
        synchronized(connections) { connections.toList() }.forEach { it.close() }
        synchronized(connections) { connections.clear() }
        pool.shutdownNow()
    }

    fun broadcast(json: String) {
        synchronized(connections) { connections.toList() }.forEach { it.send(json) }
    }

    /** Number of live panel connections. Reported by `/api/v1/status`. */
    val connectionCount: Int get() = synchronized(connections) { connections.size }

    private fun handle(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 0
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
            val requestLine = reader.readLine() ?: return
            val path = requestLine.split(' ').getOrNull(1) ?: return
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: return
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }

            val query = path.substringAfter('?', "")
            val presented = query.split('&')
                .firstOrNull { it.startsWith("token=") }?.removePrefix("token=")
            if (presented == null || !constantTimeEquals(presented, token)) {
                writeResponse(s, 401, "Unauthorized")
                return
            }
            val key = headers["sec-websocket-key"]
            if (key == null) {
                writeResponse(s, 400, "Expected a websocket handshake")
                return
            }

            val accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1")
                    .digest((key + GUID).toByteArray(Charsets.ISO_8859_1))
            )
            val response = buildString {
                append("HTTP/1.1 101 Switching Protocols\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Accept: $accept\r\n")
                append("\r\n")
            }
            s.getOutputStream().write(response.toByteArray(Charsets.ISO_8859_1))
            s.getOutputStream().flush()

            val conn = WebSocketConnection(s.getOutputStream())
            synchronized(connections) { connections += conn }
            conn.send("""{"type":"hello","wsPort":$port}""")
            onLog("panel connected on the websocket ($path)")
            try {
                // Note: `reader` has already buffered past the headers, so the
                // frame reader must be fed from the same reader, not from
                // s.getInputStream() directly — the same trap the self-test hit.
                conn.pump(InputStreamAdapter(reader, s))
            } finally {
                synchronized(connections) { connections -= conn }
                onLog("panel disconnected")
            }
        }
    }

    private fun writeResponse(s: Socket, code: Int, body: String) {
        val text = "HTTP/1.1 $code ${if (code == 401) "Unauthorized" else "Bad Request"}\r\n" +
            "Content-Type: text/plain\r\n" +
            "Content-Length: ${body.length}\r\n" +
            "X-Content-Type-Options: nosniff\r\n" +
            "Connection: close\r\n\r\n$body"
        runCatching {
            s.getOutputStream().write(text.toByteArray(Charsets.UTF_8))
            s.getOutputStream().flush()
        }
    }

    companion object {
        private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

        private fun constantTimeEquals(a: String, b: String): Boolean {
            val x = a.toByteArray(Charsets.UTF_8)
            val y = b.toByteArray(Charsets.UTF_8)
            if (x.size != y.size) return false
            var diff = 0
            for (i in x.indices) diff = diff or (x[i].toInt() xor y[i].toInt())
            return diff == 0
        }
    }
}

/**
 * Feeds the frame reader from a `BufferedReader`'s own buffer first.
 *
 * `BufferedReader.readLine()` reads a chunk of the socket, not a line, so any
 * frame that arrived in the same TCP segment is already inside the reader when
 * the handshake ends. Reading `socket.getInputStream()` from that point returns
 * nothing until the *next* segment — which, for a server that speaks only when
 * it has something to say, is never. This adapter drains what the reader already
 * holds and only then touches the socket.
 */
private class InputStreamAdapter(
    private val reader: BufferedReader,
    private val socket: Socket,
) : java.io.InputStream() {
    override fun read(): Int {
        if (reader.ready()) {
            val c = reader.read()
            if (c >= 0) return c and 0xFF
        }
        return socket.getInputStream().read()
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        var n = 0
        while (n < len && reader.ready()) {
            val c = reader.read()
            if (c < 0) break
            b[off + n] = c.toByte()
            n++
        }
        if (n > 0) return n
        return socket.getInputStream().read(b, off, len)
    }
}
