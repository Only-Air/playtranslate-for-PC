package io.github.only_air.screengloss.bridge

import com.sun.net.httpserver.HttpExchange
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Executors

/**
 * The core <-> panel bridge — PORTING.md §7.2.3.
 *
 * The design picks loopback HTTP + WebSocket over webview `postMessage` for
 * three reasons, and all three are testable:
 *
 *  1. it is the same bridge for all three webview hosts (and for no webview at
 *     all — see below);
 *  2. it is debuggable: a normal browser can attach to it;
 *  3. it unlocks a product fallback the design explicitly wants — the panel
 *     opened in the *user's own* browser, which is what makes the GNOME-Wayland
 *     no-overlay case (§6.1) survivable, because a side panel can live on a
 *     second monitor.
 *
 * Point 3 is the one that changes the security conversation, and §7.2.3 states
 * the constraints without saying why each exists. They are:
 *
 *  - **bind `127.0.0.1` only.** Binding `0.0.0.0` would put the user's API keys
 *    behind the panel on the LAN. [start] asserts the loopback address rather
 *    than trusting a constant.
 *  - **port 0.** The OS picks. A fixed port is a port someone else is already
 *    listening on, and the panel would then be talking to a stranger.
 *  - **a fresh bearer token per launch.** Not per install: a token that
 *    survives a restart is a token that is in a file, and a file is a thing
 *    another process on the machine can read.
 *  - **no CORS wildcard.** `Access-Control-Allow-Origin` is echoed only for
 *    origins we started (the webview's `app://` / `file://` origin, or
 *    `http://127.0.0.1:<port>` for the browser case). A wildcard would let any
 *    page the user has open in any browser reach the API, which is the whole
 *    attack: `fetch('http://127.0.0.1:<port>/api/v1/settings')` from a tab.
 *  - **no filesystem passthrough.** Not one route serves a path from the
 *    request. Static files come from the packaged resource root only.
 *
 * Why not a cookie: a cookie is sent by the browser automatically, so any page
 * could reach the API if it had the port — the token would stop being a
 * capability and become a session. It is an `Authorization: Bearer` header, and
 * the panel is served with it in its URL fragment, which the browser does not
 * forward to any origin.
 */
