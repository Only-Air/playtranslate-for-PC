package io.github.only_air.screengloss.core.platform

import io.github.only_air.screengloss.core.geometry.DisplayInfo
import io.github.only_air.screengloss.core.geometry.PtImage
import io.github.only_air.screengloss.core.geometry.PtPointF
import io.github.only_air.screengloss.core.geometry.PtRect
import io.github.only_air.screengloss.core.geometry.ScreenGeometry

/**
 * The seams — the interfaces PORTING.md §7.4 lists as "需要新建的抽象".
 *
 * Upstream already has the right *idea*: four interfaces
 * (`TranslationBackend`, `OcrEngine`, `CaptureBackend`, `LiveMode`) are the
 * boundaries the port preserves. The rest of this file is what the desktop
 * needs and the phone never did.
 *
 * One rule governs everything here: **an interface exists only where a
 * platform genuinely differs, and its "unsupported" answer is a first-class
 * value, not an exception.** Wayland has no global pointer position, GNOME has
 * no tray, Windows exclusive fullscreen cannot be drawn over. If those were
 * exceptions, the shell would be full of try/catch and the UI would have no way
 * to say *why* a feature is missing. They are [Support] instead, and the
 * capability report (§9's P-1 deliverable) is just a collected list of them.
 */

/** Whether a platform feature is available, and if not, what to do about it. */
sealed interface Support {
    data object Yes : Support

    /**
     * Available, but with different semantics. The canonical case is HOLD on
     * Wayland: the portal delivers an `Activated` signal and no `Released`, so
     * hold-to-preview degrades to a toggle. The UI must say so rather than
     * silently behave differently.
     */
    data class Degraded(val reason: String, val fallback: String) : Support

    /** Not available. [workaround] is what the user can do instead, if anything. */
    data class No(val reason: String, val workaround: String? = null) : Support

    val isUsable: Boolean get() = this is Yes || this is Degraded
}

/** A named platform capability, for the report and the settings page. */
data class Capability(
    val id: String,
    val description: String,
    val support: Support,
)

/**
 * Reads the pointer without taking it — input state 0's foundation
 * (PORTING §5.2), and the one capability with a hard platform wall.
 *
 * `GetCursorPos` on Windows, `NSEvent.mouseLocation` on macOS,
 * `XQueryPointer` on X11. **Wayland has no equivalent**: `wl_pointer` requires
 * the surface to have focus, so a background app cannot ask where the cursor
 * is. That is not a bug to work around — it is why input state 0 does not exist
 * on GNOME/KDE Wayland, and why the port must degrade to state 1 (hotkey
 * latch) there rather than pretend.
 */
interface PointerObserver {
    val support: Support

    /** Current pointer position in global device pixels, or null when unknown. */
    fun sample(): PtPointF?

    /**
     * Whether this observer can deliver *movement* without polling. All three
     * desktop implementations can (they are all cheap polls at 30–60 Hz);
     * kept as a question because a future portal-based implementation might not.
     */
    val isPush: Boolean get() = false
}

/** The honest answer when the platform cannot do it. */
object NullPointerObserver : PointerObserver {
    override val support: Support = Support.No(
        reason = "This session has no global pointer query (Wayland)",
        workaround = "Use the lookup hotkey (input state 1), or log in to an X11 session",
    )

    override fun sample(): PtPointF? = null
}

/**
 * Screen capture. Four implementations (PORTING §1): Windows.Graphics.Capture /
 * DXGI, ScreenCaptureKit, XComposite, and the PipeWire portal.
 *
 * The signature is deliberately *rect-in, image-out*, with no notion of "the
 * whole screen": §6.2 recommends capturing a **window** rather than a display
 * wherever the platform allows, because that makes "the overlay must not appear
 * in its own capture" a structural property instead of a timing trick. A
 * backend that can only do whole-display capture reports [capturesWindow] =
 * false, and the pipeline then relies on the platform's exclusion mechanism
 * (`SetWindowDisplayAffinity(WDA_EXCLUDEFROMCAPTURE)` on Windows).
 */
interface CaptureBackend {
    val id: String
    val support: Support

    /** True when this backend can capture a single window instead of a display. */
    val capturesWindow: Boolean

