package io.github.only_air.screengloss.shell

import io.github.only_air.screengloss.bridge.BridgeServer
import io.github.only_air.screengloss.core.action.Action
import io.github.only_air.screengloss.core.action.BindingSet
import io.github.only_air.screengloss.core.action.Chord
import io.github.only_air.screengloss.core.action.ConflictDetector
import io.github.only_air.screengloss.core.action.ReachabilityCheck
import io.github.only_air.screengloss.core.platform.CapabilityProbe
import io.github.only_air.screengloss.platform.linux.LinuxSessionProbe
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * The desktop entry point — the smallest thing that makes the architecture real.
 *
 * PORTING.md §7.3 lays out eleven modules. This file is the one that proves the
 * three seams it is easy to get wrong actually connect:
 *
 * ```
 *   core  (pure JVM: actions, bindings, conflicts, waterfall, geometry)
 *     |  CapabilityProbe.Api is filled from core, never from a platform
 *   shell (this file: lifecycle, CLI, the API the panel talks to)
 *     |  BridgeServer.Api
 *   bridge (loopback HTTP + WebSocket, §7.2.3)
 *     |  http://127.0.0.1:<random>/#token=...
 *   ui-web (the panel, §7.2.4's route table)
 * ```
 *
 * It also exists because the design has no runnable artifact at all: §9's P-1
 * milestone is defined as "each platform decides 能做 / 不能做 / 需降级 and writes
 * it to a decision record", and without a binary there is nowhere to run the
 * probe. `--probe` is that milestone, executable, on whatever machine you are
 * sitting at.
 */
object Main {

    @JvmStatic
    fun main(args: Array<String>) {
        // No arguments starts the application, not a help screen. A packaged
        // .desktop entry launches this binary with no arguments; printing usage
        // into a log nobody reads would be the worst available behaviour.
        val cmd = args.firstOrNull() ?: "--serve"
        when (cmd) {
            "--help", "-h" -> usage()
            "--probe" -> println(LinuxSessionProbe().render())
            "--probe-json" -> println(Json.probeJson(LinuxSessionProbe()))
            "--serve" -> serve(args.drop(1))
            "--selftest" -> exit(SelfTest.run())
            "--version" -> println("ScreenGloss ${Version.NUMBER} (PlayTranslate PC port, design stage)")
            else -> {
                System.err.println("unknown command: $cmd")
                usage()
                exit(2)
            }
        }
    }

    private fun usage() {
        println(
            """
            ScreenGloss — the PC port of PlayTranslate.

              --probe        print the P-1 capability decision record for this machine (PORTING §9)
              --probe-json   the same, as JSON for the panel
              --serve        start the core<->panel bridge and the web panel, print the URL
              --selftest     prove the bridge's security constraints hold (§7.2.3)
              --version      version

            The panel is a web app; --serve prints a URL. Open it in the bundled webview or in
            your own browser — the design (§7.2.3) wants the second to be possible.
            """.trimIndent()
        )
    }

    /**
     * Where the panel's files are.
     *
     * Three homes, in order, and the order matters:
     *
     *  1. `-Dapp.home=<lib dir>/ui-web` — what the packaged launcher sets
     *     (`pc/tools/make_payload.sh` copies the panel in beside the jar);
     *  2. `--web-root <dir>` — for a developer pointing at a checkout;
     *  3. the checkout-relative paths, for `./gradlew :shell:run`.
     *
     * The first version of this only had (2) and (3), which meant the *packaged*
     * build could start the bridge and then fail to serve the panel — the one
     * configuration that matters most. `--selftest` caught it. Resolution is one
     * function now so the two call sites cannot disagree again.
     */
    fun resolveWebRoot(override: String? = null): String? {
        if (override != null && File(override).isDirectory) return File(override).path
        val home = System.getProperty("app.home")
        if (home != null) {
            val packaged = File(home, "ui-web")
            if (packaged.isDirectory) return packaged.path
        }
        for (candidate in listOf("pc/ui-web", "../ui-web", "ui-web")) {
            val dir = File(candidate)
            if (dir.isDirectory) return dir.path
        }
        return null
    }

    private fun serve(rest: List<String>) {
        val webRoot = resolveWebRoot(argValue(rest, "--web-root")) ?: run {
            System.err.println("cannot find ui-web/; pass --web-root <dir>")
            exit(2)
            return
        }
        val stateFile = File(argValue(rest, "--state") ?: defaultStatePath())

        val bridge = BridgeServer(DiskStaticRoot(File(webRoot)))
        bridge.api = ShellApi(LinuxSessionProbe(), stateFile, bridge)
        bridge.start()

        println("panel:  ${bridge.panelUrl()}")
        println("bridge: http://127.0.0.1:${bridge.port} (loopback only, bearer token, no CORS wildcard)")
        println(LinuxSessionProbe().render())
        Runtime.getRuntime().addShutdownHook(Thread { bridge.stop() })
        // A real shell would host a webview here (§7.2.2: JCEF) and an event loop
        // for tray/hotkeys/overlay. Neither exists yet, and pretending otherwise
        // by busy-looping would be worse than saying so.
        println("webview host, tray, hotkeys and overlay are not implemented — see pc/GAP_REPORT.md")
        Thread.currentThread().join()
    }