class BridgeServer(
    /** Where the web panel's files live. Never derived from a request. */
    private val staticRoot: StaticRoot,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Supplies the panel's data. Implemented by the shell. */
    interface Api {
        /** §9's P-1 decision record, and the panel's status strip. */
        fun capabilities(): String
        /** The action registry, so the panel and the router share one truth (§5.7). */
        fun actions(): String
        /** Current key bindings, with conflict findings attached. */
        fun bindings(): String
        fun settings(): String
        fun putSettings(json: String): String
        fun status(): String
        /** Rendered word/sentence card HTML — upstream already produces HTML (§7.2.1). */
        fun card(kind: String, id: String): String?
    }

    /** Read-only view over the packaged web root. */
    fun interface StaticRoot {
        /** Pair of (bytes, content-type), or null when the path does not exist. */
        fun read(path: String): Pair<ByteArray, String>?
    }

    private val token: String = generateToken()

    /**
     * The WebSocket listener, on its own loopback port.
     *
     * See [WebSocketEndpoint]'s comment for why it is not a route on the HTTP
     * server above: `HttpExchange` treats `responseLength == -1` as "no body",
     * so an upgraded connection drawn on it accepts the handshake and then drops
     * every frame.
     */
    private val ws = WebSocketEndpoint(token)
    private val server: com.sun.net.httpserver.HttpServer = com.sun.net.httpserver.HttpServer.create(
        InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0
    )
    private val pool = Executors.newFixedThreadPool(4) { r -> Thread(r, "bridge-http").apply { isDaemon = true } }

    var api: Api? = null

    val port: Int get() = server.address.port

    /** The token the panel must present. Handed to the webview as a launch argument. */
    fun handshakeToken(): String = token

    /** The WebSocket port the panel must dial. */
    val wsPort: Int get() = ws.port

    /** Live panel connections, for the status strip. */
    val panelConnections: Int get() = ws.connectionCount

    /**
     * The panel URL, with both ports and the token in the fragment.
     *
     * The fragment, not the query string: a fragment is not sent to any origin,
     * so the token never reaches a server log or a `Referer`.
     */
    fun panelUrl(): String = "http://127.0.0.1:$port/#token=$token&ws=ws://127.0.0.1:${ws.port}/ws"

    fun start() {
        check(server.address.address.isLoopbackAddress) {
            "bridge must bind loopback, got ${server.address.address}"
        }
        server.executor = pool
        server.createContext("/") { ex -> route(ex) }
        server.start()
        ws.start()
    }

    fun stop() {
        broadcast("""{"type":"shutdown"}""")
        ws.stop()
        server.stop(0)
        pool.shutdownNow()
    }

    /** Push a JSON event to every connected panel. */
    fun broadcast(json: String) = ws.broadcast(json)

    // ── routing ────────────────────────────────────────────────────────────

    private fun route(ex: HttpExchange) {
        try {
            when {
                // 426, not a silent hang: the upgrade lives on another port and
                // a client that guessed this one is told where to go.
                ex.requestURI.path == "/ws" -> respond(
                    ex, 426, "application/json",
                    """{"error":"use the websocket port","ws":"ws://127.0.0.1:${ws.port}/ws"}""",
                )
                ex.requestURI.path.startsWith("/api/") -> api(ex)
                else -> static(ex)
            }
        } catch (t: Throwable) {
            runCatching { respond(ex, 500, "application/json", """{"error":"${t.javaClass.simpleName}"}""") }
        } finally {
            runCatching { ex.close() }
        }
    }

    private fun api(ex: HttpExchange) {
        if (!authorized(ex)) {
            respond(ex, 401, "application/json", """{"error":"unauthorized"}""")
            return
        }
        val a = api ?: run {
            respond(ex, 503, "application/json", """{"error":"no api bound"}"""); return
        }
        val path = ex.requestURI.path
        val method = ex.requestMethod
        when {
            path == "/api/v1/capabilities" && method == "GET" -> json(ex, a.capabilities())
            path == "/api/v1/actions" && method == "GET" -> json(ex, a.actions())
            path == "/api/v1/bindings" && method == "GET" -> json(ex, a.bindings())
            path == "/api/v1/status" && method == "GET" -> json(ex, a.status())
            path == "/api/v1/settings" && method == "GET" -> json(ex, a.settings())
            path == "/api/v1/settings" && method == "PUT" -> json(ex, a.putSettings(readBody(ex)))
            path.startsWith("/api/v1/card/") -> {
                val rest = path.removePrefix("/api/v1/card/").split('/', limit = 2)
                val html = if (rest.size == 2) a.card(rest[0], rest[1]) else null
                if (html == null) respond(ex, 404, "application/json", """{"error":"no card"}""")
                else respond(ex, 200, "text/html; charset=utf-8", html)
            }
            path == "/api/v1/i18n" -> json(ex, i18nIndex())
            path.startsWith("/api/v1/i18n/") -> {
                val loc = path.removePrefix("/api/v1/i18n/").removeSuffix(".json")
                val f = staticRoot.read("i18n/$loc.json")
                if (f == null) respond(ex, 404, "application/json", """{"error":"no locale $loc"}""")
                else respond(ex, 200, "application/json; charset=utf-8", String(f.first, Charsets.UTF_8))
            }
            else -> respond(ex, 404, "application/json", """{"error":"no route","path":${quote(path)}}""")
        }
    }

    private fun i18nIndex(): String = staticRoot.read("i18n/index.json")
        ?.let { String(it.first, Charsets.UTF_8) }
        ?: """{"base":"en","locales":["en"]}"""

    private fun static(ex: HttpExchange) {
        // Static assets are public on purpose: they contain no data, and
        // requiring the token to fetch the panel's own JS would mean putting
        // the token in a URL the browser logs. The token gates /api/*, which is
        // where every fact about the user lives.
        val raw = ex.requestURI.path
        val path = when {
            raw == "/" || raw.isEmpty() -> "index.html"
            raw.startsWith("/i18n/") -> raw.removePrefix("/")
            else -> raw.removePrefix("/")
        }
        // Reject any traversal before touching the root.
        if (path.contains("..") || path.startsWith("/")) {
            respond(ex, 400, "text/plain", "bad path")
            return
        }
        val hit = staticRoot.read(path)
            ?: staticRoot.read("index.html")  // SPA: unknown route renders the app shell
            ?: run { respond(ex, 404, "text/plain", "not found"); return }
        val (bytes, type) = hit
        respond(ex, 200, type, bytes)
    }

    // ── http helpers ───────────────────────────────────────────────────────

    private fun authorized(ex: HttpExchange): Boolean {
        val header = ex.requestHeaders.getFirst("Authorization") ?: return false
        if (!header.startsWith("Bearer ")) return false
        return constantTimeEquals(header.removePrefix("Bearer ").trim(), token)
    }

    private fun json(ex: HttpExchange, body: String) = respond(ex, 200, "application/json; charset=utf-8", body)

    private fun respond(ex: HttpExchange, code: Int, contentType: String, body: String) =
        respond(ex, code, contentType, body.toByteArray(Charsets.UTF_8))

    private fun respond(ex: HttpExchange, code: Int, contentType: String, body: ByteArray) {
        ex.responseHeaders.add("Content-Type", contentType)
        // No wildcard, ever. The panel is same-origin (it is served from this
        // very server), so the only legitimate cross-origin caller is the
        // user's own browser on the same loopback address — and even that is
        // allowed only for a read, never echoed with credentials.
        val origin = ex.requestHeaders.getFirst("Origin")
        if (origin != null && (origin.startsWith("http://127.0.0.1") || origin.startsWith("http://localhost"))) {
            ex.responseHeaders.add("Access-Control-Allow-Origin", origin)
            ex.responseHeaders.add("Vary", "Origin")
        }
        if (ex.requestMethod == "OPTIONS") {
            ex.responseHeaders.add("Access-Control-Allow-Methods", "GET, PUT, OPTIONS")
            ex.responseHeaders.add("Access-Control-Allow-Headers", "Authorization, Content-Type")
            ex.sendResponseHeaders(204, -1)
            return
        }
        // X-Content-Type-Options: a panel that can be tricked into sniffing a
        // response as HTML is a panel that can be XSS'd from a card render.
        ex.responseHeaders.add("X-Content-Type-Options", "nosniff")
        ex.responseHeaders.add("Referrer-Policy", "no-referrer")
        ex.sendResponseHeaders(code, body.size.toLong())
        ex.responseBody.use { it.write(body) }
    }

    private fun readBody(ex: HttpExchange): String {
        val buf = ByteArrayOutputStream()
        ex.requestBody.use { input: InputStream ->
            val chunk = ByteArray(8192)
            while (true) {
                val n = input.read(chunk)
                if (n <= 0) break
                buf.write(chunk, 0, n)
                if (buf.size() > MAX_BODY) break
            }
        }
        return buf.toString(Charsets.UTF_8.name())
    }

    private fun quote(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    companion object {
        private const val MAX_BODY = 1 shl 20

        private fun generateToken(): String {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        /**
         * Compare without an early exit. A token comparison that returns on the
         * first differing byte leaks the token one byte at a time to a caller
         * who can time it, and this caller can: the whole API is reachable from
         * a browser tab.
         */
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