    /**
     * Capture [regionGlobalPx] on [display]. [windowHandle] is an opaque
     * platform window id, or null for a display capture.
     *
     * Must return an 8-bit sRGB image — HDR sources are normalised by the
     * backend via `ColorNormalizer`, because only the backend knows the format
     * the OS handed it (PORTING §6.3).
     */
    fun capture(display: DisplayInfo, regionGlobalPx: PtRect, windowHandle: Long? = null): PtImage
}

/**
 * Draws over someone else's window. Four implementations plus the degrade path.
 *
 * `PanelBackend` is not a consolation prize: PORTING §1 promotes upstream's
 * `OverlayFlavor.IN_APP_ONLY` to a first-class desktop mode, and on GNOME
 * Wayland it is the *only* mode, because Mutter does not implement
 * `wlr-layer-shell` and no ordinary application can draw over a game there.
 */
interface OverlayBackend {
    val id: String
    val support: Support

    /** True when the overlay draws over another application's window. */
    val isOverGame: Boolean

    fun show()
    fun hide()

    /** Push a new frame of text boxes. Coordinates are global device pixels. */
    fun setContent(boxes: List<OverlayBox>)

    /** Set the input-passthrough state. See [OverlayBackend] docs and §6.1. */
    fun setClickThrough(enabled: Boolean)

    /** The visual cue for a pointer-grabbing state (§5.5). */
    fun setPointerOwnerCue(active: Boolean)
}

/** One piece of painted text. */
data class OverlayBox(
    val rect: PtRect,
    val text: String,
    val kind: Kind = Kind.TRANSLATION,
    /** Reading hints (furigana / pinyin), rendered above [text]. */
    val hint: String? = null,
) {
    enum class Kind { TRANSLATION, SOURCE_HIGHLIGHT, DEBUG }
}

/**
 * The panel host: where the web UI is rendered.
 *
 * JCEF/KCEF per §7.2.2, with platform WebViews as the P1 alternative. The port
 * does not pick here — the interface is what lets the decision stay open, and
 * §11 lists it as an undecided question for the project owner.
 */
interface PanelHost {
    val support: Support

    /** Open (or focus) a panel at [route], e.g. `/settings/hotkeys`. */
    fun open(route: String)

    fun close()

    /** True when the panel is showing; drives the `reachable` gate. */
    val isVisible: Boolean

    /** Push an event to the panel over the bridge (§7.2.3). */
    fun post(event: String, payloadJson: String)
}

/**
 * The tray. Three implementations (PORTING §5.8), and one hard constraint:
 * **GNOME ships no tray** unless the AppIndicator extension is installed, so
 * the tray can never be the only way into a running instance. That is enforced
 * in `ReachabilityCheck`, not here.
 */
interface TrayBackend {
    val support: Support

    fun install(onActivate: (String) -> Unit)

    fun uninstall()

    /** The status light: amber when degraded to an offline floor (§5.8). */
    fun setStatus(state: TrayStatus)

    fun setMenu(items: List<TrayMenuItem>)
}

enum class TrayStatus { IDLE, LIVE, DEGRADED, LATCHED }

data class TrayMenuItem(
    val id: String,
    val label: String,
    val kind: Kind = Kind.ACTION,
    val enabled: Boolean = true,
    val checked: Boolean? = null,
) {
    enum class Kind { ACTION, TOGGLE, SEPARATOR, STATUS }
}

/**
 * Global hotkeys. Four implementations (PORTING §5.7), and the capability that
 * differs most across them:
 *
 * | platform | API | key-up? |
 * |---|---|---|
 * | Windows | Raw Input (`RIDEV_INPUTSINK`) | yes, no hook, no admin |
 * | macOS | CGEventTap | yes, needs Accessibility permission |
 * | X11 | XInput2 / XRecord | yes |
 * | Wayland | `GlobalShortcuts` portal | **no** — `Activated` only |
 *
 * The `keyUp` capability is a value, not a comment, because HOLD actions have
 * to degrade to toggles without it.
 */
interface HotkeyBackend {
    val support: Support

    /** True when this backend delivers key-up. False on Wayland. */
    val deliversKeyUp: Boolean

    /** True when mouse side buttons can be bound. False on the Wayland portal. */
    val deliversMouseButtons: Boolean

    /** Register the chords the router cares about. Unregistered chords are passed through. */
    fun register(chords: Set<io.github.only_air.screengloss.core.action.Chord>)