    private fun argValue(args: List<String>, name: String): String? =
        args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }

    private fun defaultStatePath(): String {
        val xdg = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
            ?: (System.getProperty("user.home") + "/.config")
        return "$xdg/screengloss/settings.json"
    }

    private fun exit(code: Int): Nothing = kotlin.system.exitProcess(code)

    object Version {
        const val NUMBER = "0.2.0"
    }
}

/**
 * The `BridgeServer.Api` implementation.
 *
 * Every method here answers from `core`. That is the point of the class: the
 * panel must not be able to reach a fact that the hotkey router does not also
 * see, because §5.7's whole argument is that the registry is the single source
 * of truth. If the panel rendered its own action list, the two would drift and
 * the drift would be invisible until a chord did nothing.
 */
class ShellApi(
    private val probe: LinuxSessionProbe,
    private val stateFile: File,
    private val bridge: BridgeServer,
) : BridgeServer.Api {

    private var settings: String = loadSettings()

    override fun capabilities(): String = Json.probeJson(probe)

    override fun actions(): String = Json.actions()

    override fun bindings(): String {
        val set = BindingSet.DEFAULT
        val findings = ConflictDetector.analyse(set, probe.detectDesktopEnvironment().toConflictEnv())
        val reach = ReachabilityCheck.check(set, trayEnabled = false)
        return Json.bindings(set, findings, reach)
    }

    override fun settings(): String = settings

    override fun putSettings(json: String): String {
        // Accept-and-store, validate-nothing: the schema belongs to the panel's
        // forms, and a bridge that re-validated them would be a second place
        // where a setting's shape is defined. What it does enforce is that the
        // file never leaves this directory and is never served back raw.
        stateFile.parentFile?.mkdirs()
        stateFile.writeText(json, Charsets.UTF_8)
        settings = json
        bridge.broadcast("""{"type":"settings","body":$json}""")
        return """{"ok":true}"""
    }

    override fun status(): String = buildString {
        append("""{"version":""").append(Json.str(Main.Version.NUMBER))
        append(""","session":""").append(Json.str(probe.detectSession().name))
        append(""","desktop":""").append(Json.str(probe.detectDesktopEnvironment().name))
        append(""","port":""").append(bridge.port)
        append(""","stateFile":""").append(Json.str(stateFile.absolutePath))
        append(""","panelLayers":{"web":["settings","word","history","workspace"],""")
        append(""""native":["overlay","region-picker","camera-preview"]}}""")
    }

    override fun card(kind: String, id: String): String? = when (kind) {
        // §7.2.1's佐证: the render side already produces HTML upstream
        // (AnkiCardCss, DefinitionsDocument, YomitanContentHtml — ~2700 lines
        // of string building). The port renders the same shapes as HTML for the
        // panel instead of for a WebView inside an Activity.
        "word" -> DemoCards.word(id)
        "sentence" -> DemoCards.sentence(id)
        else -> null
    }

    private fun loadSettings(): String =
        if (stateFile.isFile) runCatching { stateFile.readText() }.getOrDefault("{}") else "{}"
}

private fun LinuxSessionProbe.DesktopEnvironment.toConflictEnv(): ConflictDetector.DesktopEnvironment =
    when (this) {
        LinuxSessionProbe.DesktopEnvironment.KDE -> ConflictDetector.DesktopEnvironment.KDE_PLASMA
        LinuxSessionProbe.DesktopEnvironment.GNOME -> ConflictDetector.DesktopEnvironment.GNOME
        LinuxSessionProbe.DesktopEnvironment.XFCE -> ConflictDetector.DesktopEnvironment.XFCE
        else -> ConflictDetector.DesktopEnvironment.GNOME
    }

/**
 * Serves the panel from a directory on disk during development.
 *
 * The packaged application uses a classpath root instead, because a path from
 * the request must never reach the filesystem — §7.2.3's "不做任何文件系统直通".
 * This class is the one place that rule is relaxed, and it relaxes it by
 * *resolving under a fixed root and refusing anything that escapes* rather than
 * by trusting the caller.
 */
class DiskStaticRoot(private val root: File) : BridgeServer.StaticRoot {
    override fun read(path: String): Pair<ByteArray, String>? {
        val base = root.canonicalFile
        val f = File(base, path)
        // The check that matters: canonicalize, then require the result to be
        // under the root. `../` cannot survive this, whatever it is made of.
        if (!f.canonicalPath.startsWith(base.canonicalPath + File.separator)) return null
        if (!f.isFile) return null
        return f.readBytes() to contentType(path)
    }

