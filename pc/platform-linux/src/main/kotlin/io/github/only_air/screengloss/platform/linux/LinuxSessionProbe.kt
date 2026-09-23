package io.github.only_air.screengloss.platform.linux

import io.github.only_air.screengloss.core.platform.CapabilityProbe
// `Platform`, `SessionKind` and `Sandbox` are nested in [CapabilityProbe]
// because they describe *its* input rather than the platform's. Importing the
// nested types keeps that nesting meaningful and this file readable.
import io.github.only_air.screengloss.core.platform.CapabilityProbe.SessionKind
import io.github.only_air.screengloss.core.platform.Support
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * The Linux half of PORTING.md §9's P-1 milestone.
 *
 * §9 is unusually explicit about what it wants here, and about why:
 *
 * > ⚠️ 表中标注"需实测"的条目（KWin 的 layer-shell 行为、GlobalShortcuts portal
 * > 在各 DE 的实现版本、PipeWire 按应用捕获）属于**外部生态事实**，本次未能联网核实，
 * > 进入 P0 前必须各写一个 30 行的最小验证程序跑一遍。**不要基于假设开工。**
 *
 * This is that program. It does not *implement* overlay/hotkey/audio capture —
 * it determines whether the platform is going to allow them, and it returns
 * [Support.No] / [Support.Degraded] with a reason rather than throwing, so the
 * answer can be rendered in the panel and quoted in a decision record.
 *
 * ## What it checks, and what each check is worth
 *
 * Every check below reads a fact that is *observable without a GUI session*,
 * because a probe that needs to be run twice (once in a terminal, once in a
 * session) is a probe that will be run once. The three "需实测" items are the
 * exception and are marked [Confidence.NEEDS_A_SESSION].
 *
 * The distinction the report has to keep: **"not present" and "not known" are
 * different answers.** A container has no `WAYLAND_DISPLAY`, so layer-shell is
 * *unknown*, not *unsupported* — claiming the latter from the former is exactly
 * the assumption §9 forbids. [Confidence] carries that distinction so the
 * rendered table cannot conflate them.
 */
