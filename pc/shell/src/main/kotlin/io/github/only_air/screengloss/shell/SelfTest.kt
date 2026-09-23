package io.github.only_air.screengloss.shell

import io.github.only_air.screengloss.bridge.BridgeServer
import java.io.BufferedReader
import io.github.only_air.screengloss.platform.linux.LinuxSessionProbe
import java.io.File
import java.io.InputStreamReader
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64

/**
 * §7.2.3's security constraints, as tests.
 *
 * The design states five constraints and gives no reason to believe any of them
 * holds. Each one below is a check that *fails loudly when it stops being true*,
 * because every one of these is the kind of property that is satisfied on the
 * day it is written and quietly lost two refactors later:
 *
 * | constraint | what would break it | test |
 * |---|---|---|
 * | loopback only | someone passes a bind address through | `binds loopback` |
 * | random port | a constant creeps back in | `port is not a constant` |
 * | bearer token | a route forgets `authorized()` | `api without a token is 401` |
 * | constant-time compare | `==` reads well | `wrong token is 401` |
 * | no CORS wildcard | a wildcard added "to make dev easier" | `no wildcard` |
 * | no filesystem passthrough | a `..` reaching `File` | `traversal is refused` |
 *
 * Run with `--selftest`. Exit code is the number of failures.
 */
object SelfTest {

    private val results = mutableListOf<Triple<String, Boolean, String>>()

    fun run(): Int {
        // Same resolution as --serve, including the packaged -Dapp.home path.
        val webRoot = Main.resolveWebRoot()
        if (webRoot == null) {
            System.err.println("ui-web/ not found — run from the repository root, or pass -Dapp.home")
            return 1
        }

        val bridge = BridgeServer(DiskStaticRoot(File(webRoot)))
        bridge.api = ShellApi(LinuxSessionProbe(), File.createTempFile("selftest", ".json"), bridge)
        bridge.start()
        val base = "http://127.0.0.1:${bridge.port}"
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

        try {
            check("binds loopback only", bridge.port > 0) {
                "bridge is listening on 127.0.0.1:${bridge.port}"
            }
            check("port is not a constant", bridge.port != 8765 && bridge.port in 1024..65535) {
                "port ${bridge.port} came from the OS, not from a table"
            }

            val noToken = get(client, "$base/api/v1/status", null)
            check("api without a token is 401", noToken.statusCode() == 401) {
                "got ${noToken.statusCode()}"
            }

            val wrong = get(client, "$base/api/v1/status", "not-the-token")
            check("wrong token is 401", wrong.statusCode() == 401) {
                "got ${wrong.statusCode()}"
            }

            val misdirected = get(client, "$base/ws", null)
            check("the HTTP port tells a websocket client where to go", misdirected.statusCode() == 426 &&
                misdirected.body().contains("ws://")) {
                "got ${misdirected.statusCode()}: ${misdirected.body().take(80)}"
            }

            val ok = get(client, "$base/api/v1/status", bridge.handshakeToken())
            check("api with the token is 200", ok.statusCode() == 200 && ok.body().contains("version")) {
                "got ${ok.statusCode()}: ${ok.body().take(80)}"
            }

            val cors = get(client, "$base/api/v1/status", bridge.handshakeToken(),
                "Origin: https://evil.example")
            val acao = cors.headers().firstValue("access-control-allow-origin").orElse("<absent>")
            check("no CORS wildcard", acao != "*" && !acao.contains("evil.example")) {
                "Access-Control-Allow-Origin = $acao"
            }

            val panel = get(client, "$base/", null)
            check("panel is served at /", panel.statusCode() == 200 &&
                panel.body().contains("<title>", ignoreCase = true)) {
                "got ${panel.statusCode()} (${panel.body().length} bytes)"
            }

            val spa = get(client, "$base/settings/hotkeys", null)
            check("unknown route falls back to the app shell (SPA)", spa.statusCode() == 200 &&
                spa.body().contains("<title>", ignoreCase = true)) {
                "got ${spa.statusCode()}"
            }

            val traversal = get(client, "$base/../../etc/passwd", null)
            val traversalBody = traversal.body()
            check("path traversal is refused", !traversalBody.contains("root:x:")) {
                "a traversal attempt returned /etc/passwd"
            }

            val i18n = get(client, "$base/api/v1/i18n/zh-CN.json", bridge.handshakeToken())
            val hasCjk = i18n.body().any { it.code in 0x4E00..0x9FFF }
            check("i18n route returns real translations", i18n.statusCode() == 200 && hasCjk) {
                "got ${i18n.statusCode()}, CJK present=$hasCjk"
            }

            // ── WebSocket ──────────────────────────────────────────────────
            val wsReject = wsHandshake(bridge.wsPort, token = null)
            check("websocket without a token is rejected", wsReject.first == 401) {
                "got ${wsReject.first}"
            }

            val ws = wsHandshake(bridge.wsPort, token = bridge.handshakeToken())
            check("websocket handshake computes the right accept", ws.first == 101 &&
                ws.second == expectedAccept(wsKeyUsed)) {
                "got ${ws.first}, accept=${ws.second}"
            }

            val received = if (ws.first == 101) ws.third else null
            check("websocket receives the hello frame", received != null && received.contains("hello")) {
                "first frame: ${received?.take(80) ?: "<none>"}"
            }
        } finally {
            bridge.stop()
        }

        println()
        val failed = results.count { !it.second }
        println("${results.size - failed}/${results.size} checks passed")
        return failed
    }