    private fun contentType(path: String): String = when (path.substringAfterLast('.', "")) {
        "html" -> "text/html; charset=utf-8"
        "js" -> "text/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "json" -> "application/json; charset=utf-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "woff2" -> "font/woff2"
        else -> "application/octet-stream"
    }
}

/** Path sanity for the panel's own static assets, shared with the packaged root. */
object Paths {
    fun isSafe(path: String): Boolean =
        path.isNotEmpty() && !path.contains("..") && !path.startsWith("/") && !path.contains('\u0000')
}

/** Used by the classpath static root, kept here so both roots agree. */
fun Path.under(root: Path): Boolean = toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize())

/** A tiny JSON writer. No dependency, one escape function, no reflection. */
object Json {
    fun str(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        sb.append('"')
        return sb.toString()
    }

    fun actions(): String = buildString {
        append("""{"actions":[""")
        Action.entries.forEachIndexed { i, a ->
            if (i > 0) append(',')
            append("""{"id":""").append(str(a.id))
            append(""","group":""").append(str(a.group.name))
            append(""","trigger":""").append(str(a.trigger.name))
            append(""","pointerPolicy":""").append(str(a.pointerPolicy.name))
            append(""","defaultChords":[""")
            a.defaultChords.forEachIndexed { j, ch ->
                if (j > 0) append(',')
                append(str(ch.encode())).append("]".let { "" })
            }
            append("]}").let { }
        }
        append("]}")
    }

    fun bindings(
        set: BindingSet,
        findings: List<ConflictDetector.Finding>,
        reach: ReachabilityCheck.Result,
    ): String = buildString {
        append("""{"bindings":[""")
        set.bindings.forEachIndexed { i, b ->
            if (i > 0) append(',')
            append("""{"action":""").append(str(b.action.id))
            append(""","chord":""").append(str(b.chord.encode()))
            append(""","label":""").append(str(b.chord.label()))
            append(""","scope":""").append(if (b.scope == null) "null" else str(b.scope.process ?: b.scope.titlePattern ?: "scoped"))
            append(""","enabled":""").append(b.enabled)
            append('}')
        }
        append("""],"findings":[""")
        findings.forEachIndexed { i, f ->
            if (i > 0) append(',')
            append("""{"code":""").append(str(f.code))
            append(""","verdict":""").append(str(f.verdict.name))
            append(""","source":""").append(str(f.source.name))
            append(""","message":""").append(str(f.message))
            append('}')
        }
        append("""],"reachable":""").append(reach.ok)
        append(""","blocking":""").append(reach.blocking.size)
        append(""","advisory":""").append(reach.advisory.size)
        append('}')
    }

    fun probeJson(p: LinuxSessionProbe): String {
        val checks = p.checks()
        return buildString {
            append("""{"platform":"linux","session":""").append(str(p.detectSession().name))
            append(""","desktop":""").append(str(p.detectDesktopEnvironment().name))
            append(""","checks":[""")
            checks.forEachIndexed { i, c ->
                if (i > 0) append(',')
                val (verdict, reason) = when (val s = c.support) {
                    is io.github.only_air.screengloss.core.platform.Support.Yes -> "yes" to null
                    is io.github.only_air.screengloss.core.platform.Support.Degraded -> "degraded" to s.reason
                    is io.github.only_air.screengloss.core.platform.Support.No -> "no" to s.reason
                }
                append("""{"feature":""").append(str(c.feature))
                append(""","verdict":""").append(str(verdict))
                append(""","confidence":""").append(str(c.confidence.name))
                append(""","reason":""").append(if (reason == null) "null" else str(reason))
                append(""","how":""").append(str(c.how))
                append('}')
            }
            append("]}")
        }
    }
}

/**
 * Sample card renderers.
 *
 * Deliberately minimal. They exist so the panel has *something* to render on
 * `/word` and `/anki/sentence` before the dictionary stack moves, and so the
 * "upstream already produces HTML, so this is a port not an invention" claim in
 * §7.2.1 has a concrete shape in this repo. The real renderers are
 * `PtCardTemplates` (716 lines), `AnkiCardCss` (278) and `SentenceAnkiHtmlBuilder`
 * (645), which are extracted to `core/.../upstream/` and not yet wiring-complete.
 */
object DemoCards {
    fun word(id: String): String = """
        <article class="pt-card" data-source="demo">
          <header class="pt-card__head">
            <span class="pt-headword">${esc(id)}</span>
            <span class="pt-reading">よみ</span>
          </header>
          <p class="pt-note">
            This is a placeholder card. The real renderer is <code>PtCardTemplates.kt</code>
            (716 lines upstream), which is extracted but not yet wired to this endpoint —
            see <code>pc/GAP_REPORT.md</code>.
          </p>
        </article>
    """.trimIndent()

    fun sentence(id: String): String = """
        <article class="pt-sentence" data-source="demo">
          <p class="pt-sentence__source">${esc(id)}</p>
          <p class="pt-sentence__translation">(placeholder translation)</p>
        </article>
    """.trimIndent()

    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}

private fun Chord.encodeOrId(): String = encode()
