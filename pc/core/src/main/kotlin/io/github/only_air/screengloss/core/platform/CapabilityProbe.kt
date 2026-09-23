package io.github.only_air.screengloss.core.platform

import io.github.only_air.screengloss.core.geometry.PtImage
import io.github.only_air.screengloss.core.geometry.PtIntRect
import io.github.only_air.screengloss.core.geometry.PtRect

/**
 * The P-1 platform probe — PORTING.md §9's first milestone, made runnable.
 *
 * §9 says P-1 exists to "排除假设风险" and names **five** minimal verification
 * programs, one per platform, with an exit criterion of "每平台明确「能做 / 不能做 /
 * 需降级」，写入决策记录". It then adds the rule that makes the whole milestone
 * worth having:
 *
 * > ⚠️ 表中标注"需实测"的条目（KWin 的 layer-shell 行为、GlobalShortcuts portal
 * > 在各 DE 的实现版本、PipeWire 按应用捕获）属于**外部生态事实**，本次未能联网核实，
 * > 进入 P0 前必须各写一个 30 行的最小验证程序跑一遍。**不要基于假设开工。**
 *
 * This file is that decision record as a *type*. It does not perform the
 * platform checks — it cannot; that is what the platform backends are for — it
 * collects their [Support] answers, adds the two checks that are not a backend
 * property (does the overlay survive its own capture; does the tray actually
 * appear), and renders the table §9 asks for.
 *
 * The reason this is worth writing before the platform code: every one of the
 * five has a *documented* answer in PORTING §1, and several of those answers
 * are marked "需实测". Encoding them as data means a probe run on a real machine
 * either confirms the doc or contradicts it, and the contradiction is visible
 * instead of being discovered at P2.
 */