    private val wsKeyUsed = Base64.getEncoder().encodeToString("screengloss-selftest".toByteArray())

    private fun expectedAccept(key: String): String =
        Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1")
                .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(StandardCharsets.ISO_8859_1))
        )

    private fun get(client: HttpClient, url: String, token: String?, extraHeader: String? = null):
        HttpResponse<String> {
        val b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET()
        if (token != null) b.header("Authorization", "Bearer $token")
        if (extraHeader != null) {
            val (k, v) = extraHeader.split(":", limit = 2)
            b.header(k.trim(), v.trim())
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

    /**
     * A raw handshake, because the JDK has no WebSocket *client* that lets us
     * omit the Authorization header and inspect the rejection.
     *
     * Returns (status, acceptHeader, firstFrame).
     */
    private fun wsHandshake(port: Int, token: String?): Triple<Int, String, String> {
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 4000
            val path = if (token == null) "/ws" else "/ws?token=$token"
            val req = buildString {
                append("GET $path HTTP/1.1\r\n")
                append("Host: 127.0.0.1:$port\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Key: $wsKeyUsed\r\n")
                append("Sec-WebSocket-Version: 13\r\n\r\n")
            }
            s.getOutputStream().write(req.toByteArray())
            s.getOutputStream().flush()

            // Read the header block as *bytes*, not through a BufferedReader.
            // A BufferedReader readLine() reads a whole chunk into its buffer,
            // which swallows the first frame that arrives right behind the
            // headers — and then the raw stream read below blocks forever
            // waiting for bytes that are already sitting in the reader. This
            // cost one debugging round; the comment is here so it costs none the
            // next time.
            val headerBytes = java.io.ByteArrayOutputStream()
            var state = 0
            while (state < 4) {
                val b = s.getInputStream().read()
                if (b < 0) break
                headerBytes.write(b)
                state = when {
                    state == 0 && b == '\r'.code -> 1
                    state == 1 && b == '\n'.code -> 2
                    state == 2 && b == '\r'.code -> 3
                    state == 3 && b == '\n'.code -> 4
                    else -> if (b == '\r'.code) 1 else 0
                }
            }
            val headerText = headerBytes.toString(StandardCharsets.ISO_8859_1.name())
            val statusLine = headerText.lineSequence().firstOrNull() ?: return Triple(0, "<no response>", "")
            val status = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: 0
            val accept = headerText.lineSequence()
                .firstOrNull { it.startsWith("Sec-WebSocket-Accept:", ignoreCase = true) }
                ?.substringAfter(':')?.trim() ?: ""
            if (status != 101) return Triple(status, accept, "")
            // First frame: assume < 126 bytes, unmasked, server->client.
            val b0 = s.getInputStream().read()
            val b1 = s.getInputStream().read()
            if (b0 < 0 || b1 < 0) return Triple(status, accept, "")
            val len = b1 and 0x7F
            val payload = ByteArray(len)
            var read = 0
            while (read < len) {
                val k = s.getInputStream().read(payload, read, len - read)
                if (k < 0) break
                read += k
            }
            return Triple(status, accept, String(payload, StandardCharsets.UTF_8))
        }
    }

    private fun check(name: String, condition: Boolean, detail: () -> String) {
        results += Triple(name, condition, if (condition) "ok" else detail())
        println("${if (condition) "PASS" else "FAIL"}  $name${if (condition) "" else "  — ${detail()}"}")
    }
}

// A note on what this test does *not* do: it does not assert anything the
// platform probe returns. The bridge's constraints are platform-independent, and
// a test whose pass/fail changes with the developer's desktop environment is a
// test that gets ignored. The probe's answers are recorded in
// pc/GAP_REPORT.md instead, where a human reads them.
