# PlayTranslate for PC

A real-time screen translation app for PC, built for both language learners and people who just want to play. Windows, macOS, and Linux.

**This is a fork of [PlayTranslate](https://github.com/dominostars/playtranslate), it is at the design stage, and it is not being maintained.** What is here is the porting design, the packaging setup for all five release formats, and the first part of the port itself. There is still no working app — if you want one today, get [the Android original](https://github.com/dominostars/playtranslate).

**[Read the porting design →](PORTING.md)** · **[Packaging →](packaging/README.md)** · **[The port so far →](pc/README.md)** · **[What is missing →](pc/GAP_REPORT.md)**

## What this is

PlayTranslate reads the text off your game screen, looks up the words, translates them, and paints the result back over the original. On Android it does that with a floating overlay you drag around with your finger.

On a PC there is no finger. There is a mouse, a keyboard, and usually more than one monitor — and the mouse is the thing your game is using to aim. So a fair amount of this cannot be ported; it has to be designed again. That is what this repo is: the design, plus an inventory of which parts of the Android codebase actually survive the trip.

The short version: about a quarter of the Kotlin is pure JVM with no Android in it at all, the dictionary and model packs are format-identical, and the two native inference engines (MNN for LLMs and OCR, slimt for offline NMT) are already cross-platform C++. Those carry over. The UI, the capture, the overlay, and the input model do not.

## Status

Design stage, with the first part of the port now written. The Android source is here untouched; the PC port is designed, and the pieces that do not depend on a platform API exist — see [`pc/`](pc/README.md):

- **Written and verified**: the action registry and hotkey decision machine (upstream's shadow-window logic, ported), the translation waterfall with an explicit "no offline model" answer instead of Android's always-succeeds ML Kit rung, the loopback HTTP + WebSocket bridge with its 14 security self-checks passing, the web panel (one route per upstream screen, 13 locales), the Linux capability probe, and the payload that the packaging scripts consume.
- **Not written**: every platform backend (capture, overlay, hotkeys, tray, audio), the native overlay layer, the dictionary/OCR stack, and the webview host. [`pc/GAP_REPORT.md`](pc/GAP_REPORT.md) lists the gaps and the evidence for each claim, and corrects seven numbers in `PORTING.md` against measurement.

Packaging is set up and testable today: Windows **MSI**, macOS **DMG**, and Linux **deb / rpm / Flatpak**, plus the icon generator and the release manifest. See [`packaging/README.md`](packaging/README.md) for the build commands and [`packaging/RELEASING.md`](packaging/RELEASING.md) for how to cut a release. The Linux **deb** path has now been exercised end to end against a real payload; the other four still have not been built here.

**I do not plan to maintain this.** It is a fork I made to work out how the port would actually go — what survives contact with a desktop, what has to be rewritten, and where the platform differences bite. The design doc is the deliverable. If you want to take it further, take it; the license already says you can.

## What carries over, and what does not

| | Android original | PC port |
|---|---|---|
| OCR | ML Kit, plus optional Meiki / manga-ocr / PaddleOCR | **Meiki / manga-ocr / PaddleOCR only.** ML Kit has no desktop build, and it is the fallback for 23 of the 26 source languages — so the OCR floor gets rebuilt on MNN |
| OCR models | 8 packs, 173 MB | **Same files, byte for byte** — they are already `.mnn` |
| Dictionaries | 25 source packs, 58 target packs | **Same files, byte for byte** |
| Language engines | Sudachi, HanLP, KOMORAN, Snowball, newmm, CAMeL, Morfologik | Same — all pure JVM |
| Offline translation | Bergamot (slimt), plus optional MNN LLMs | Same, and easier — slimt is a desktop library first |
| Local LLMs | Gemma 4 E2B, Qwen 3.5 2B, Hy-MT2, through MNN | Same, **plus your own local server** (Ollama, LM Studio, llama.cpp) |
| UI | 171 files of hand-built Android Views | **Rewritten as a web app** — one build for all three platforms |
| Overlay | A floating window you drag with your finger | **Native, per platform** — with a keyboard-first input model instead of drag |
| Anki | AnkiDroid content provider | AnkiConnect |
| Game audio | AudioPlaybackCapture | WASAPI loopback / ScreenCaptureKit / PipeWire |
| TTS | Android TextToSpeech | SAPI, AVSpeechSynthesizer, speech-dispatcher, or bundled Piper |
| Camera tool | Point your phone at the screen | Mostly pointless on a PC — but the same tracker handles a **capture card** |

## Planned platforms

| Platform | Overlay over games | Screen capture | Global hotkeys | Game audio |
|---|---|---|---|---|
| Windows 10/11 | Yes | Windows.Graphics.Capture, DXGI | Raw Input, no hook needed | WASAPI loopback |
| macOS 13+ | Yes | ScreenCaptureKit | CGEventTap (needs Accessibility permission) | ScreenCaptureKit |
| Linux — KDE Plasma | Yes, on X11 and Wayland | XComposite / PipeWire via portal | XInput2 / portal | PipeWire monitor |
| Linux — XFCE | Yes (X11) | XComposite | XInput2 | PulseAudio monitor |
| Linux — GNOME (X11) | Yes | XComposite | XInput2 | PulseAudio monitor |
| Linux — GNOME (Wayland) | **No** | PipeWire via portal | portal, and it gives no key-up | PipeWire monitor |

If you are on GNOME Wayland, no ordinary app can draw over your game — GNOME does not implement `wlr-layer-shell`. The port falls back to a side panel there. Everything else still works. X11 sessions, KDE, and XFCE are unaffected.

## Planned features

### New on PC

- **Hotkeys instead of gestures**: The floating icon's drag / hold / tap become rebindable global hotkeys. Mouse side buttons count as first-class bindings — games rarely take them. Hold-to-preview still works, except on Wayland, where you get a toggle instead.
- **Tray icon**: The floating icon becomes a system tray icon. On by default, and you can turn it off. On GNOME there is no tray unless you install the AppIndicator extension, so it is never the only way in — there is always a hotkey and a main window.
- **Web UI**: Settings, word cards, the workspace, Anki review, and history are a web app, laid out like the Android screens but built once for all three platforms. Its wording comes from the Android resources — 1006 base strings already translated into 12 locales — converted mechanically rather than re-authored. The overlay stays native, because drawing text over someone else's window frame by frame is the one thing a browser is bad at.
- **Multi-monitor**: Each monitor keeps its own capture region and its own overlay state, the way the Android app does it per display.
- **Capture cards**: Route a console or handheld through a capture card and it becomes just another game window. This is the one place the camera tool's planar tracker is still worth having.
- **Local LLM servers**: Point it at Ollama, LM Studio, or llama.cpp on `127.0.0.1` and translate with a model you already have. No download, and better output than a 2B model that fits in a phone.
- **Keyboard-first mode**: Number the detected text boxes in reading order and pick one with the keyboard, with the result spoken by TTS. No pointer involved.
- **Region select by keyboard**: Snap to a text box, a window, or a monitor edge, then nudge with the arrow keys.

### Carried over from Android

- **Offline**: OCR and dictionary lookups work without an internet connection, with optional offline translation models.
- **Word lookup**: Hover over a word for its definition. On Android you drag a lens onto it; here you just leave the pointer there.
- **Auto Translation Mode**: Translates as dialogue changes, no key press required. The sentence-completion gate that keeps half-typed lines from being translated wrong is platform-independent, so it comes over as-is.
- **Furigana / Pinyin mode**: Reading hints above the characters, in real time.
- **Capture regions**: Crop to just the dialogue box, the subtitles, or any custom area.
- **Text-to-speech**: Hear the text spoken aloud.
- **Anki export**: Save sentences to Anki with the original text, the translation, the word list, target words, TTS, and a screenshot. Card type presets for popular decks. Works through AnkiConnect, so it works with desktop Anki.
- **Yomitan integration**: Yomitan dictionaries import as-is, with pitch accent, frequency chips, kanji enrichment, and merged term definitions everywhere, including in Anki cards.
- **Text History**: A record of captured sentences. Off by default.

### Gone

- **The magnifier lens.** It exists because your finger covers the word you are looking at. A mouse pointer is smaller and does not cover anything.
- **The drag-to-look-up gesture**, and the icon's hold and tap gestures with it.
- **Pointing the camera at your screen.** Just capture the screen.

## How it installs

Five formats, one build each. [`packaging/README.md`](packaging/README.md) has the commands, and marks which of them have actually been built and tested on what.

| Platform | Format | What to know |
|---|---|---|
| Windows | **MSI** | Per-user install, no admin needed. Unsigned builds will show a SmartScreen warning until there is a code-signing certificate |
| macOS | **DMG** | Unsigned and un-notarized builds need a right-click → Open. Screen-recording and Accessibility permissions are tied to the signing identity, so if the signature changes on an update, you have to grant them again |
| Linux | **deb**, **rpm**, and **Flatpak** | Flatpak is the recommended one — a single build that works on every distro. It goes through portals for screen capture and hotkeys, which is the supported way to do both on Wayland |

## Planned hotkey defaults

Everything is rebindable, and there is a mouse-side-button column because that is where the free real estate is.

| Action | Default |
|---|---|
| Look up words at the pointer | `Ctrl+Alt+Q` / mouse button 4 (hold) |
| Translate the current region | `Ctrl+Alt+W` / mouse button 5 |
| Toggle auto-translate | `Ctrl+Alt+E` |
| Pick a capture region | `Ctrl+Alt+R` |
| Send the last sentence to Anki | `Ctrl+Alt+A` |
| Speak the last sentence | `Ctrl+Alt+S` |
| Open the result panel / workspace | `Ctrl+Alt+D` |
| Translate the clipboard | `Ctrl+Alt+C` |
| Hide or show the overlay | `Ctrl+Alt+H` |
| Panic — drop every latched state and hide all overlays | `Ctrl+Alt+Shift+X` |

No bare letters in the defaults. Your game is using those.

## Supported languages

Same set as the Android app, because the packs are the same files.

### Game languages (read from the screen)

| Language              | Native name      | Code     |
|-----------------------|------------------|----------|
| English               | English          | en       |
| Chinese (Simplified)  | 简体中文          | zh       |
| Chinese (Traditional) | 繁體中文          | zh-Hant  |
| Hindi                 | हिन्दी            | hi       |
| Spanish               | Español          | es       |
| Arabic                | العربية          | ar       |
| French                | Français         | fr       |
| Portuguese            | Português        | pt       |
| Russian               | Русский          | ru       |
| Indonesian            | Bahasa Indonesia | id       |
| German                | Deutsch          | de       |
| Japanese              | 日本語           | ja       |
| Turkish               | Türkçe           | tr       |
| Vietnamese            | Tiếng Việt       | vi       |
| Korean                | 한국어           | ko       |
| Italian               | Italiano         | it       |
| Thai                  | ไทย              | th       |
| Dutch                 | Nederlands       | nl       |
| Romanian              | Română           | ro       |
| Hungarian             | Magyar           | hu       |
| Swedish               | Svenska          | sv       |
| Catalan               | Català           | ca       |
| Danish                | Dansk            | da       |
| Finnish               | Suomi            | fi       |
| Norwegian             | Norsk            | no       |
| Polish                | Polski           | pl       |

### Translation languages (translated for you)

| Language       | Native name        | Code |
|----------------|--------------------|------|
| English        | English            | en   |
| Chinese        | 中文               | zh   |
| Hindi          | हिन्दी              | hi   |
| Spanish        | Español            | es   |
| Arabic         | العربية             | ar   |
| French         | Français           | fr   |
| Bengali        | বাংলা              | bn   |
| Portuguese     | Português          | pt   |
| Russian        | Русский            | ru   |
| Urdu           | اردو               | ur   |
| Indonesian     | Bahasa Indonesia   | id   |
| Swahili        | Kiswahili          | sw   |
| German         | Deutsch            | de   |
| Japanese       | 日本語             | ja   |
| Marathi        | मराठी              | mr   |
| Telugu         | తెలుగు              | te   |
| Turkish        | Türkçe             | tr   |
| Vietnamese     | Tiếng Việt         | vi   |
| Korean         | 한국어             | ko   |
| Tamil          | தமிழ்              | ta   |
| Persian        | فارسی              | fa   |
| Italian        | Italiano           | it   |
| Thai           | ไทย                | th   |
| Gujarati       | ગુજરાતી             | gu   |
| Polish         | Polski             | pl   |
| Ukrainian      | Українська         | uk   |
| Tagalog        | Tagalog            | tl   |
| Malay          | Bahasa Melayu      | ms   |
| Kannada        | ಕನ್ನಡ              | kn   |
| Dutch          | Nederlands         | nl   |
| Romanian       | Română             | ro   |
| Hungarian      | Magyar             | hu   |
| Greek          | Ελληνικά           | el   |
| Czech          | Čeština            | cs   |
| Swedish        | Svenska            | sv   |
| Belarusian     | Беларуская         | be   |
| Hebrew         | עברית              | he   |
| Bulgarian      | Български          | bg   |
| Catalan        | Català             | ca   |
| Slovak         | Slovenčina         | sk   |
| Haitian Creole | Kreyòl Ayisyen     | ht   |
| Croatian       | Hrvatski           | hr   |
| Danish         | Dansk              | da   |
| Finnish        | Suomi              | fi   |
| Norwegian      | Norsk              | no   |
| Albanian       | Shqip              | sq   |
| Galician       | Galego             | gl   |
| Slovenian      | Slovenščina        | sl   |
| Lithuanian     | Lietuvių           | lt   |
| Latvian       | Latviešu           | lv   |
| Afrikaans      | Afrikaans          | af   |
| Macedonian     | Македонски         | mk   |
| Estonian       | Eesti              | et   |
| Georgian       | ქართული            | ka   |
| Welsh          | Cymraeg            | cy   |
| Maltese        | Malti              | mt   |
| Icelandic      | Íslenska           | is   |
| Irish          | Gaeilge            | ga   |
| Esperanto      | Esperanto          | eo   |

## Optional: Online Translation Backends

By default, translation runs offline. For higher quality you can plug in an API key for any of these, and add as many as you like — each service is its own entry, so you can keep several configured and reorder them to pick which one translates first:

- **DeepL**, **OpenAI**, **Gemini**, **DeepSeek**, **Mistral**, **Groq**, **OpenRouter**, **Claude**, or any other OpenAI-compatible endpoint, including a local one

## Optional: Anki Flashcards

Install [Anki](https://apps.ankiweb.net/) with [AnkiConnect](https://ankiweb.net/shared/info/2055492159) and the port exports cards straight to your decks. No AnkiDroid, no content provider, no permissions dance.

## Credits

Everything below is the original project's list, carried over because the port reuses the same libraries, models, and linguistic data. Nothing in this fork was written by the people named here.

### Libraries and services

- [ML Kit](https://developers.google.com/ml-kit): on-device OCR and translation
- [Sudachi](https://github.com/WorksApplications/Sudachi): Japanese morphological analysis (Apache 2.0)
- [HanLP](https://github.com/hankcs/HanLP): Chinese word segmentation
- [KOMORAN](https://github.com/shineware/KOMORAN): Korean morphological analysis
- [Snowball stemmers](https://snowballstem.org/) via [Apache Lucene](https://lucene.apache.org/): Latin/European stemming
- [Lingva](https://github.com/thedaviddelta/lingva-translate): online translation
- [AnkiDroid](https://github.com/ankidroid/Anki-Android): flashcard integration
- [MNN](https://github.com/alibaba/MNN): on-device LLM and OCR inference engine (Apache 2.0)
- [PaddleOCR](https://github.com/PaddlePaddle/PaddleOCR): on-device OCR from the bundled PP-OCRv6 text detector and unified recognizer, plus optional per-script recognizers (Apache 2.0)
- [OpenCV](https://opencv.org/): image processing for OCR (DBNet contour postprocessing, crop rectification) and for the camera tool's planar tracker (ORB features, pyramidal Lucas-Kanade flow, RANSAC homography fitting) (Apache 2.0)
- [Silero VAD](https://github.com/snakers4/silero-vad): voice-activity detection for the game-audio trimmer, bundled as a converted MNN model (MIT)
- [OpenCC4j](https://github.com/houbb/opencc4j): Simplified/Traditional Chinese conversion (Apache 2.0)
- [slimt](https://github.com/jerinphilip/slimt): tiny [Marian](https://marian-nmt.github.io/)-based NMT engine that runs the Bergamot offline models (GPL-2.0-or-later, with MPL-2.0 Marian components)
- [OkHttp](https://square.github.io/okhttp/): HTTP client for online translation and downloads (Apache 2.0)
- [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization): JSON (de)serialization for the translation backends and data models (Apache 2.0)
- [Gson](https://github.com/google/gson): streaming JSON parsing for Yomitan dictionary banks (Apache 2.0)
- [CameraX](https://developer.android.com/training/camerax): camera preview and analysis frames for the camera tool (Apache 2.0)
- [Material Components for Android](https://github.com/material-components/material-components-android) and [Material Symbols](https://fonts.google.com/icons): UI components, and icons traced from the Outlined symbol set (Apache 2.0)

### Adapted from other projects

Work we reimplemented rather than linked. No source was copied verbatim.

- [offline-translator](https://github.com/DavidVentura/offline-translator) (David Ventura): its `translator-rs` planar tracking engine is the design reference for the camera tool's keyframe-OCR tracker. We took the split between optical flow that sustains correspondences and descriptor re-matching that corrects drift, the Idle/Locked/Lost lifecycle with inlier hysteresis, the anchor cache that re-locks a previously seen scene without re-running OCR, and several tuned thresholds. Independently reimplemented in Kotlin over OpenCV, without the reference's IMU prior and with a smoothing filter of our own (offline-translator GPL 3.0; `translator-rs` MIT)
- [PyThaiNLP](https://github.com/PyThaiNLP/pythainlp): Thai word segmentation, a faithful Kotlin port of its `newmm` maximal-matcher, run over a word list that includes its CC0 list (Apache 2.0)
- [docTR](https://github.com/mindee/doctr) and [EasyOCR](https://github.com/JaidedAI/EasyOCR): line-grouping logic adapted for OCR word-box assembly, following docTR's recognize-then-group architecture with thresholds modelled on docTR `_resolve_lines` and EasyOCR `group_text_box` (Apache 2.0)
- [Yomitan](https://github.com/yomidevs/yomitan): the dictionary format our importer and styled renderer target — structured-content glossaries, per-dictionary CSS scoping, and media references. `YomitanContentHtml` is an independent Kotlin implementation of the format's tag and inline-style whitelists, following Yomitan's render-side sanitisation model (GPL 3.0)

### (Optional) Downloadable Offline Models

- Gemma 4 E2B (Google): downloadable as an optional offline pack, MNN conversion by [taobao-mnn](https://huggingface.co/taobao-mnn/gemma-4-E2B-it-MNN) (Apache 2.0)
- Hy-MT2 1.8B (Tencent): translation-specialised model, downloadable as an optional offline pack, MNN conversion by [@starsharp06sharp](https://huggingface.co/starsharp06sharp/Hy-MT2-1.8B-MNN) (Apache 2.0)
- Qwen 3.5 2B (Alibaba): downloadable as an optional offline pack, MNN conversion by [taobao-mnn](https://huggingface.co/taobao-mnn/Qwen3.5-2B-MNN) (Apache 2.0)
- Retired packs, still recognised while installed: Qwen 2.5 1.5B Instruct (Apache 2.0) and Hunyuan-MT 1.5 1.8B (Tencent HY Community License; not available in the EU, UK, or South Korea)
- [PaddleOCR PP-OCRv5+v6 recognizers](https://github.com/PaddlePaddle/PaddleOCR): optional per-script OCR recognizer packs for additional scripts (e.g. Korean, Arabic, Cyrillic, Thai), downloadable per source language (Apache 2.0)
- [Meiki](https://github.com/rtr46/meikiocr): high-accuracy Japanese OCR model (D-FINE), downloadable as an optional offline pack (LGPL 3.0)
- [MangaOCR](https://huggingface.co/jzhang533/manga-ocr-base-2025): Japanese OCR refinement for stylized and vertical text, downloadable as an optional offline pack. `manga-ocr-base-2025` by jzhang533, based on [manga-ocr](https://github.com/kha-white/manga-ocr) by kha-white (Maciej Budyś), both Apache 2.0, converted to fp16 MNN for on-device use. The manga-ocr model family is trained using the [Manga109-s](https://manga109.github.io/manga109-project-website/en/index.html) dataset, whose use is acknowledged per its terms
- [Firefox Translations (Bergamot)](https://github.com/mozilla/translations): Mozilla's offline NMT model pairs, downloadable for offline translation (CC BY-SA 4.0)

### Linguistic data

- [JMdict](https://www.edrdg.org/jmdict/j_jmdict.html), [KANJIDIC2](https://www.edrdg.org/kanjidic/kanjidic2.html), and [JMnedict](https://www.edrdg.org/enamdict/enamdict_doc.html): Japanese dictionary, kanji, and proper-name data (EDRDG licence; JMnedict offered as an optional in-app [Yomitan](https://github.com/yomidevs/jmdict-yomitan) download)
- [CC-CEDICT](https://cc-cedict.org/wiki/): Chinese-English dictionary (CC BY-SA 4.0)
- [CFDICT](https://chinese.gratis/cfdict.php): Chinese-French dictionary, used for French-target glosses (CC BY-SA 3.0)
- [HanDeDict](https://handedict.zydeo.net/): Chinese-German dictionary, used for German-target glosses (CC BY-SA 2.0 DE)
- [Wiktionary](https://en.wiktionary.org/) via [kaikki.org](https://kaikki.org/): multilingual dictionary entries (CC BY-SA)
- [Tatoeba](https://tatoeba.org/): example sentences (CC BY 2.0)
- [PanLex](https://panlex.org/): multilingual translation pairs (CC0)
- [wordfreq](https://github.com/rspeer/wordfreq): word frequency data
- [Camel Morph MSA](https://github.com/CAMeL-Lab/camel_morph): Arabic morphology, used to map inflected and broken-plural surface forms to dictionary lemmas (© CAMeL Lab, NYU Abu Dhabi; CC BY 4.0, modified)
- [Arramooz](https://github.com/linuxscout/arramooz): Arabic morphological dictionary (© Taha Zerrouki; GPL 3.0)
- [Morfologik](https://github.com/morfologik/morfologik-stemming) / PoliMorf: Polish morphology, used to map inflected surface forms to dictionary lemmas (© Marcin Miłkowski; BSD-2-Clause)
- [SudachiDict](https://github.com/WorksApplications/SudachiDict): Japanese tokenizer dictionary bundled for Sudachi, including [UniDic](https://clrd.ninjal.ac.jp/unidic/) (© NINJAL) and part of [mecab-ipadic-NEologd](https://github.com/neologd/mecab-ipadic-neologd) (Apache 2.0)
- [Jiten](https://jiten.moe/): Japanese frequency data, offered as an optional in-app Yomitan dictionary download (CC BY-SA 4.0)
- [Wikimedia Commons](https://commons.wikimedia.org/): pronunciation audio for word playback and Anki cards, fetched on demand. Each clip carries its own author and license (typically CC BY / CC BY-SA / public domain), shown as a credit that travels onto exported cards

## License

[GPL 3.0](LICENSE) — the same license as the original.

PlayTranslate is GPL-3.0, so this fork is GPL-3.0 too. The copyright in the code and in all the bundled linguistic data belongs to the original project and to the upstream sources credited above, not to me.

## Naming

The original project's [TRADEMARK.md](TRADEMARK.md) is explicit that the GPL covers the code and **not** the PlayTranslate name, logo, or visual identity. Stating that this is a fork of PlayTranslate is fine, which is what this repo does. Anything *distributed* from here would have to ship under a different name, a different icon, and a different application id.

## Maintenance

Not maintained. Issues and pull requests may go unanswered, and there is no support channel — the original project's [Discord](https://discord.gg/DVCj6p7MUC) is for the Android app, not for this.