class CapabilityProbe(
    private val platform: Platform,
    private val session: SessionKind,
    private val capture: CaptureBackend? = null,
    private val overlay: OverlayBackend? = null,
    private val hotkeys: HotkeyBackend? = null,
    private val tray: TrayBackend? = null,
    private val audio: AudioLoopbackBackend? = null,
    private val pointer: PointerObserver? = null,
    private val panel: PanelHost? = null,
    private val sandbox: Sandbox = Sandbox.NATIVE,
) {

    enum class Platform { WINDOWS, MACOS, LINUX }

    /** X11 vs Wayland is a bigger divide than Windows vs macOS (PORTING §1). */
    enum class SessionKind { X11, WAYLAND, QUARTZ, DWM, UNKNOWN }

    /** Whether we are inside Flatpak/Snap, which pushes capture and hotkeys onto portals. */
    enum class Sandbox { NATIVE, FLATPAK, SNAP }

    /** A desktop environment, when the platform is Linux. */
    enum class DesktopEnvironment { KDE_PLASMA, GNOME, XFCE, OTHER }

    var desktopEnvironment: DesktopEnvironment = DesktopEnvironment.OTHER

    /**
     * The five checks, in the order §9 lists them.
     *
     * A null backend means "not implemented on this platform yet" and reports
     * [Support.No] with the design's own stated reason, so a probe run on an
     * unfinished port still produces a readable table rather than blanks.
     */
    fun probe(): DecisionRecord {
        val capabilities = mutableListOf<Capability>()

        // ① Overlay: layered window + click-through + always-on-top.
        capabilities += Capability(
            id = "overlay",
            description = "Draw over another application's window, click-through, no focus steal",
            support = overlay?.support ?: Support.No(
                reason = "No overlay backend implemented for this platform yet",
                workaround = if (session == SessionKind.WAYLAND && desktopEnvironment == DesktopEnvironment.GNOME)
                    "GNOME Wayland has no wlr-layer-shell; the port falls back to the side-panel mode"
                else null,
            ),
        )

        // ①b The overlay-must-not-appear-in-its-own-capture check (PORTING §6.2).
        //     Not a backend property: it is the interaction between two of them.
        capabilities += Capability(
            id = "overlay.clean_capture",
            description = "The overlay is absent from the app's own screen capture (no OCR feedback loop)",
            support = cleanCaptureSupport(),
        )

        // ② Capture, including per-window.
        capabilities += Capability(
            id = "capture",
            description = "Capture a display region",
            support = capture?.support ?: Support.No("No capture backend implemented for this platform yet"),
        )
        capabilities += Capability(
            id = "capture.window",
            description = "Capture a single window rather than a display (makes clean capture structural)",
            support = when {
                capture == null -> Support.No("No capture backend implemented for this platform yet")
                capture.capturesWindow -> Support.Yes
                else -> Support.Degraded(
                    reason = "This backend can only capture a whole display",
                    fallback = "The overlay is excluded from capture by the platform's own mechanism instead",
                )
            },
        )

        // ③ Global hotkeys, and the key-up question that decides HOLD vs toggle.
        capabilities += Capability(
            id = "hotkey",
            description = "Register a global hotkey",
            support = hotkeys?.support ?: Support.No("No hotkey backend implemented for this platform yet"),
        )
        capabilities += Capability(
            id = "hotkey.keyup",
            description = "Receive key-up, so HOLD actions can be momentary",
            support = when {
                hotkeys == null -> Support.No("No hotkey backend implemented for this platform yet")
                hotkeys.deliversKeyUp -> Support.Yes
                else -> Support.Degraded(
                    reason = "This backend delivers activation only, with no release event",
                    fallback = "HOLD actions become press-to-show / press-to-hide toggles",
                )
            },
        )
        capabilities += Capability(
            id = "hotkey.mouse_buttons",
            description = "Bind mouse side buttons",
            support = when {
                hotkeys == null -> Support.No("No hotkey backend implemented for this platform yet")
                hotkeys.deliversMouseButtons -> Support.Yes
                else -> Support.Degraded(
                    reason = "The platform's hotkey API has no mouse-button concept",
                    fallback = "Falls back to evdev, which needs the user in the input group",
                )
            },
        )

        // ④ System audio loopback.
        capabilities += Capability(
            id = "audio.loopback",
            description = "Capture the game's own audio for Anki cards",
            support = audio?.support ?: Support.No("No audio loopback backend implemented for this platform yet"),
        )

        // ⑤ Tray icon — the check §9 singles out because GNOME is the failure case.
        capabilities += Capability(
            id = "tray",
            description = "System tray icon appears",
            support = traySupport(),
        )

        // The rest of the seams, which §7.4 lists but §9 does not gate on.
        capabilities += Capability(
            id = "pointer.observe",
            description = "Read the pointer without taking it (input state 0)",
            support = pointer?.support ?: Support.No("No pointer observer implemented for this platform yet"),
        )
        capabilities += Capability(
            id = "panel",
            description = "Host the web panel",
            support = panel?.support ?: Support.No("No panel host wired up yet"),
        )
        capabilities += Capability(
            id = "sandbox.portals",
            description = "Capture and hotkeys work through portals inside a sandbox",
            support = when (sandbox) {
                Sandbox.NATIVE -> Support.Yes
                else -> Support.Degraded(
                    reason = "${sandbox.name} forces capture and hotkeys through xdg-desktop-portal",
                    fallback = "Same capability boundary as outside the sandbox, but the portal must be present",
                )
            },
        )

        return DecisionRecord(platform, session, desktopEnvironment, sandbox, capabilities)
    }

    private fun cleanCaptureSupport(): Support {
        if (capture == null) return Support.No("No capture backend implemented for this platform yet")
        return when {
            // A window capture is clean by construction: the overlay is a
            // different window, so it cannot be in the frame.
            capture.capturesWindow -> Support.Yes
            // Whole-display capture needs a platform exclusion mechanism.
            platform == Platform.WINDOWS -> Support.Yes // SetWindowDisplayAffinity(WDA_EXCLUDEFROMCAPTURE)
            platform == Platform.MACOS -> Support.Degraded(
                reason = "Whole-display capture needs an SCContentFilter",
                fallback = "Prefer desktopIndependentWindow capture; otherwise blank the overlay for one frame",
            )
            else -> Support.Degraded(
                reason = "XComposite can redirect a window's pixmap and skip the overlay",
                fallback = "Capture the window rather than the root, or blank the overlay for one frame",
            )
        }
    }

    private fun traySupport(): Support {
        val backend = tray?.support ?: return Support.No("No tray backend implemented for this platform yet")
        if (!backend.isUsable) return backend
        // The known trap: GNOME removed the legacy tray and needs the
        // AppIndicator extension for StatusNotifierItem (PORTING §5.8).
        return if (platform == Platform.LINUX && desktopEnvironment == DesktopEnvironment.GNOME) {
            Support.Degraded(
                reason = "GNOME ships no tray; StatusNotifierItem needs the AppIndicator extension",
                fallback = "The tray is never the only way in: a hotkey-summoned menu and the main window both work",
            )
        } else {
            backend
        }
    }
}

/**
 * The P-1 exit artifact: one row per capability, per platform, with the
 * verdict §9 asks for (能做 / 不能做 / 需降级) and a machine-readable form so a
 * future CI run on a real machine can diff today's assumptions against the
 * measured answer.
 */