    fun unregisterAll()
}

/**
 * Text-to-speech. Three platform backends plus bundled Piper (PORTING §4.1).
 *
 * `synthesizeToFile` is not a convenience: the Anki card needs a **file**
 * (`audio/sources/TtsAudioSource.kt` on Android), and of the four backends only
 * Piper produces one natively. Windows SAPI needs `SPBindToFile`, macOS needs a
 * buffer callback, and speech-dispatcher will not give you a file at all — which
 * is the design's stated reason for shipping Piper.
 */
interface TtsBackend {
    val support: Support

    fun voicesFor(language: String): List<TtsVoice>

    fun speak(text: String, voice: TtsVoice?): Boolean

    /** Render to a WAV file. @return null when the backend cannot produce one. */
    fun synthesizeToFile(text: String, voice: TtsVoice?, outFile: java.io.File): java.io.File?

    fun stop()
}

data class TtsVoice(
    val id: String,
    val name: String,
    val language: String,
    val quality: Quality = Quality.STANDARD,
) {
    enum class Quality { STANDARD, ENHANCED, PREMIUM, NEURAL }
}

/**
 * Secret storage. AndroidKeyStore + AES-GCM upstream; DPAPI / Keychain /
 * libsecret here. One interface because the *policy* is identical on all three
 * (never write a key to prefs in plaintext, never log one), and only the
 * backing store differs.
 */
interface SecretStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
    fun isAvailable(): Boolean
}

/**
 * AnkiConnect. Replaces upstream's AnkiDroid ContentProvider entirely
 * (PORTING §2.2) — the card templates, field mapper and send pipeline survive,
 * only the transport changes.
 */
interface AnkiClient {
    val support: Support

    /** Deck names, for the picker. */
    fun decks(): List<String>

    /** Model (note type) names. */
    fun models(): List<String>

    /** Create a note. @return the note id, or null on failure. */
    fun addNote(deck: String, model: String, fields: Map<String, String>, tags: List<String>): Long?

    fun isReachable(): Boolean
}

/**
 * Update channel. Three implementations (§7.5), all reading the same
 * `latest.json` manifest — which is why the check logic is shared and only the
 * install action differs.
 *
 * The MSI case carries a constraint the interface encodes: Windows Installer
 * reference-counts files, so an application may **not** replace its own
 * installed files. [UpdateChannel.install] therefore takes the downloaded
 * artifact and a reinstall strategy, rather than a path to overwrite.
 */
interface UpdateChannel {
    val support: Support

    /** Platform artifact suffix in the manifest: `msi`, `dmg`, `deb`, `rpm`, `flatpak`. */
    val artifactKind: String

    fun install(downloadedArtifact: java.io.File, silent: Boolean): Boolean
}

/**
 * Game-audio capture, for the "original game audio" on an Anki card
 * (PORTING §4.3). WASAPI loopback / ScreenCaptureKit audio / PipeWire monitor.
 *
 * What is *reusable* from upstream is everything downstream of the bytes:
 * `GameAudioSnapshot`, `VoiceLineSnap`, `SilenceGate`, `Loudness`,
 * `RecordingPlayer`, and the Silero VAD model itself — all pure logic over a
 * PCM ring buffer. Only "pour PCM into the buffer" is platform code, which is
 * why this interface is small and the port is large.
 *
 * One improvement the desktop gets for free and should not squander: Windows
 * 10 2004+ can capture **per process tree**, which solves "exclude our own
 * audio" structurally. Upstream had to match on `usage` and `excludeUid`.
 */
interface AudioLoopbackBackend {
    val support: Support

    /** True when capture can be scoped to a single process rather than the system mix. */
    val supportsPerProcess: Boolean

    /** Start feeding 16 kHz mono PCM16 into [sink]. */
    fun start(sink: (ShortArray) -> Unit, processId: Long? = null): Boolean

    fun stop()
}

/**
 * The screen model source. Displays come and go, DPI changes when the user
 * drags a window across monitors, and every one of those invalidates cached
 * regions — so the shell owns this and pushes changes, rather than the core
 * polling.
 */
interface DisplayWatcher {
    fun current(): ScreenGeometry

    fun onChange(listener: (ScreenGeometry) -> Unit)
}