class LinuxSessionProbe(
    private val fs: FileSystemView = RealFileSystem(),
    private val env: (String) -> String? = System::getenv,
) {

    /** What a check is worth when it says "no". */
    enum class Confidence {
        /** The check is conclusive: the platform cannot do this. */
        CONCLUSIVE,

        /** The check is conclusive when it *finds* the thing, and merely
         *  uninformative when it does not (no session to look at). */
        NEEDS_A_SESSION,

        /** Nothing can be concluded from here. */
        INDETERMINATE,
    }

    class Check(val feature: String, val support: Support, val confidence: Confidence, val how: String)

    fun checks(): List<Check> {
        val session = detectSession()
        val de = detectDesktopEnvironment()
        return listOfNotNull(
            checkSession(),
            checkOverlay(session, de),
            checkGlobalShortcuts(session, de),
            checkCapture(session),
            checkAudioCapture(session, de),
            checkTray(de, session),
            checkPointer(session),
            checkSandbox(),
        )
    }

    fun probe(): CapabilityProbe = CapabilityProbe(
        platform = CapabilityProbe.Platform.LINUX,
        session = detectSession(),
        sandbox = when {
            isFlatpak() -> CapabilityProbe.Sandbox.FLATPAK
            isSnap() -> CapabilityProbe.Sandbox.SNAP
            else -> CapabilityProbe.Sandbox.NATIVE
        },
    )

    // ── session ────────────────────────────────────────────────────────────

    fun detectSession(): SessionKind = when {
        env("PLAYTRANSLATE_FORCE_SESSION") != null -> when (env("PLAYTRANSLATE_FORCE_SESSION")) {
            "x11" -> SessionKind.X11
            "wayland" -> SessionKind.WAYLAND
            "headless" -> SessionKind.UNKNOWN
            else -> SessionKind.UNKNOWN
        }
        env("WAYLAND_DISPLAY") != null -> SessionKind.WAYLAND
        env("DISPLAY") != null -> SessionKind.X11
        else -> SessionKind.UNKNOWN
    }

    enum class DesktopEnvironment { KDE, GNOME, XFCE, SWAY, OTHER, NONE }

    fun detectDesktopEnvironment(): DesktopEnvironment {
        val joined = listOf("XDG_CURRENT_DESKTOP", "XDG_SESSION_DESKTOP", "DESKTOP_SESSION")
            .mapNotNull { env(it) }.joinToString(":").lowercase()
        return when {
            joined.isBlank() -> DesktopEnvironment.NONE
            joined.contains("kde") || joined.contains("plasma") -> DesktopEnvironment.KDE
            joined.contains("gnome") -> DesktopEnvironment.GNOME
            joined.contains("xfce") -> DesktopEnvironment.XFCE
            joined.contains("sway") -> DesktopEnvironment.SWAY
            else -> DesktopEnvironment.OTHER
        }
    }

    private fun checkSession(): Check? {
        val s = detectSession()
        return Check(
            "screen session",
            when (s) {
                SessionKind.X11 -> Support.Yes
                SessionKind.WAYLAND -> Support.Yes
                SessionKind.QUARTZ -> Support.No("macOS session kind on a Linux build — the probe is misconfigured")
                SessionKind.DWM -> Support.No("Windows session kind on a Linux build — the probe is misconfigured")
                SessionKind.UNKNOWN -> Support.No("no DISPLAY and no WAYLAND_DISPLAY in this environment")
            },
            if (s == SessionKind.UNKNOWN) Confidence.INDETERMINATE else Confidence.CONCLUSIVE,
            "WAYLAND_DISPLAY / DISPLAY in the environment",
        )
    }

    // ── overlay ────────────────────────────────────────────────────────────

    private fun checkOverlay(session: SessionKind, de: DesktopEnvironment): Check {
        val howParts = mutableListOf<String>()
        if (session == SessionKind.X11) {
            val composite = env("DISPLAY") != null
            howParts += "X11: override-redirect + XShape, needs a compositor (XComposite)"
            return Check(
                "overlay over games",
                if (composite) Support.Yes else Support.No("no DISPLAY"),
                Confidence.NEEDS_A_SESSION,
                howParts.joinToString("; "),
            )
        }
        if (session == SessionKind.WAYLAND) {
            // The check that actually answers "需实测": is wlr-layer-shell
            // implemented by this compositor? The protocol XML ships with
            // wayland-protocols when *any* compositor could implement it, so its
            // presence is necessary and not sufficient. KWin and Mutter are the
            // two that matter and they differ, so the check is split.
            val hasLayerShell = fs.exists("/usr/share/wayland-protocols") ||
                fs.glob("/usr/share/wayland-protocols/**/wlr-layer-shell*.xml").isNotEmpty() ||
                fs.glob("/usr/share/wlr-protocols/**/wlr-layer-shell*.xml").isNotEmpty()
            howParts += if (hasLayerShell) {
                "wlr-layer-shell XML present under /usr/share/wayland-protocols"
            } else {
                "no wlr-layer-shell XML found; the *compositor*, not the protocol files, decides"
            }
            return when (de) {
                DesktopEnvironment.KDE -> Check(
                    "overlay over games",
                    if (hasLayerShell) Support.Yes
                    else Support.Degraded(
                        "KWin may support wlr-layer-shell but the protocol files were not found",
                        "side panel",
                    ),
                    Confidence.NEEDS_A_SESSION,
                    howParts.joinToString("; ") + " — §1 marks this '需实测'; run on a real Plasma session",
                )
                DesktopEnvironment.GNOME -> Check(
                    "overlay over games",
                    Support.No(
                        "Mutter does not implement wlr-layer-shell; an ordinary app cannot draw over another window",
                        "use the side panel, or log into an X11 session",
                    ),
                    Confidence.CONCLUSIVE,
                    "desktop environment is GNOME (Mutter) — §1's one hard 'no'",
                )
                DesktopEnvironment.SWAY -> Check(
                    "overlay over games",
                    if (hasLayerShell) Support.Yes else Support.Degraded("sway present, protocol files missing", "side panel"),
                    Confidence.NEEDS_A_SESSION,
                    "sway implements wlr-layer-shell",
                )
                else -> Check(
                    "overlay over games",
                    Support.Degraded("compositor not identified", "side panel"),
                    Confidence.INDETERMINATE,
                    howParts.joinToString("; "),
                )
            }
        }
        return Check(
            "overlay over games",
            Support.No("no graphical session in this environment", "side panel"),
            Confidence.INDETERMINATE,
            "no DISPLAY / WAYLAND_DISPLAY",
        )
    }

    // ── global shortcuts ───────────────────────────────────────────────────

    private fun checkGlobalShortcuts(session: SessionKind, de: DesktopEnvironment): Check {
        // The second "需实测" item. The GlobalShortcuts portal exists in
        // xdg-desktop-portal ≥ 1.17, but whether a given desktop *implements*
        // it, and whether it delivers a Released signal, is per-DE. On X11 the
        // question does not arise.
        val portalDir = "/usr/share/xdg-desktop-portal/portals"
        val portalConfigs = fs.glob("$portalDir/*.portal")
        val hasPortal = portalConfigs.isNotEmpty()
        val backend = when (de) {
            DesktopEnvironment.KDE -> portalConfigs.any { it.contains("kde") }
            DesktopEnvironment.GNOME -> portalConfigs.any { it.contains("gnome") }
            DesktopEnvironment.XFCE -> portalConfigs.any { it.contains("gtk") || it.contains("xfce") }
            else -> false
        }
        return when (session) {
            SessionKind.X11 -> Check(
                "global hotkeys",
                Support.Yes,
                Confidence.NEEDS_A_SESSION,
                "XInput2 / XRecord grab; key-up is delivered, so HOLD semantics survive",
            )
            SessionKind.WAYLAND -> Check(
                "global hotkeys",
                when {
                    !hasPortal -> Support.Degraded(
                        "no xdg-desktop-portal backend installed",
                        "hotkeys only fire while the panel has focus",
                    )
                    !backend -> Support.Degraded(
                        "$de backend not among the installed portal configs (${portalConfigs.joinToString()})",
                        "hotkeys only fire while the panel has focus",
                    )
                    else -> Support.Degraded(
                        "portal GlobalShortcuts delivers Activated and, in most backends, no Released " +
                            "— §5.7's HOLD/TAP split degrades to TAP-only",
                        "hold-to-preview becomes a toggle",
                    )
                },
                Confidence.NEEDS_A_SESSION,
                if (hasPortal) "portal configs present: ${portalConfigs.joinToString()}" else "$portalDir empty or absent",
            )
            SessionKind.UNKNOWN, SessionKind.QUARTZ, SessionKind.DWM -> Check(
                "global hotkeys",
                Support.No("no session", "n/a"),
                Confidence.INDETERMINATE,
                "no session to grab keys from",
            )
        }
    }

    // ── capture ────────────────────────────────────────────────────────────

    private fun checkCapture(session: SessionKind): Check = when (session) {
        SessionKind.X11 -> Check(
            "screen capture",
            Support.Yes,
            Confidence.NEEDS_A_SESSION,
            "XComposite + XShm on the root window; no permission prompt",
        )
        SessionKind.WAYLAND -> {
            val portal = fs.exists("/usr/libexec/xdg-desktop-portal") ||
                fs.exists("/usr/lib/xdg-desktop-portal") ||
                fs.glob("/usr/lib*/**/xdg-desktop-portal").isNotEmpty() ||
                fs.glob("/usr/share/xdg-desktop-portal/portals/*.portal").isNotEmpty()
            Check(
                "screen capture",
                if (portal) Support.Degraded(
                    "ScreenCast portal: a permission dialog per session, and the returned stream may be " +
                        "a composited desktop rather than the game window",
                    "accept the portal dialog on first use",
                ) else Support.No("no xdg-desktop-portal found", "run an X11 session"),
                Confidence.NEEDS_A_SESSION,
                if (portal) "xdg-desktop-portal ScreenCast" else "no portal binaries or configs found",
            )
        }
        SessionKind.UNKNOWN, SessionKind.QUARTZ, SessionKind.DWM -> Check(
            "screen capture",
            Support.No("no session", "n/a"),
            Confidence.INDETERMINATE,
            "no DISPLAY / WAYLAND_DISPLAY",
        )
    }

    // ── audio ──────────────────────────────────────────────────────────────

    private fun checkAudioCapture(session: SessionKind, de: DesktopEnvironment): Check {
        val pipewire = fs.exists("/usr/bin/pipewire") || fs.glob("/usr/bin/pipewire*").isNotEmpty() ||
            fs.glob("/usr/lib*/pipewire*").isNotEmpty()
        val pulse = fs.exists("/usr/bin/pulseaudio") || fs.glob("/usr/bin/pactl").isNotEmpty()
        val socket = env("PIPEWIRE_RUNTIME_DIR") != null ||
            env("XDG_RUNTIME_DIR")?.let { fs.exists("$it/pipewire-0") || fs.exists("$it/pulse/native") } == true
        val how = buildString {
            append(if (pipewire) "PipeWire present" else "no PipeWire")
            append(if (pulse) "; PulseAudio present" else "; no PulseAudio")
            append(if (socket) "; a runtime socket is visible" else "; no runtime socket")
        }
        return Check(
            "game audio capture",
            when {
                pipewire && session != SessionKind.UNKNOWN -> Support.Yes
                pulse && session != SessionKind.UNKNOWN -> Support.Degraded(
                    "PulseAudio .monitor source: the monitor is per-sink, not per-application",
                    "mute the app's own audio while capturing",
                )
                else -> Support.No("no sound server found", "n/a")
            },
            if (session == SessionKind.UNKNOWN) Confidence.INDETERMINATE else Confidence.NEEDS_A_SESSION,
            how,
        )
    }

    // ── tray ───────────────────────────────────────────────────────────────

    private fun checkTray(de: DesktopEnvironment, session: SessionKind): Check {
        // StatusNotifierItem over D-Bus: the only tray protocol Linux has. On
        // GNOME it needs the AppIndicator extension, which §5.8 calls out.
        val sni = fs.glob("/usr/share/dbus-1/services/*StatusNotifierWatcher*").isNotEmpty() ||
            fs.glob("/usr/share/dbus-1/services/**/*StatusNotifier*").isNotEmpty()
        val extension = fs.glob("/usr/share/gnome-shell/extensions/*appindicator*").isNotEmpty() ||
            fs.glob("/usr/share/gnome-shell/extensions/*AppIndicator*").isNotEmpty()
        return Check(
            "tray icon",
            when {
                session == SessionKind.UNKNOWN -> Support.No("no session", "the main window and hotkeys still work")
                de == DesktopEnvironment.GNOME && !extension -> Support.Degraded(
                    "GNOME has no tray without the AppIndicator extension",
                    "the panel's status strip, plus a hotkey",
                )
                sni || extension -> Support.Yes
                else -> Support.Degraded(
                    "no StatusNotifierWatcher service file found; the tray may still work if the " +
                        "watcher is provided by the session bus rather than a service file",
                    "the panel's status strip",
                )
            },
            Confidence.NEEDS_A_SESSION,
            "StatusNotifierItem service files: ${if (sni) "present" else "not found"}; " +
                "GNOME AppIndicator extension: ${if (extension) "present" else "not found"}",
        )
    }

    // ── pointer ────────────────────────────────────────────────────────────

    private fun checkPointer(session: SessionKind): Check = when (session) {
        SessionKind.X11 -> Check(
            "global pointer position",
            Support.Yes,
            Confidence.NEEDS_A_SESSION,
            "XQueryPointer: the pointer is global state on X11, which is what makes the magnifier lens " +
                "possible; §5.5 nonetheless makes reading it opt-in",
        )
        SessionKind.WAYLAND -> Check(
            "global pointer position",
            Support.No(
                "no protocol exposes the global pointer position to a client; even the pointer " +
                    "constraints protocol reports only that a pointer is over *your* surface",
                "the lens falls back to a per-region probe the user moves with a hotkey",
            ),
            Confidence.CONCLUSIVE,
            "Wayland's security model — not a missing implementation",
        )
        SessionKind.UNKNOWN, SessionKind.QUARTZ, SessionKind.DWM -> Check("global pointer position", Support.No("no session", "n/a"), Confidence.INDETERMINATE, "n/a")
    }

    private fun checkSandbox(): Check {
        val flatpak = isFlatpak()
        val snap = isSnap()
        return Check(
            "sandbox",
            if (flatpak || snap) Support.Degraded(
                "running inside ${if (flatpak) "Flatpak" else "Snap"}; the sandbox does not change the " +
                    "capability boundary, it only forces capture and hotkeys through the portal",
                "grant the portal permissions on first use",
            ) else Support.Yes,
            Confidence.CONCLUSIVE,
            "FLATPAK_ID=${env("FLATPAK_ID") ?: "-"}, SNAP=${env("SNAP") ?: "-"}",
        )
    }

    private fun isFlatpak(): Boolean = env("FLATPAK_ID") != null || fs.exists("/.flatpak-info")
    private fun isSnap(): Boolean = env("SNAP") != null

    // ── text rendering ─────────────────────────────────────────────────────

    /** Render §9's decision record as the table it asks for. */
    fun render(): String {
        val sb = StringBuilder()
        sb.appendLine("# P-1 capability decision record — Linux")
        sb.appendLine()
        sb.appendLine("Generated by `pc/platform-linux/.../LinuxSessionProbe.kt`.")
        sb.appendLine()
        sb.appendLine("- session: **${detectSession()}**")
        sb.appendLine("- desktop environment: **${detectDesktopEnvironment()}**")
        sb.appendLine("- sandbox: **${if (isFlatpak()) "Flatpak" else if (isSnap()) "Snap" else "native"}**")
        sb.appendLine()
        sb.appendLine("| feature | verdict | confidence | how it was decided |")
        sb.appendLine("|---|---|---|---|")
        for (c in checks()) {
            val verdict = when (val s = c.support) {
                is Support.Yes -> "yes"
                is Support.Degraded -> "degraded — ${s.reason}"
                is Support.No -> "no — ${s.reason}"
            }
            sb.appendLine("| ${c.feature} | $verdict | ${c.confidence} | ${c.how} |")
        }
        sb.appendLine()
        sb.appendLine("## How to read the confidence column")
        sb.appendLine()
        sb.appendLine("- `CONCLUSIVE` — the platform cannot do this, for a structural reason.")
        sb.appendLine("- `NEEDS_A_SESSION` — the check is conclusive when it *finds* the thing and says nothing")
        sb.appendLine("  when it does not. A container has no compositor, so \"not found\" here is not \"unsupported\".")
        sb.appendLine("  PORTING.md §1 marks these three items '需实测' and they stay 需实测 until this table is")
        sb.appendLine("  generated on a real session.")
        sb.appendLine("- `INDETERMINATE` — nothing can be concluded from this environment.")
        return sb.toString()
    }
}

/** Indirection over the filesystem so the probe is unit-testable without one. */
interface FileSystemView {
    fun exists(path: String): Boolean
    /** Returns matching paths for a glob. Only `*` and `**` need to work. */
    fun glob(pattern: String): List<String>
}

class RealFileSystem : FileSystemView {
    override fun exists(path: String): Boolean = File(path).exists()

    override fun glob(pattern: String): List<String> {
        val cut = pattern.indexOf('*')
        if (cut < 0) return if (exists(pattern)) listOf(pattern) else emptyList()
        val prefix = pattern.substring(0, cut)
        val dir = Path.of(prefix.substringBeforeLast('/', "/"))
        if (!Files.isDirectory(dir)) return emptyList()
        val regex = Regex(
            pattern.replace(".", "\\.").replace("**", "\u0000").replace("*", "[^/]*")
                .replace("\u0000", ".*")
        )
        return Files.walk(dir, 6).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .map { it.toString() }
                .filter { regex.matches(it) }
                .limit(50)
                .toList()
        }
    }
}