data class DecisionRecord(
    val platform: CapabilityProbe.Platform,
    val session: CapabilityProbe.SessionKind,
    val desktopEnvironment: CapabilityProbe.DesktopEnvironment,
    val sandbox: CapabilityProbe.Sandbox,
    val capabilities: List<Capability>,
) {

    enum class Verdict { CAN, CANNOT, DEGRADED }

    data class Row(val id: String, val description: String, val verdict: Verdict, val note: String)

    val rows: List<Row> get() = capabilities.map { capability ->
        when (val support = capability.support) {
            is Support.Yes -> Row(capability.id, capability.description, Verdict.CAN, "")
            is Support.Degraded -> Row(
                capability.id, capability.description, Verdict.DEGRADED,
                "${support.reason} -> ${support.fallback}",
            )
            is Support.No -> Row(
                capability.id, capability.description, Verdict.CANNOT,
                listOfNotNull(support.reason, support.workaround?.let { "workaround: $it" }).joinToString("; "),
            )
        }
    }

    /** True when nothing on this platform is a hard no. */
    val isFullyCapable: Boolean get() = rows.none { it.verdict == Verdict.CANNOT }

    /** The §9 exit criterion, verbatim: every capability is can / cannot / degraded. */
    fun renderMarkdown(): String = buildString {
        appendLine("# P-1 platform decision record")
        appendLine()
        appendLine("- platform: `${platform.name}`")
        appendLine("- session: `${session.name}`")
        appendLine("- desktop environment: `${desktopEnvironment.name}`")
        appendLine("- packaging: `${sandbox.name}`")
        appendLine("- produced by: `screengloss probe`")
        appendLine()
        appendLine("| capability | verdict | note |")
        appendLine("|---|---|---|")
        for (row in rows) {
            val verdict = when (row.verdict) {
                Verdict.CAN -> "CAN"
                Verdict.CANNOT -> "**CANNOT**"
                Verdict.DEGRADED -> "DEGRADED"
            }
            appendLine("| `${row.id}` — ${row.description} | $verdict | ${row.note.replace("|", "\\|")} |")
        }
        appendLine()
        appendLine("Verdicts are *assertions until measured on the target machine*. PORTING.md §1")
        appendLine("marks several of these \"需实测\"; a probe run on real hardware either confirms or")
        appendLine("contradicts the design, and the contradiction is the point of P-1.")
    }

    companion object {
        /**
         * A probe with no backends at all — what a headless CI run can honestly
         * report. Every row is `CANNOT` with the reason "not implemented yet",
         * which is exactly the state of the port: the seams exist, the platform
         * implementations do not.
         */
        fun headless(): DecisionRecord = CapabilityProbe(
            platform = CapabilityProbe.Platform.LINUX,
            session = CapabilityProbe.SessionKind.UNKNOWN,
        ).probe()
    }
}

/**
 * A capture backend that always works, used by the headless end-to-end check
 * and by the shell's `demo` command.
 *
 * It is not a stub in the pejorative sense: it produces a real [PtImage] with a
 * real text-box layout, so everything downstream (region resolution, colour
 * normalisation, box ordering, the overlay content model, the Anki screenshot)
 * is exercised end to end. What it cannot do is prove anything about the
 * platform — and it says so, by reporting [Support.No].
 */
class FixtureCaptureBackend(
    private val width: Int = 1280,
    private val height: Int = 720,
) : CaptureBackend {

    override val id: String = "fixture"
    override val support: Support = Support.No(
        reason = "Fixture backend: produces synthetic frames, captures no real screen",
    )
    override val capturesWindow: Boolean = false

    /** Boxes the fixture draws, in *fixture-local* pixels. */
    val fixtureBoxes: List<PtIntRect> = listOf(
        PtIntRect(120, 480, 900, 520),
        PtIntRect(120, 530, 760, 570),
        PtIntRect(120, 580, 640, 620),
    )

    override fun capture(display: io.github.only_air.screengloss.core.geometry.DisplayInfo, regionGlobalPx: PtRect, windowHandle: Long?): PtImage {
        val w = regionGlobalPx.width.toInt().coerceAtLeast(1).coerceAtMost(width)
        val h = regionGlobalPx.height.toInt().coerceAtLeast(1).coerceAtMost(height)
        return PtImage.fixture(w, h, fixtureBoxes.map { box ->
            PtIntRect(box.left, box.top, box.right.coerceAtMost(w), box.bottom.coerceAtMost(h))
        })
    }
}
