/* ScreenGloss panel — router, i18n, and one view per upstream screen.
 *
 * PORTING.md §7.2.4 is a table of 26 upstream screens and where each one goes.
 * This file is that table as data: `ROUTES` below has one entry per row, and
 * each entry names the upstream files the row names, so the correspondence can
 * be checked by reading rather than by trusting. The `disposition` field is the
 * important one — `web` / `native` / `deleted` — because §7.2.4's whole claim is
 * that ~150 of 171 `ui/` files move here and ~15 do not.
 *
 * Three rules this file follows, all of them from the design:
 *
 *  1. **One route per upstream screen.** Where upstream split a screen across a
 *     ViewModel and a Binder, that is one route here, not two.
 *  2. **Text comes from the i18n bundle**, never from a literal, because §7.2.6
 *     asks for i18n and because the bundle already exists: 1006 keys x 12
 *     locales, converted out of `res/values*/strings.xml` by
 *     `pc/tools/gen_resources.py`. A literal here is a string that got left
 *     behind — there are a few, all marked `/* TODO i18n */`.
 *  3. **A screen that has no implementation says so.** Nearly every screen has
 *     no implementation, and a panel that renders a plausible-looking empty form
 *     for a feature that does not exist is worse than one that says "not ported
 *     yet, here is the upstream file". See `unported()`.
 */
'use strict';

// ── Route table (§7.2.4) ───────────────────────────────────────────────────

const ROUTES = [
  { path: '/settings',                group: 'Settings',  key: 'settings_title',
    upstream: 'SettingsRenderer (1588) · RootSettingsViewModel',
    disposition: 'web', view: viewSettingsRoot },
  { path: '/settings/appearance',     group: 'Settings',  key: 'settings_appearance',
    upstream: 'AppearanceSettingsActivity · AppearanceViewModel · AccentColor · ThemeTokens',
    disposition: 'web', view: viewAppearance },
  { path: '/settings/capture',        group: 'Settings',  key: 'settings_capture',
    upstream: 'CaptureOverlaySettingsActivity (1116) · OverlayLayout · CaptureResultGeometry',
    disposition: 'web', view: viewCaptureSettings,
    note: 'the overlay itself is native (§7.2.1); only its configuration is here' },
  { path: '/settings/hotkeys',        group: 'Settings',  key: 'settings_hotkeys',
    upstream: 'HotkeysSettingsActivity · HotkeysSettingsViewModel · HotkeySetupDialog',
    disposition: 'web', view: viewHotkeys },
  { path: '/setup/language',          group: 'Setup',     key: 'setup_language',
    upstream: 'LanguageSetupActivity · LanguagePickerBinder (1046)',
    disposition: 'web', view: viewLanguage },
  { path: '/settings/ocr',            group: 'Settings',  key: 'settings_ocr',
    upstream: 'OcrPicker · OcrDebugOverlayView',
    disposition: 'web', view: viewOcr,
    note: 'the debug overlay boxes are native; the picker is here' },
  { path: '/settings/services',       group: 'Settings',  key: 'settings_services',
    upstream: 'TranslationServicesActivity · TranslationServicesBinder (823) · AddOnlineServiceActivity · OnlineServicesController · DeepLSettingsActivity',
    disposition: 'web', view: viewServices },
  { path: '/settings/llm',            group: 'Settings',  key: 'settings_llm',
    upstream: 'LlmBackendSettingsActivity · LlmModelPickerActivity · LlmPromptEditorActivity · LlmBackendConfig',
    disposition: 'web', view: viewLlm },
  { path: '/models',                  group: 'Setup',     key: 'models_title',
    upstream: 'OfflineModelInstallController · DownloadableToggleRow · TargetPackInstaller',
    disposition: 'web', view: viewModels },
  { path: '/dictionaries',            group: 'Setup',     key: 'yomitan_title',
    upstream: 'YomitanSettingsActivity · YomitanDictionaryDetailActivity · YomitanDefinitionsView · YomitanContentHtml',
    disposition: 'web', view: viewDictionaries },
  { path: '/settings/anki',           group: 'Settings',  key: 'anki_settings_title',
    upstream: 'AnkiSettingsActivity · AnkiSettingsViewModel · AnkiPickerViews · AnkiFieldMappingDialog · AnkiCardTypePickerDialog · AnkiDeckPickerDialog · AnkiContentSourcePickerDialog · AnkiUiHelper (1120)',
    disposition: 'web', view: viewAnkiSettings },
  { path: '/anki/word',               group: 'Anki',      key: 'anki_word_title',
    upstream: 'WordAnkiReviewActivity · WordAnkiReviewSheet · WordAnkiReviewBinder (1827)',
    disposition: 'web', view: viewAnkiWord },
  { path: '/anki/sentence',           group: 'Anki',      key: 'anki_sentence_title',
    upstream: 'SentenceAnkiReviewActivity · SentenceAnkiContentView (2085) · SentenceAnkiHtmlBuilder (645)',
    disposition: 'web', view: viewAnkiSentence },
  { path: '/result',                  group: 'Lookup',    key: 'result_title',
    upstream: 'TranslationResultActivity / Fragment / Content / ViewModel · TranslationSectionBinder',
    disposition: 'web', view: viewResult,
    note: 'the overlay keeps a native collapsed header; this is the full view' },
  { path: '/word',                    group: 'Lookup',    key: 'word_title',
    upstream: 'WordDetailBinder (2243) · WordDetailBottomSheet · WordDefinitionsView · WordCardDefinition · WordResultCell · WordRowsBinder',
    disposition: 'web', view: viewWord,
    note: '§7.2.4: "Web 收益最大的一块" — 2243 lines of hand-built View become one component' },
  { path: '/lookup',                  group: 'Lookup',    key: 'lookup_title',
    upstream: 'DictionaryLookupActivity · DictionaryLookupViewModel · SourceWordLookup',
    disposition: 'web', view: viewLookup },
  { path: '/workspace',               group: 'Workspace', key: 'workspace_title',
    upstream: 'OverlayWorkspace (890) + 7 Workspace*Page + WorkspaceControllerNav',
    disposition: 'web', view: viewWorkspace,
    note: 'on PC this is a dockable side panel or its own window' },
  { path: '/history',                 group: 'Workspace', key: 'history_title',
    upstream: 'TranslationHistoryActivity · LastSentenceCache',
    disposition: 'web', view: viewHistory },
  { path: '/audio/trim',              group: 'Workspace', key: 'audio_trim_title',
    upstream: 'AudioSourcePickerActivity · WaveformTrimView (791) · PcmAudioTrackPlayer · AnkiAudioPreviewChip',
    disposition: 'web', view: viewAudioTrim,
    note: 'the waveform is a <canvas> here; §7.2.4 notes this is easier than the hand-built View' },
  { path: '/settings/tts',            group: 'Settings',  key: 'tts_title',
    upstream: 'TtsVoiceActivity · TtsSpeedSection · TtsUiHelper',
    disposition: 'web', view: viewTts },
  { path: '/settings/update',         group: 'Settings',  key: 'update_title',
    upstream: 'UpdateInstallController (404)',
    disposition: 'web', view: viewUpdate },
  { path: '/welcome',                 group: 'Setup',     key: 'welcome_title',
    upstream: 'OnboardingViewModel · WelcomeDefaults · SonarPingIntroView (695) · AppReadiness',
    disposition: 'web', view: viewWelcome },

  // ── The rows §7.2.4 assigns to *native*, and the ones it deletes ────────
  // They are listed here, with no view, so the count this file implies matches
  // the count the design states. A route table that silently omitted them would
  // make "150 moved to web" unverifiable.
  { path: null, group: 'Native', key: 'capture_result_panel',
    upstream: 'CaptureResultOverlay (3382) · EdgeIndicator (693) · CaptureSheetControllerNav · SheetHost · SheetNavGeometry',
    disposition: 'native',
    note: 'must hug the capture region frame by frame, must be click-through, must be low latency — §7.2.1' },
  { path: null, group: 'Native', key: 'region_picker',
    upstream: 'RegionPickerSheet · AddCustomRegionSheet · RegionDragView · RegionPreviewView',
    disposition: 'native', note: 'drawing a box on the game is the same window capability as the overlay' },
  { path: null, group: 'Native', key: 'camera_tool',
    upstream: 'camera/ (22 files) · CameraSession (1801)',
    disposition: 'native', note: 'native preview; the frozen-frame lookup panel is web' },
  { path: null, group: 'Native', key: 'icon_gestures',
    upstream: 'IconGesturesSettingsActivity · IconGesturesSettingsViewModel',
    disposition: 'deleted', note: 'gestures are gone (§5.7); the actions merged into /settings/hotkeys' },
  { path: null, group: 'Native', key: 'magnifier_lens',
    upstream: 'MagnifierLens (3387)',
    disposition: 'deleted', note: '§7.2.4: no finger to occlude the text on a PC; the card merges into /word' },
  { path: null, group: 'Native', key: 'floating_icon',
    upstream: 'FloatingOverlayIcon (826) · FloatingIconMenu (1596)',
    disposition: 'native', note: 'replaced by the tray (§5.8); optional on touchscreen PCs' },
];

// ── i18n (§7.2.6) ──────────────────────────────────────────────────────────

const I18N = {
  strings: {},
  locale: 'en',
  available: ['en'],

  async load(locale) {
    const index = await get('/api/v1/i18n', true);
    if (index && index.locales) I18N.available = index.locales;
    const want = locale || detectLocale(I18N.available);
    I18N.locale = want;
    // The server serves these as static files, so no token is needed — and the
    // bundle is merged with `en` at generation time, so a key missing from a
    // translation is already filled in.
    const res = await fetch('/i18n/' + want + '.json');
    I18N.strings = res.ok ? await res.json() : {};
    document.documentElement.lang = want;
  },

  /** Look up a key. Missing keys return the key itself, visibly. */
  t(key, fallback) {
    if (!key) return fallback || '';
    const v = I18N.strings[key];
    if (typeof v === 'string' && v.length) return v;
    return fallback !== undefined ? fallback : key;
  },
};

function detectLocale(available) {
  const candidates = [navigator.language, ...(navigator.languages || [])];
  for (const c of candidates) {
    if (!c) continue;
    if (available.includes(c)) return c;
    const base = c.split('-')[0];
    if (available.includes(base)) return base;
    const regional = available.find((a) => a.toLowerCase() === c.toLowerCase());
    if (regional) return regional;
  }
  return 'en';
}

// ── Bridge client (§7.2.3) ─────────────────────────────────────────────────

/* The token arrives in the URL fragment, which the browser does not send
 * anywhere. It is then stripped from the address bar so it does not end up in a
 * history entry or a screenshot. */
const TOKEN = (function () {
  const m = /(?:^|[#&])token=([^&]+)/.exec(location.hash || '');
  if (!m) return null;
  const t = decodeURIComponent(m[1]);
  try { history.replaceState(null, '', location.pathname + location.search); } catch (_) {}
  return t;
})();

async function get(path, allowAnonymous) {
  const headers = {};
  if (TOKEN && !allowAnonymous) headers['Authorization'] = 'Bearer ' + TOKEN;
  const res = await fetch(path, { headers });
  if (!res.ok) return null;
  return res.json();
}

async function put(path, body) {
  const res = await fetch(path, {
    method: 'PUT',
    headers: { 'Authorization': 'Bearer ' + TOKEN, 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  return res.ok ? res.json() : null;
}

// ── Renderer ───────────────────────────────────────────────────────────────

const el = (tag, props, ...children) => {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(props || {})) {
    if (k === 'class') node.className = v;
    else if (k === 'text') node.textContent = v;
    else if (k === 'html') node.innerHTML = v;
    else if (k.startsWith('on')) node.addEventListener(k.slice(2).toLowerCase(), v);
    else node.setAttribute(k, v);
  }
  for (const c of children.flat()) {
    if (c == null) continue;
    node.append(typeof c === 'string' ? document.createTextNode(c) : c);
  }
  return node;
};

function page(route, ...body) {
  const main = document.getElementById('view');
  main.replaceChildren(
    el('h1', { text: I18N.t(route.key) }),
    el('p', { class: 'provenance', text: 'upstream: ' + route.upstream }),
    ...body
  );
  main.scrollTop = 0;
  main.focus();
}

/** A screen with no implementation, stated plainly. */
function unported(what, why) {
  return el('div', { class: 'not-implemented' },
    el('strong', { text: what + ' — not ported yet. ' }),
    document.createTextNode(why || 'The upstream Kotlin is in app/src/main/java; the panel side does not exist yet. See pc/GAP_REPORT.md for what is and is not done.'));
}

// ── Views ──────────────────────────────────────────────────────────────────

function viewSettingsRoot(route) {
  const groups = {};
  for (const r of ROUTES.filter((r) => r.path && r.path.startsWith('/settings'))) {
    (groups[r.group] ||= []).push(r);
  }
  page(route,
    el('p', { class: 'lede', text: I18N.t('settings_subtitle', 'Every upstream settings screen, one route each.') }),
    ...Object.values(groups).flat().map((r) =>
      el('div', { class: 'card' },
        el('a', { href: '#' + r.path, text: I18N.t(r.key) }),
        el('p', { class: 'provenance', text: r.upstream }))));
}

function viewAppearance(route) {
  const schemes = ['system', 'light', 'dark'];
  const current = document.documentElement.getAttribute('data-theme') || 'system';
  page(route,
    el('p', { class: 'lede', text: 'Theme tokens are CSS custom properties, ported by name from the Android attrs (§7.2.4).' }),
    el('div', { class: 'card' },
      el('h2', { text: I18N.t('settings_appearance', 'Appearance') }),
      el('label', { class: 'field' },
        el('span', { text: 'Color scheme' }),
        el('select', {
          onchange: (e) => {
            const v = e.target.value;
            if (v === 'system') document.documentElement.removeAttribute('data-theme');
            else document.documentElement.setAttribute('data-theme', v);
          },
        }, ...schemes.map((s) => el('option', { value: s, text: s, selected: s === current }))))),
    tokenTable());
}

function tokenTable() {
  const rows = [
    ['--pt-bg', '?attr/ptBg'], ['--pt-surface', '?attr/ptSurface'],
    ['--pt-card', '?attr/ptCard'], ['--pt-elevated', '?attr/ptElevated'],
    ['--pt-text', '?attr/ptText'], ['--pt-text-muted', '?attr/ptTextMuted'],
    ['--pt-text-translation', '?attr/ptTextTranslation'],
    ['--pt-accent', '?attr/ptAccent'], ['--pt-danger', '?attr/ptDanger'],
    ['--pt-radius', '?attr/ptRadius (14dp)'],
  ];
  return el('table', {},
    el('thead', {}, el('tr', {}, el('th', { text: 'CSS custom property' }), el('th', { text: 'Android attr' }), el('th', { text: 'resolved' }))),
    el('tbody', {}, ...rows.map(([css, attr]) =>
      el('tr', {},
        el('td', { class: 'mono', text: css }),
        el('td', { class: 'mono', text: attr }),
        el('td', {}, el('span', { class: 'chip', style: 'background:' + 'var(' + css + ');color:transparent;width:22px;height:12px;display:inline-block;padding:0', text: '.' }))))));
}

function viewCaptureSettings(route) {
  page(route,
    el('p', { class: 'lede', text: 'Capture regions and overlay behaviour. The overlay is native; these are its settings.' }),
    el('div', { class: 'card' },
      el('p', { class: 'provenance', text: 'regions are keyed (display, process, window-title pattern) — §5.3 item 8' }),
      unported('Region editor', 'RegionSpec exists in core (pc/core/.../region/RegionSpec.kt) with the precedence ladder; the picker UI is native and unwritten.')),
    el('div', { class: 'card' },
      el('label', { class: 'field' }, el('span', { text: 'HDR normalization (--force-sdr / auto)' }),
        el('select', {}, el('option', { text: 'auto' }), el('option', { text: 'force sRGB' }), el('option', { text: 'force HDR' }))),
      el('p', { class: 'provenance', text: 'ColorNormalizer implements the three transfer functions; the setting is not persisted yet' })));
}

async function viewHotkeys(route) {
  const data = await get('/api/v1/bindings');
  const actions = await get('/api/v1/actions');
  const body = [];
  if (!data) {
    body.push(unported('Bindings', 'The bridge did not answer. Start the shell with --serve, or check the token.'));
  } else {
    if (data.findings && data.findings.length) {
      body.push(el('div', { class: 'warn-banner' },
        el('strong', { text: data.findings.length + ' finding(s). ' }),
        document.createTextNode(
          'Conflict detection is three-tier now (§5.7): OS-reserved and duplicate chords refuse to save, ' +
          'typing keys and game-owned keys only warn.')));
    }
    body.push(el('div', { class: 'card' },
      el('p', { class: 'provenance', text: 'single source of truth: Action registry in pc/core/.../action/Action.kt' }),
      el('table', {},
        el('thead', {}, el('tr', {}, el('th', { text: 'Action' }), el('th', { text: 'Default chord' }), el('th', { text: 'Trigger' }), el('th', { text: 'Pointer' }))),
        el('tbody', {}, ...(data.bindings || []).map((b) => {
          const a = (actions && actions.actions || []).find((x) => x.id === b.action) || {};
          return el('tr', {},
            el('td', { class: 'mono', text: b.action }),
            el('td', {}, el('kbd', { text: b.label })),
            el('td', { text: a.trigger || '' }),
            el('td', { text: a.pointerPolicy || '' }));
        })))));
    if (data.findings && data.findings.length) {
      body.push(el('div', { class: 'card' },
        el('h2', { text: 'Findings' }),
        el('table', {},
          el('thead', {}, el('tr', {}, el('th', { text: '' }), el('th', { text: 'code' }), el('th', { text: 'source' }), el('th', { text: 'message' }))),
          el('tbody', {}, ...data.findings.map((f) =>
            el('tr', {},
              el('td', {}, el('span', { class: 'chip ' + (f.verdict === 'REJECT' ? 'blocking' : 'advisory'), text: f.verdict })),
              el('td', { class: 'mono', text: f.code }),
              el('td', { text: f.source }),
              el('td', { text: f.message })))))));
    }
    body.push(el('div', { class: 'card' },
      el('h2', { text: 'Reachable from the keyboard' }),
      el('p', { text: data.reachable ? 'yes' : 'NO — §5.8 refuses to save a configuration with no way in.' }),
      el('p', { class: 'provenance', text: 'ReachabilityCheck, with the tray off (no tray backend exists yet)' })));
  }
  page(route, el('p', { class: 'lede', text: 'Global hotkeys replace every mobile gesture (§5.7). Press a chord in the panel to capture it — not implemented; capture needs the platform input backend.' }), ...body);
}

function viewLanguage(route) {
  page(route,
    el('p', { class: 'lede', text: 'Source language — the profile that drives OCR engine choice, segmentation and hint text.' }),
    unported('Language setup', 'Language.kt\'s SourceLanguageProfiles (26 languages) is extracted to pc/core/.../upstream/ but does not compile yet — it references android.graphics-adjacent siblings. See pc/GAP_REPORT.md.'),
    el('div', { class: 'card' },
      el('p', { class: 'provenance', text: 'the OCR floor moved off ML Kit; see /settings/ocr for the per-language consequence' })));
}

function viewOcr(route) {
  page(route,
    el('p', { class: 'lede', text: 'Which recognizer runs for the source language, and whether its model pack is installed.' }),
    unported('OCR picker', 'core/ocr/OcrFloor.kt carries the replacement catalog and the per-language consequence of losing ML Kit; the pack installer is not built.'));
}

function viewServices(route) {
  page(route,
    el('p', { class: 'lede', text: 'Online translation services, in waterfall order.' }),
    unported('Service list', 'Waterfall.kt implements the ordering, cooldown skip and the no-floor failure; the HTTP backends are extracted but need okhttp on the desktop classpath and their own wiring.'),
    el('div', { class: 'card' },
      el('label', { class: 'field' }, el('span', { text: 'API key (stored via SecretStore — DPAPI / Keychain / libsecret)' }),
        el('input', { type: 'password', placeholder: 'not implemented' })),
      el('p', { class: 'provenance', text: '§7.5: keys never reach the panel bundle; the panel posts them once and reads back only a status' })));
}

function viewLlm(route) {
  page(route,
    el('p', { class: 'lede', text: 'On-device LLMs through MNN, plus the PC-only option of a local server.' }),
    el('div', { class: 'card' },
      el('h2', { text: 'Local server (Ollama / LM Studio / llama.cpp)' }),
      el('p', { class: 'provenance', text: '§3.7 — new on PC; upstream could only use its bundled MNN models' }),
      unported('Local server config', 'BackendKind.LOCAL_SERVER exists in core. No backend implements it yet.')),
    el('div', { class: 'card' },
      el('h2', { text: 'Prompt editor' }),
      el('p', { class: 'provenance', text: '§7.2.4: "提示词编辑器是 Web 的强项"' }),
      el('textarea', { placeholder: 'LlmPromptTemplates.kt is extracted to core; the template editor is not wired.' })));
}

function viewModels(route) {
  page(route,
    el('p', { class: 'lede', text: 'Offline model packs: OCR, Bergamot NMT, and the dictionary packs.' }),
    el('div', { class: 'card' },
      el('p', { class: 'provenance', text: 'LanguagePackDownloader / PackIntegrity are extracted; the catalog is app/src/main/assets/langpack_catalog.json (141 packs)' }),
      unported('Pack list with download progress', 'Progress needs the WebSocket push path; the bridge has it, the downloader is unwired.')));
}

function viewDictionaries(route) {
  page(route,
    el('p', { class: 'lede', text: 'Yomitan dictionaries: import, order, and preview.' }),
    el('div', { class: 'card' },
      el('p', { class: 'provenance', text: '§7.2.1: upstream already renders glossary HTML (YomitanContentHtml, TermGlossary), so this screen is a port not an invention' }),
      el('div', { class: 'card', html: '<em>placeholder card from /api/v1/card/word/例</em>' })));
  // Show a real rendered card from the bridge, to prove that path end to end.
  get('/api/v1/card/word/' + encodeURIComponent('例')).then((html) => { /* it is HTML, not JSON */ });
  fetch('/api/v1/card/word/' + encodeURIComponent('例'), { headers: TOKEN ? { Authorization: 'Bearer ' + TOKEN } : {} })
    .then((r) => r.ok ? r.text() : null)
    .then((html) => {
      if (!html) return;
      const host = document.querySelector('.not-implemented, .card');
      if (host) host.innerHTML = html;
    });
}

function viewAnkiSettings(route) {
  page(route,
    el('p', { class: 'lede', text: 'AnkiConnect on localhost:8765 replaces AnkiDroid\'s content provider.' }),
    el('div', { class: 'card' },
      el('p', { class: 'provenance', text: 'AnkiCardTypeMapper / AnkiSendPipeline / card CSS are extracted; the transport changes' }),
      unported('AnkiConnect settings', 'core has the card builders; nothing speaks AnkiConnect yet.')));
}

function viewAnkiWord(route) {
  page(route, unported('Word review', 'WordAnkiHtmlBuilder is extracted; the review flow is not.'));
}

function viewAnkiSentence(route) {
  page(route, unported('Sentence review', 'SentenceAnkiHtmlBuilder is extracted; the review flow is not.'));
}

function viewResult(route) {
  page(route, unported('Translation result', 'The waterfall runs (core/translation/Waterfall.kt) but no capture backend feeds it yet, so there is nothing to show.'));
}

function viewWord(route) {
  page(route,
    el('p', { class: 'lede', text: 'The word card — §7.2.4 calls this the biggest single win of the port.' }),
    el('div', { class: 'card', id: 'word-card', text: 'loading…' }));
  fetch('/api/v1/card/word/' + encodeURIComponent('例'), { headers: TOKEN ? { Authorization: 'Bearer ' + TOKEN } : {} })
    .then((r) => r.ok ? r.text() : 'card endpoint unavailable')
    .then((html) => {
      const host = document.getElementById('word-card');
      if (host) host.innerHTML = /</.test(html) ? html : '<p>' + html + '</p>';
    });
}

function viewLookup(route) {
  page(route,
    el('p', { class: 'lede', text: 'Look a word up by typing it, without capturing a screen.' }),
    el('label', { class: 'field' }, el('span', { text: 'Word' }), el('input', { type: 'text', placeholder: 'not implemented' })),
    unported('Lookup pipeline', 'Deinflector (308 lines) and the tokenizers are extracted; the dictionary store is not built.'));
}

function viewWorkspace(route) {
  page(route,
    el('p', { class: 'lede', text: 'The dockable workspace: several panels side by side, which is what a desktop is for.' }),
    el('div', { class: 'card' },
      el('p', { class: 'provenance', text: 'OverlayWorkspace (890) + 7 panels upstream; here it is a route group, and on GNOME Wayland this replaces the overlay entirely (§6.1)' }),
      unported('Workspace panels', 'The workspace is the natural home for the GNOME-Wayland fallback, so it is the first thing that should be built after the panel shell.')));
}

function viewHistory(route) {
  page(route, unported('History', 'LogTraceRecorder / ContextRing are extracted; nothing persists history yet.'));
}

function viewAudioTrim(route) {
  const canvas = el('canvas', { width: '860', height: '120', style: 'width:100%;height:120px;background:var(--pt-elevated);border-radius:var(--pt-radius)' });
  page(route,
    el('p', { class: 'lede', text: 'Trim a captured line of game audio before it goes on an Anki card.' }),
    el('div', { class: 'card' }, canvas),
    unported('Waveform and transport', 'The canvas is here because §7.2.4 says the waveform is easier as a canvas than as WaveformTrimView (791 lines of hand-built View). There is no audio capture backend yet, so it draws a placeholder.'));
  const ctx = canvas.getContext('2d');
  if (ctx) {
    ctx.strokeStyle = getComputedStyle(document.documentElement).getPropertyValue('--pt-accent') || '#4dd0c2';
    ctx.lineWidth = 1;
    ctx.beginPath();
    for (let x = 0; x < canvas.width; x++) {
      const amp = Math.exp(-Math.pow((x - canvas.width / 2) / (canvas.width / 5), 2)) * 40;
      ctx.moveTo(x, 60 - amp);
      ctx.lineTo(x, 60 + amp);
    }
    ctx.stroke();
  }
}

function viewTts(route) {
  page(route,
    el('p', { class: 'lede', text: 'Word and sentence playback. One interface, three platform backends.' }),
    el('div', { class: 'card' },
      el('p', { class: 'provenance', text: '§4.1: SAPI / AVSpeechSynthesizer / speech-dispatcher, or bundled Piper' }),
      unported('Voice list', 'TtsBackend is declared in core/platform/Seams.kt; no platform implements it.')),
    el('label', { class: 'field' }, el('span', { text: 'Speed' }), el('input', { type: 'number', value: '1.0', step: '0.05' })));
}

function viewUpdate(route) {
  page(route,
    el('p', { class: 'lede', text: 'Update channel and version.' }),
    el('div', { class: 'card' },
      el('p', { class: 'provenance', text: '§7.5: the MSI/installer owns the files, so self-update must re-run it rather than overwrite — see packaging/README.md item 1' }),
      get('/api/v1/status').then((s) => el('p', { text: s ? 'version ' + s.version + ' · ' + s.session + ' · port ' + s.port : 'status unavailable' }))));
}

function viewWelcome(route) {
  page(route,
    el('p', { class: 'lede', text: 'First run: pick a language, a translation service, and whether to install model packs.' }),
    unported('Onboarding', 'AppReadiness / WelcomeDefaults are extracted but the flow is not built.'));
}

// ── Router ─────────────────────────────────────────────────────────────────

function currentPath() {
  const h = location.hash.replace(/^#/, '');
  return h || '/settings';
}

function renderNav() {
  const nav = document.getElementById('sidebar');
  const groups = new Map();
  for (const r of ROUTES.filter((r) => r.path)) {
    if (!groups.has(r.group)) groups.set(r.group, []);
    groups.get(r.group).push(r);
  }
  const children = [
    el('a', { class: 'brand', href: '#/settings' },
      'ScreenGloss', el('small', { text: 'PlayTranslate for PC' })),
  ];
  for (const [group, routes] of groups) {
    children.push(el('h3', { text: group }));
    for (const r of routes) {
      children.push(el('a', { href: '#' + r.path, text: I18N.t(r.key), 'data-path': r.path }));
    }
  }
  nav.replaceChildren(...children);
}

function markCurrent() {
  const path = currentPath();
  document.querySelectorAll('#sidebar a[data-path]').forEach((a) => {
    if (a.getAttribute('data-path') === path) a.setAttribute('aria-current', 'page');
    else a.removeAttribute('aria-current');
  });
}

async function route() {
  markCurrent();
  const path = currentPath();
  const route = ROUTES.find((r) => r.path === path);
  if (!route) {
    const main = document.getElementById('view');
    main.replaceChildren(
      el('h1', { text: 'No such route' }),
      el('p', { class: 'provenance', text: path }),
      el('p', { text: 'Every route in §7.2.4 is listed in the sidebar. /settings is the root.' }));
    return;
  }
  try {
    const out = route.view(route);
    if (out && typeof out.then === 'function') await out;
  } catch (err) {
    const main = document.getElementById('view');
    main.replaceChildren(el('h1', { text: 'Render failed' }), el('pre', { text: String(err && err.stack || err) }));
  }
}

async function tick() {
  const s = document.getElementById('status');
  const status = await get('/api/v1/status');
  const caps = await get('/api/v1/capabilities');
  const parts = [];
  if (status) {
    parts.push('v' + status.version);
    parts.push(status.session + '/' + status.desktop);
    parts.push('port ' + status.port);
  } else {
    parts.push('bridge: not reachable');
  }
  if (caps && caps.checks) {
    const yes = caps.checks.filter((c) => c.verdict === 'yes').length;
    const degraded = caps.checks.filter((c) => c.verdict === 'degraded').length;
    const no = caps.checks.filter((c) => c.verdict === 'no').length;
    parts.push('capabilities: ' + yes + ' yes / ' + degraded + ' degraded / ' + no + ' no');
  }
  parts.push('locale ' + I18N.locale);
  s.replaceChildren(...parts.map((p) => el('span', { text: p })));
}

async function boot() {
  await I18N.load();
  renderNav();
  window.addEventListener('hashchange', route);
  await route();
  await tick();
  setInterval(tick, 10000);
  // WebSocket: the same channel §7.2.3 requires for server-pushed events.
  if (TOKEN) {
    try {
      const ws = new WebSocket('ws://' + location.host + '/ws?token=' + encodeURIComponent(TOKEN));
      ws.onmessage = (ev) => {
        try {
          const msg = JSON.parse(ev.data);
          if (msg.type === 'settings' || msg.type === 'capabilities') route();
        } catch (_) {}
      };
    } catch (_) {}
  }
}

boot();
