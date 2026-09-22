const DEFAULT_PAGE_LIMIT = 200;
const MAX_PAGE_LIMIT = 5000;
let offset = 0;
let lastTotal = 0;
let browsePath = "";
let browseParent = null;
let metaRange = { first: null, last: null };

const els = {
  meta: document.getElementById("meta"),
  parseWarning: document.getElementById("parse-warning"),
  parseWarningText: document.getElementById("parse-warning-text"),
  parseWarningSamples: document.getElementById("parse-warning-samples"),
  logDir: document.getElementById("log-dir"),
  logFormat: document.getElementById("log-format"),
  formatFileError: document.getElementById("format-file-error"),
  manageFormats: document.getElementById("manage-formats"),
  logFormatsDialog: document.getElementById("log-formats-dialog"),
  logFormatFile: document.getElementById("log-format-file"),
  logFormatList: document.getElementById("log-format-list"),
  logFormatEmpty: document.getElementById("log-format-empty"),
  logFormatSkipped: document.getElementById("log-format-skipped"),
  logFormatEditorTitle: document.getElementById("log-format-editor-title"),
  logFormatId: document.getElementById("log-format-id"),
  logFormatIdNote: document.getElementById("log-format-id-note"),
  logFormatName: document.getElementById("log-format-name"),
  logFormatPattern: document.getElementById("log-format-pattern"),
  logFormatTimestamp: document.getElementById("log-format-timestamp"),
  logFormatSample: document.getElementById("log-format-sample"),
  logFormatTry: document.getElementById("log-format-try"),
  logFormatSave: document.getElementById("log-format-save"),
  logFormatReset: document.getElementById("log-format-reset"),
  logFormatResult: document.getElementById("log-format-result"),
  browse: document.getElementById("browse"),
  loadDir: document.getElementById("load-dir"),
  fileList: document.getElementById("file-list"),
  level: document.getElementById("level"),
  logger: document.getElementById("logger"),
  thread: document.getElementById("thread"),
  message: document.getElementById("message"),
  sinceDate: document.getElementById("since-date"),
  sinceTime: document.getElementById("since-time"),
  untilDate: document.getElementById("until-date"),
  untilTime: document.getElementById("until-time"),
  rangeFirst1h: document.getElementById("range-first-1h"),
  rangeFirst24h: document.getElementById("range-first-24h"),
  rangeLast1h: document.getElementById("range-last-1h"),
  rangeLast24h: document.getElementById("range-last-24h"),
  rangeClear: document.getElementById("range-clear"),
  rangeHint: document.getElementById("range-hint"),
  grep: document.getElementById("grep"),
  source: document.getElementById("source"),
  pageLimit: document.getElementById("page-limit"),
  search: document.getElementById("search"),
  reset: document.getElementById("reset"),
  rows: document.getElementById("rows"),
  resultCount: document.getElementById("result-count"),
  // マッチ行のハイライト。並び順が色の優先順位（row-hl-1 → 3）になる
  highlights: [1, 2, 3].map((n) => document.getElementById("highlight-" + n)),
  fullPath: document.getElementById("full-path"),
  parseWarningDetails: document.getElementById("parse-warning-details"),
  filtersFields: document.getElementById("filters-fields"),
  filtersToggle: document.getElementById("filters-toggle"),
  filtersSummary: document.getElementById("filters-summary"),
  savedSearches: document.getElementById("saved-searches"),
  savedSearchesDialog: document.getElementById("saved-searches-dialog"),
  savedSearchesTitle: document.getElementById("saved-searches-title"),
  traceFields: document.getElementById("trace-fields"),
  savedSearchName: document.getElementById("saved-search-name"),
  savedSearchSave: document.getElementById("saved-search-save"),
  savedSearchList: document.getElementById("saved-search-list"),
  savedSearchEmpty: document.getElementById("saved-search-empty"),
  savedSearchSkipped: document.getElementById("saved-search-skipped"),
  savedSearchFile: document.getElementById("saved-search-file"),
  pageInfo: document.getElementById("page-info"),
  prev: document.getElementById("prev"),
  next: document.getElementById("next"),
  detail: document.getElementById("detail"),
  detailBody: document.getElementById("detail-body"),
  detailSearchAround1m: document.getElementById("detail-search-around-1m"),
  detailSearchAround5m: document.getElementById("detail-search-around-5m"),
  detailFilterSameContext: document.getElementById("detail-filter-same-context"),
  regexSamples: document.getElementById("regex-samples"),
  regexSamplesDialog: document.getElementById("regex-samples-dialog"),
  browseDialog: document.getElementById("browse-dialog"),
  browseCurrent: document.getElementById("browse-current"),
  browseList: document.getElementById("browse-list"),
  browseUp: document.getElementById("browse-up"),
  browseSelect: document.getElementById("browse-select"),
  loadingOverlay: document.getElementById("loading-overlay"),
  loadingText: document.getElementById("loading-text"),
  tabSearch: document.getElementById("tab-search"),
  tabTrace: document.getElementById("tab-trace"),
  panelSearch: document.getElementById("panel-search"),
  panelTrace: document.getElementById("panel-trace"),
  traceMode: document.getElementById("trace-mode"),
  traceId: document.getElementById("trace-id"),
  traceWindowSecs: document.getElementById("trace-window-secs"),
  traceWindowField: document.getElementById("trace-window-field"),
  traceMinutesField: document.getElementById("trace-minutes-field"),
  traceWindowNote: document.getElementById("trace-window-note"),
  traceContains: document.getElementById("trace-contains"),
  traceExcludes: document.getElementById("trace-excludes"),
  traceStart: document.getElementById("trace-start"),
  traceEnd: document.getElementById("trace-end"),
  traceMaxMinutes: document.getElementById("trace-max-minutes"),
  traceRun: document.getElementById("trace-run"),
  traceClear: document.getElementById("trace-clear"),
};

let loadingDepth = 0;
let backgroundLoading = false;

function syncLoadingOverlay() {
  const visible = loadingDepth > 0 || backgroundLoading;
  els.loadingOverlay.hidden = !visible;
  document.body.classList.toggle("is-loading", visible);
}

function pushLoading(message) {
  loadingDepth += 1;
  if (message) els.loadingText.textContent = message;
  syncLoadingOverlay();
}

function popLoading() {
  loadingDepth = Math.max(0, loadingDepth - 1);
  syncLoadingOverlay();
}

function setBackgroundLoading(loading, message) {
  backgroundLoading = loading;
  if (message) els.loadingText.textContent = message;
  syncLoadingOverlay();
}

/**
 * レベル名を色分け用のクラスへ。SLF4J / log4j 系に加え、java.util.logging の
 * レベル（Tomcat の catalina.out で出る）も同じ色に寄せる。
 * FATAL / SEVERE は ERROR、CONFIG は INFO、FINE / FINER は DEBUG、FINEST は TRACE 相当。
 */
function levelClass(level) {
  if (!level) return "";
  const upper = String(level).toUpperCase();
  if (upper === "ERROR" || upper === "FATAL" || upper === "SEVERE") return "level-error";
  if (upper === "WARN" || upper === "WARNING") return "level-warn";
  if (upper === "INFO" || upper === "CONFIG") return "level-info";
  if (upper === "DEBUG" || upper === "FINE" || upper === "FINER") return "level-debug";
  if (upper === "TRACE" || upper === "FINEST") return "level-trace";
  return "";
}

function pad2(n) {
  return String(n).padStart(2, "0");
}

function parseIsoParts(iso) {
  if (!iso) return null;
  const match = iso.match(/^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})/);
  if (!match) return null;
  return {
    y: Number(match[1]),
    mo: Number(match[2]),
    d: Number(match[3]),
    h: Number(match[4]),
    mi: Number(match[5]),
    s: Number(match[6]),
  };
}

/**
 * ログの時刻文字列を計算用の数値へ。タイムゾーン変換はせず、書かれている暦の値を
 * そのまま扱う（サーバ側の内部表現と同じ考え方）。対になる msToFields /
 * msToApiDatetime も getUTC* で読み戻すため、ブラウザのタイムゾーンに影響されない。
 */
function isoToMs(iso) {
  const p = parseIsoParts(iso);
  if (!p) return null;
  return Date.UTC(p.y, p.mo - 1, p.d, p.h, p.mi, p.s);
}

function msToFields(ms) {
  const d = new Date(ms);
  return {
    date:
      d.getUTCFullYear() +
      "-" +
      pad2(d.getUTCMonth() + 1) +
      "-" +
      pad2(d.getUTCDate()),
    time: pad2(d.getUTCHours()) + ":" + pad2(d.getUTCMinutes()),
  };
}

function formatRangeHint(iso) {
  const p = parseIsoParts(iso);
  if (!p) return iso;
  return `${p.y}-${pad2(p.mo)}-${pad2(p.d)} ${pad2(p.h)}:${pad2(p.mi)}`;
}

function setDatetimeFields(start, end) {
  els.sinceDate.value = start.date;
  els.sinceTime.value = start.time;
  els.untilDate.value = end.date;
  els.untilTime.value = end.time;
}

/** プログラムから期間を設定するとき、日付入力の min/max を広げる。 */
function widenDateInputBounds(startDate, endDate) {
  const minDate = startDate <= endDate ? startDate : endDate;
  const maxDate = startDate <= endDate ? endDate : startDate;
  if (!els.sinceDate.min || minDate < els.sinceDate.min) {
    els.sinceDate.min = minDate;
    els.untilDate.min = minDate;
  }
  if (!els.sinceDate.max || maxDate > els.sinceDate.max) {
    els.sinceDate.max = maxDate;
    els.untilDate.max = maxDate;
  }
}

function setExactQueryRange(sinceMs, untilMs) {
  const start = msToFields(sinceMs);
  const end = msToFields(untilMs);
  widenDateInputBounds(start.date, end.date);
  exactQueryRange = {
    since: msToApiDatetime(sinceMs),
    until: msToApiDatetime(untilMs),
  };
  setDatetimeFields(start, end);
}

function clearDatetimeFields() {
  els.sinceDate.value = "";
  els.sinceTime.value = "";
  els.untilDate.value = "";
  els.untilTime.value = "";
  clearExactQueryRange();
}

function getSinceParam() {
  if (!els.sinceDate.value) return null;
  // step=60 の time 入力は HH:mm を返すが、秒付きを返す実装に備えて桁を揃える。
  const time = (els.sinceTime.value || "00:00").slice(0, 5);
  return `${els.sinceDate.value} ${time}:00.000`;
}

function getUntilParam() {
  if (!els.untilDate.value) return null;
  const time = (els.untilTime.value || "23:59").slice(0, 5);
  // 時刻入力は分単位のため、その分の末尾（59.999 秒）まで含める
  return `${els.untilDate.value} ${time}:59.999`;
}

/** ISO 文字列（API meta）を since/until クエリ形式へ。 */
function isoToApiDatetime(iso) {
  return iso.replace("T", " ");
}

/** isoToMs が返す数値を API の日時文字列へ（ミリ秒まで）。 */
function msToApiDatetime(ms) {
  const t = new Date(ms);
  const y = t.getUTCFullYear();
  const mo = pad2(t.getUTCMonth() + 1);
  const d = pad2(t.getUTCDate());
  const h = pad2(t.getUTCHours());
  const mi = pad2(t.getUTCMinutes());
  const sec = pad2(t.getUTCSeconds());
  const milli = String(t.getUTCMilliseconds()).padStart(3, "0");
  return `${y}-${mo}-${d} ${h}:${mi}:${sec}.${milli}`;
}

/** クイック選択ボタン用。手入力フィールドより優先する正確な since/until。 */
let exactQueryRange = null;

/** 直近の追跡で実際に使った前後の秒数（表示用。入力欄を後から変えてもずれないように持つ）。 */
let lastTraceWindowSecs = "";

/** 詳細ダイアログ表示中のログ時刻（ISO）。 */
let detailTimestamp = null;
/** 詳細ダイアログ表示中のログファイル / スレッド。 */
let detailContext = null;

function escapeRegex(s) {
  return String(s).replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function clearExactQueryRange() {
  exactQueryRange = null;
}

function updateRangeUi() {
  const ready = Boolean(metaRange.first && metaRange.last);
  els.rangeFirst1h.disabled = !ready;
  els.rangeFirst24h.disabled = !ready;
  els.rangeLast1h.disabled = !ready;
  els.rangeLast24h.disabled = !ready;
  if (ready) {
    els.sinceDate.min = msToFields(isoToMs(metaRange.first)).date;
    els.sinceDate.max = msToFields(isoToMs(metaRange.last)).date;
    els.untilDate.min = els.sinceDate.min;
    els.untilDate.max = els.sinceDate.max;
    els.rangeHint.textContent =
      "ログの範囲: " +
      formatRangeHint(metaRange.first) +
      " 〜 " +
      formatRangeHint(metaRange.last);
  } else {
    els.sinceDate.min = "";
    els.sinceDate.max = "";
    els.untilDate.min = "";
    els.untilDate.max = "";
    els.rangeHint.textContent = "ログ読み込み後に期間ボタンが使えます";
  }
}

function applyFirstHours(hours) {
  if (!metaRange.first || !metaRange.last) return;
  const startMs = isoToMs(metaRange.first);
  const endMs = isoToMs(metaRange.last);
  if (startMs == null || endMs == null) return;
  const untilMs = Math.min(endMs, startMs + hours * 3600000);
  exactQueryRange = {
    since: isoToApiDatetime(metaRange.first),
    until: msToApiDatetime(untilMs),
  };
  setDatetimeFields(msToFields(startMs), msToFields(untilMs));
  offset = 0;
  loadLogs();
}

function applyLastHours(hours) {
  if (!metaRange.first || !metaRange.last) return;
  const endMs = isoToMs(metaRange.last);
  const startMs = isoToMs(metaRange.first);
  if (endMs == null || startMs == null) return;
  const sinceMs = Math.max(startMs, endMs - hours * 3600000);
  exactQueryRange = {
    since: msToApiDatetime(sinceMs),
    until: isoToApiDatetime(metaRange.last),
  };
  setDatetimeFields(msToFields(sinceMs), msToFields(endMs));
  offset = 0;
  loadLogs();
}

function applyAroundMinutes(isoTimestamp, minutes) {
  const centerMs = isoToMs(isoTimestamp);
  if (centerMs == null) return false;
  const delta = minutes * 60 * 1000;
  setExactQueryRange(centerMs - delta, centerMs + delta);
  offset = 0;
  loadLogs();
  return true;
}

function getPageLimit() {
  const raw = Number(els.pageLimit.value);
  if (!Number.isFinite(raw) || raw < 1) return DEFAULT_PAGE_LIMIT;
  return Math.min(Math.floor(raw), MAX_PAGE_LIMIT);
}

function buildQuery(pageLimit) {
  const params = new URLSearchParams();
  params.set("limit", String(pageLimit));
  params.set("offset", String(offset));
  for (const [key, el] of [
    ["level", els.level],
    ["logger", els.logger],
    ["thread", els.thread],
    ["message", els.message],
    ["grep", els.grep],
    ["source", els.source],
  ]) {
    if (el.value.trim()) params.set(key, el.value.trim());
  }
  if (exactQueryRange) {
    params.set("since", exactQueryRange.since);
    params.set("until", exactQueryRange.until);
  } else {
    if (els.sinceDate.value) params.set("since", getSinceParam());
    if (els.untilDate.value) params.set("until", getUntilParam());
  }
  return params;
}

let loadPollTimer = null;
let lastPageItems = [];
/** 現在表示中の結果を取得したときの表示件数。入力欄を変えてもページャがずれないよう保持する。 */
let currentPageLimit = DEFAULT_PAGE_LIMIT;
/** 検索リクエストの通し番号。古いレスポンスで新しい結果を上書きしないために使う。 */
let logsRequestSeq = 0;
let savedSearchListSeq = 0;

/** 各ハイライト欄の検索語（小文字化済み）。空欄は "" のまま位置を保つ。 */
function getHighlightNeedles() {
  return els.highlights.map((el) => el.value.trim().toLowerCase());
}

/** ハイライト判定の対象文字列。grep と同様にスタックトレース含む raw を優先する（検索結果は変えない）。 */
function highlightHaystack(item) {
  const text = item.raw || [
    item.timestamp,
    item.level,
    item.logger,
    item.thread,
    item.message,
    item.source,
    item.line_no,
  ].filter((v) => v != null && v !== "").join(" ");
  return text.toLowerCase();
}

/**
 * 行を色分けする。複数の欄に一致した行は番号の小さい欄の色にする
 * （背景色は 1 色しか出せないため。並びで優先順位が分かるようにしている）。
 */
function applyRowHighlights() {
  const needles = getHighlightNeedles();
  const active = needles.some((n) => n);
  const rows = els.rows.querySelectorAll("tr");
  for (let i = 0; i < rows.length; i += 1) {
    const item = lastPageItems[i];
    let hit = -1;
    if (active && item) {
      const haystack = highlightHaystack(item);
      hit = needles.findIndex((n) => n && haystack.includes(n));
    }
    for (let k = 0; k < needles.length; k += 1) {
      rows[i].classList.toggle("row-hl-" + (k + 1), k === hit);
    }
  }
}

function setLoadingUi(loading) {
  els.search.disabled = loading;
  els.reset.disabled = loading;
  els.loadDir.disabled = loading;
  els.browse.disabled = loading;
  // 追跡もインデックスが揃ってからでないと実行できない（押しても API が弾く）ので、
  // 検索ボタンと同じように読み込み中は押せなくしておく
  els.traceRun.disabled = loading;
  els.traceClear.disabled = loading;
}

function clearLoadPoll() {
  if (loadPollTimer) {
    clearTimeout(loadPollTimer);
    loadPollTimer = null;
  }
}

function scheduleLoadPoll() {
  if (loadPollTimer) return;
  loadPollTimer = setTimeout(async () => {
    loadPollTimer = null;
    const data = await fetchMeta();
    updateMeta(data);
    if (data.loading) {
      scheduleLoadPoll();
      return;
    }
    offset = 0;
    await loadLogs();
  }, 1000);
}

async function fetchMeta() {
  const res = await fetch("/api/meta");
  return res.json();
}

async function loadMeta(options = {}) {
  if (!options.silent) pushLoading("情報を取得中...");
  try {
    const data = await fetchMeta();
    updateMeta(data);
    return data;
  } finally {
    if (!options.silent) popLoading();
  }
}

/**
 * 書式のプルダウンをサーバの一覧で作り直す。
 *
 * 組み込み書式に加えて、利用者が aplv-log-formats.txt に書いた書式も並ぶ。
 * ファイルを直して読み込み直せば、サーバを起動し直さずに選べるようになる。
 * 選択中の値は保てるときだけ保つ（消された書式を選んだままにしない）。
 */
function syncLogFormatOptions(formats) {
  if (!Array.isArray(formats) || formats.length === 0) return;
  const signature = formats.map((f) => `${f.id}\t${f.name}`).join("\n");
  if (els.logFormat.dataset.signature === signature) return;
  els.logFormat.dataset.signature = signature;

  const previous = els.logFormat.value;
  const auto = els.logFormat.querySelector('option[value="auto"]');
  const autoText = auto ? auto.textContent : "書式: 自動判定";
  els.logFormat.innerHTML = "";
  const autoOption = document.createElement("option");
  autoOption.value = "auto";
  autoOption.textContent = autoText;
  els.logFormat.appendChild(autoOption);
  for (const f of formats) {
    if (typeof f.id !== "string" || typeof f.name !== "string") continue;
    const option = document.createElement("option");
    option.value = f.id;
    // 利用者が足した書式だと分かるようにする（組み込みと見分けがつかないと、
    // 書式ファイルを消したときに選択肢が消えた理由が分からない）。
    option.textContent = f.custom ? `書式: ${f.name}（利用者定義）` : `書式: ${f.name}`;
    els.logFormat.appendChild(option);
  }
  if (previous && els.logFormat.querySelector(`option[value="${cssEscape(previous)}"]`)) {
    els.logFormat.value = previous;
  }
}

/** セレクタに値を埋めるときのエスケープ（書式 id は英数字とハイフンだが、念のため）。 */
function cssEscape(value) {
  if (window.CSS && typeof window.CSS.escape === "function") {
    return window.CSS.escape(value);
  }
  return value.replace(/["\\]/g, "\\$&");
}

/**
 * 実際に使われた書式をセレクトへ反映する。
 *
 * <p>自動判定のときは「自動判定」の表示を保ったまま、判定結果を option の文言に添える。
 * 利用者が明示指定していた場合はその選択をそのまま残す。
 */
function syncLogFormatSelect(data) {
  syncLogFormatOptions(data.log_formats);
  // 書式ファイルを読めていないことは黙って無視しない。組み込み書式では動くので、
  // 気づかないまま「自分で足した書式が出てこない」と悩むことになる。
  if (els.formatFileError) {
    els.formatFileError.hidden = !data.log_formats_error;
    els.formatFileError.textContent = data.log_formats_error
      ? `書式ファイルを読めません: ${data.log_formats_error}`
      : "";
  }
  const auto = els.logFormat.querySelector('option[value="auto"]');
  if (!auto) return;
  // 判定結果を添えるのは取り込みが終わったときだけ。log_format_name は取り込み前も
  // 初期値（既定書式）が入っているので、条件を付けないと「まだ判定していないのに
  // 既定書式が選ばれた」ように見える。
  if (data.log_format_auto && data.log_format_name && data.load_status === "ready") {
    // 利用者定義が選ばれたことは必ず出す。自動判定は一致した行数で決めるので、
    // 広い正規表現の書式は組み込みを越えて選ばれうる。ここが黙っていると、
    // 自分が登録した書式で読まれていることに気づけない。
    auto.textContent = data.log_format_custom
      ? `書式: 自動判定（${data.log_format_name}・利用者定義）`
      : `書式: 自動判定（${data.log_format_name}）`;
  } else {
    auto.textContent = "書式: 自動判定";
  }
  if (!data.log_format_auto && data.log_format) {
    els.logFormat.value = data.log_format;
  }
}

function updateParseWarning(data) {
  const skipped = data.skipped_lines || 0;
  if (skipped <= 0) {
    els.parseWarning.hidden = true;
    els.parseWarningText.textContent = "";
    els.parseWarningSamples.innerHTML = "";
    return;
  }
  els.parseWarning.hidden = false;
  els.parseWarningDetails.open = false;
  // 利用者定義の書式で外れたときに「Java アプリログ形式として認識できません」と出すと、
  // 直す先が自分の書いた正規表現だと分からない。使った書式で言い分ける。
  els.parseWarningText.textContent = data.log_format_custom
    ? `${skipped.toLocaleString()} 行が、書式「${data.log_format_name}」の正規表現に` +
      "一致しませんでした（先頭の孤立行など。継続行として扱った行は除く）。" +
      "下の「ファイル名:行番号」の行をログから取り出し、" +
      "「書式の管理」の「この行で試す」に貼って確かめてください" +
      "（下に出る例は長いと末尾を切り詰めるので、そのままでは一致しません）。"
    : `${skipped.toLocaleString()} 行を Java アプリログ形式として認識できませんでした` +
      "（先頭の孤立行など。スタックトレース等の継続行は除く）。";
  els.parseWarningSamples.innerHTML = "";
  const samples = data.skipped_samples || [];
  for (const s of samples) {
    const li = document.createElement("li");
    li.textContent = `${s.source}:${s.line_no} — ${s.preview}`;
    li.title = s.preview;
    els.parseWarningSamples.appendChild(li);
  }
  if (skipped > samples.length) {
    const li = document.createElement("li");
    li.textContent = `…他 ${(skipped - samples.length).toLocaleString()} 行`;
    els.parseWarningSamples.appendChild(li);
  }
}

function updateMeta(data) {
  if (data.directory) {
    els.logDir.value = data.directory;
  }
  // 書式のプルダウンは、この先の早期 return より前に作り直す。ディレクトリ未選択・
  // 読み込み中・読み込み失敗のときも、登録した書式を選べるようにしておかないと、
  // 「登録したのに 1 回目の読み込みで指定できない」という詰まり方をする。
  syncLogFormatSelect(data);
  if (data.files.length === 0) {
    metaRange = { first: null, last: null };
    els.meta.textContent = "ログファイル未読み込み — ディレクトリを選択してください";
    els.fileList.textContent = "";
    els.fileList.title = "";
    updateParseWarning({});
    setBackgroundLoading(false);
    setLoadingUi(false);
    clearLoadPoll();
    updateRangeUi();
    return;
  }
  if (data.load_error) {
    metaRange = { first: null, last: null };
    els.meta.textContent = `読み込みエラー: ${data.load_error}`;
    els.fileList.textContent = data.files.join(" | ");
    els.fileList.title = data.files.join("\n");
    updateParseWarning({});
    setBackgroundLoading(false);
    setLoadingUi(false);
    clearLoadPoll();
    updateRangeUi();
    return;
  }
  if (data.loading) {
    metaRange = { first: null, last: null };
    const message = `ログを読み込み中... ${data.load_progress.toLocaleString()} 行`;
    els.meta.textContent = `${message} / ファイル ${data.files.length} 件`;
    els.fileList.textContent = data.files.join(" | ");
    els.fileList.title = data.files.join("\n");
    updateParseWarning({});
    setBackgroundLoading(true, message);
    setLoadingUi(true);
    scheduleLoadPoll();
    updateRangeUi();
    return;
  }
  metaRange = { first: data.first, last: data.last };
  els.meta.textContent =
    `${data.total.toLocaleString()} 行 / ファイル ${data.files.length} 件` +
    (data.first ? ` / ${data.first} 〜 ${data.last}` : "") +
    (data.log_format_name ? ` / 書式: ${data.log_format_name}` : "");
  els.fileList.textContent = data.files.join(" | ");
  els.fileList.title = data.files.join("\n");
  updateParseWarning(data);
  setBackgroundLoading(false);
  setLoadingUi(false);
  clearLoadPoll();
  updateRangeUi();
}

async function loadDirectory() {
  const directory = els.logDir.value.trim();
  if (!directory) {
    alert("ログディレクトリを入力してください。");
    return;
  }
  pushLoading("ディレクトリを読み込み中...");
  try {
    const res = await fetch("/api/load", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ directory, format: els.logFormat.value }),
    });
    const data = await res.json();
    if (!res.ok) {
      alert(data.error || "読み込みに失敗しました。");
      return;
    }
    offset = 0;
    updateMeta(data);
    if (!data.loading) {
      await loadLogs();
    }
  } finally {
    popLoading();
  }
}

async function openBrowseDialog() {
  await refreshBrowseList(els.logDir.value.trim());
  els.browseDialog.showModal();
}

/**
 * ディレクトリ一覧を取得して描画する。
 *
 * 取得に失敗した場合は現在位置（browsePath / browseParent）を変更しない。
 * 遷移先を先に代入すると、失敗時に画面表示と現在位置が食い違い、
 * 「このディレクトリを選択」で存在しないパスを入力欄へ書き戻してしまうため。
 *
 * @param nextPath 遷移先。省略時は現在位置を読み直す
 */
async function refreshBrowseList(nextPath) {
  const target = nextPath !== undefined ? nextPath : browsePath;
  const params = new URLSearchParams();
  if (target) params.set("path", target);
  const res = await fetch("/api/browse?" + params);
  const data = await res.json();
  if (!res.ok) {
    alert(data.error || "ディレクトリ一覧の取得に失敗しました。");
    return;
  }
  browsePath = data.current;
  browseParent = data.parent || null;
  els.browseCurrent.textContent = data.current;
  els.browseUp.disabled = !browseParent;
  els.browseList.innerHTML = "";
  for (const dir of data.directories) {
    const li = document.createElement("li");
    const btn = document.createElement("button");
    btn.type = "button";
    btn.textContent = dir.split(/[/\\]/).pop() || dir;
    btn.title = dir;
    btn.addEventListener("click", () => refreshBrowseList(dir));
    li.appendChild(btn);
    els.browseList.appendChild(li);
  }
}

function displayLoggerName(logger) {
  if (logger == null || logger === "") return logger;
  const s = String(logger);
  const dot = s.lastIndexOf(".");
  return dot >= 0 ? s.slice(dot + 1) : s;
}

function formatSourceLabel(source) {
  if (!source) return "-";
  const parts = source.split(/[/\\]/).filter(Boolean);
  if (parts.length <= 1) return parts[0] || source;
  const sep = source.includes("\\") ? "\\" : "/";
  return parts.slice(-2).join(sep);
}

/**
 * ログファイル列の表示文字列。既定は末尾 2 要素だけの短縮表示で、
 * 「フルパス表示」を入れると絶対パスをそのまま出す。
 */
function sourceCellText(item) {
  const path =
    els.fullPath.checked && item.source ? item.source : formatSourceLabel(item.source);
  return path + ":" + item.line_no;
}

/**
 * ログファイル列だけを描き替える。検索をやり直さずに切り替えたいので
 * 行は作り直さない。フルパスのときは列幅の上限を外す（body のクラスで CSS 側を切り替え）。
 */
function applySourceDisplay() {
  document.body.classList.toggle("show-full-path", els.fullPath.checked);
  const rows = els.rows.querySelectorAll("tr");
  for (let i = 0; i < rows.length; i += 1) {
    const item = lastPageItems[i];
    const td = rows[i].querySelector("td.source");
    if (item && td) td.textContent = sourceCellText(item);
  }
}

/**
 * 検索条件の保存・呼び出し（サーバ側。ツールホーム直下の JSON）。
 * ハイライトやフルパス表示など表示設定は対象外。いま選んでいるタブの input/select を
 * id -> value のマップとして保存し、適用時は同じ id の要素へ書き戻す。
 * どちらのタブで保存したかを mode に持ち、適用時はそのタブへ切り替えて欄を埋める
 * （mode を持たない古い保存データは検索タブのものとして扱う）。
 *
 * 条件値は正規表現を含みうるが、ここでは文字列として読み書きするだけ。
 * JSON.parse した値を正規表現オブジェクトへはせず、入力欄へ戻すときにだけ使う。
 */
async function loadSavedSearches() {
  const res = await fetch("/api/saved-searches", { cache: "no-store" });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    throw new Error(data.error || "検索条件の読み込みに失敗しました。");
  }
  return {
    items: Array.isArray(data.items) ? data.items : [],
    skipped: typeof data.skipped === "number" ? data.skipped : 0,
    overflow: data.overflow === true,
    file: typeof data.file === "string" ? data.file : "",
  };
}

/** いま選んでいるタブ（"search" / "trace"）。 */
function activeTab() {
  return els.panelTrace.hidden ? "search" : "trace";
}

/** タブごとの入力欄・折りたたみ対象・保存した条件の呼び名。 */
const TAB_UI = {
  search: { panel: () => els.panelSearch, fields: () => els.filtersFields, label: "検索" },
  trace: { panel: () => els.panelTrace, fields: () => els.traceFields, label: "追跡" },
};

function collectFilterFields() {
  const fields = {};
  for (const el of TAB_UI[activeTab()].panel().querySelectorAll("input[id], select[id]")) {
    fields[el.id] = el.value;
  }
  return fields;
}

/**
 * 読み飛ばした項目の案内。上限超過のときは保存・削除ごと断られるので、
 * 「次の保存で消える」ではなくファイルを減らすよう促す。
 */
function skippedMessage(loaded) {
  if (loaded.overflow) {
    return `保存ファイルの件数が上限を超えています。読み込めていない項目が ${loaded.skipped} 件あり、`
      + "消えないよう保存と削除を止めています。ファイルを直接編集して減らしてください。";
  }
  return `読めなかった項目が ${loaded.skipped} 件あります（次の保存でファイルから消えます）。`;
}

/** 入力欄の既定値（HTML に書いた値）。保存に無い項目はここへ戻す。 */
function defaultFieldValue(el) {
  if (el.tagName === "SELECT") {
    const selected = el.querySelector("option[selected]");
    return selected ? selected.value : (el.options[0] ? el.options[0].value : "");
  }
  return el.defaultValue;
}

function applyFilterFields(fields) {
  const panel = TAB_UI[activeTab()].panel();
  // 保存に含まれない項目に前の入力が残ると、条件が混ざって分かりにくい。
  // 「適用 = 保存したときの状態を再現」に揃えるため、いったん既定値へ戻す。
  for (const el of panel.querySelectorAll("input[id], select[id]")) {
    el.value = defaultFieldValue(el);
  }
  for (const [id, value] of Object.entries(fields || {})) {
    // 正規表現を含む値は文字列のまま入力欄へ戻す。id もリテラルとして扱い、
    // セレクタ結合や他パネルの要素（ログディレクトリ等）へは書かない。
    if (typeof id !== "string" || typeof value !== "string") continue;
    const el = document.getElementById(id);
    if (!el || !panel.contains(el)) continue;
    if (el.tagName !== "INPUT" && el.tagName !== "SELECT") continue;
    el.value = value;
  }
  // クイック範囲ボタンが設定する秒未満の精度は保存対象外。日時欄の値（分単位）で
  // 検索すれば同じ分の範囲になるため、古い厳密範囲は捨てる（秒精度までは再現しない）。
  clearExactQueryRange();
  updateRangeUi();
  // 判定方法も書き戻されるので、入力欄の出し分けを合わせ直す
  syncTraceMode();
  updateFiltersSummary();
}

function formatSavedAt(iso) {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "";
  const p2 = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p2(d.getMonth() + 1)}-${p2(d.getDate())} ${p2(d.getHours())}:${p2(d.getMinutes())}`;
}

async function renderSavedSearchList() {
  const seq = (savedSearchListSeq += 1);
  els.savedSearchList.innerHTML = "";
  els.savedSearchEmpty.hidden = false;
  els.savedSearchEmpty.textContent = "読み込み中...";
  if (els.savedSearchSkipped) els.savedSearchSkipped.hidden = true;
  let loaded;
  try {
    loaded = await loadSavedSearches();
  } catch (e) {
    if (seq !== savedSearchListSeq) return;
    els.savedSearchEmpty.textContent = e.message || "検索条件の読み込みに失敗しました。";
    return;
  }
  if (seq !== savedSearchListSeq) return;
  if (els.savedSearchFile) {
    // パスは textContent で入れる（HTML として解釈させない）
    els.savedSearchFile.textContent = loaded.file || "(取得できませんでした)";
    els.savedSearchFile.title = loaded.file || "";
  }
  const list = loaded.items.slice().sort((a, b) => (a.savedAt < b.savedAt ? 1 : -1));
  els.savedSearchEmpty.textContent = "保存した検索条件はまだありません。";
  els.savedSearchEmpty.hidden = list.length > 0;
  if (els.savedSearchSkipped) {
    els.savedSearchSkipped.hidden = loaded.skipped <= 0;
    els.savedSearchSkipped.textContent =
      loaded.skipped > 0
        ? skippedMessage(loaded)
        : "";
  }
  for (const saved of list) {
    const li = document.createElement("li");
    li.className = "saved-search-item";

    const info = document.createElement("div");
    info.className = "saved-search-info";
    const name = document.createElement("span");
    name.className = "saved-search-name";
    name.textContent = saved.name;
    name.title = saved.name;
    const mode = document.createElement("span");
    mode.className = "saved-search-mode";
    mode.textContent = saved.mode === "trace" ? "追跡" : "検索";
    const date = document.createElement("span");
    date.className = "saved-search-date";
    date.textContent = formatSavedAt(saved.savedAt);
    const nameRow = document.createElement("div");
    nameRow.className = "saved-search-name-row";
    nameRow.appendChild(mode);
    nameRow.appendChild(name);
    info.appendChild(nameRow);
    info.appendChild(date);

    const buttons = document.createElement("div");
    buttons.className = "saved-search-buttons";
    const applyBtn = document.createElement("button");
    applyBtn.type = "button";
    applyBtn.textContent = "適用";
    // 条件を入れるだけにする（実行は利用者が「検索」「追跡」を押したとき）。
    // 重いログでは、呼び出しただけで走るほうが困るため。
    applyBtn.addEventListener("click", () => {
      const mode = saved.mode === "trace" ? "trace" : "search";
      setActiveTab(mode);
      applyFilterFields(saved.fields);
      els.savedSearchesDialog.close();
      // 欄を入れるだけなので、表には前の条件の結果が残る。そのまま「次へ」を押すと
      // 新しい条件の 2 ページ目から読んでしまうため、ページャは止めておく。
      // 「検索」を押した時点で offset ごと組み直す。
      offset = 0;
      els.prev.disabled = true;
      els.next.disabled = true;
    });
    const deleteBtn = document.createElement("button");
    deleteBtn.type = "button";
    deleteBtn.className = "saved-search-delete";
    deleteBtn.textContent = "削除";
    deleteBtn.addEventListener("click", async () => {
      if (!confirm(`「${saved.name}」を削除しますか？`)) return;
      try {
        const params = new URLSearchParams();
        params.set("id", saved.id);
        const res = await fetch("/api/saved-searches?" + params, { method: "DELETE" });
        const data = await res.json().catch(() => ({}));
        if (!res.ok) {
          alert(data.error || "削除に失敗しました。");
          return;
        }
        await renderSavedSearchList();
      } catch (e) {
        alert(e.message || "削除に失敗しました。");
      }
    });
    buttons.appendChild(applyBtn);
    buttons.appendChild(deleteBtn);

    li.appendChild(info);
    li.appendChild(buttons);
    els.savedSearchList.appendChild(li);
  }
}

/** 現在の検索条件欄の内容に名前を付けて保存する。同名があれば確認のうえ上書きする。 */
async function saveCurrentSearch() {
  const name = els.savedSearchName.value.trim();
  if (!name) {
    alert("名前を入力してください。");
    return;
  }
  const mode = activeTab();
  try {
    const loaded = await loadSavedSearches();
    // 同じ名前でも、検索と追跡は別の条件として保存する
    const existing = loaded.items.find(
      (s) => s.name === name && (s.mode === "trace" ? "trace" : "search") === mode
    );
    if (existing && !confirm(`「${name}」は既に保存されています。上書きしますか？`)) return;
    const res = await fetch("/api/saved-searches", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ name, mode, fields: collectFilterFields() }),
    });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) {
      alert(data.error || "検索条件の保存に失敗しました。");
      return;
    }
    els.savedSearchName.value = "";
    await renderSavedSearchList();
  } catch (e) {
    alert(e.message || "検索条件の保存に失敗しました。");
  }
}

function addCell(tr, content, options = {}) {
  const td = document.createElement("td");
  const text = content == null || content === "" ? "-" : String(content);
  td.textContent = text;
  if (options.className) td.className = options.className;
  if (options.title) td.title = options.title;
  tr.appendChild(td);
  return td;
}

/** 一覧の 1 行を組み立てる。クリックで詳細ダイアログを開く。 */
function buildLogRow(item) {
  const tr = document.createElement("tr");

  addCell(tr, item.timestamp);
  addCell(tr, item.level, { className: levelClass(item.level) });
  addCell(tr, displayLoggerName(item.logger), {
    className: "logger",
    title: item.logger,
  });
  addCell(tr, item.thread, { title: item.thread });
  addCell(tr, item.message, { className: "message", title: item.message });
  addCell(tr, sourceCellText(item), {
    className: "source",
    title: item.source + ":" + item.line_no,
  });

  tr.addEventListener("click", async () => {
    pushLoading("詳細を取得中...");
    try {
      const detailRes = await fetch(
        "/api/logs/detail?" +
          new URLSearchParams({
            source: item.source,
            line_no: String(item.line_no),
            timestamp: item.timestamp,
          })
      );
      const detail = await detailRes.json();
      if (detail.loading) {
        return;
      }
      if (!detailRes.ok) {
        alert(detail.error || "詳細の取得に失敗しました。");
        return;
      }
      els.detailBody.textContent =
        "ログファイル: " + detail.source + "\n" +
        "行番号: " + detail.line_no + "\n" +
        "時刻: " + detail.timestamp + "\n" +
        "Level: " + detail.level + "\n" +
        "Logger: " + detail.logger + "\n" +
        "Thread: " + detail.thread + "\n\n" +
        detail.raw;
      detailTimestamp = detail.timestamp;
      detailContext = { source: detail.source || "", thread: detail.thread || "" };
      els.detail.showModal();
    } finally {
      popLoading();
    }
  });
  return tr;
}

async function loadLogs() {
  // 検索を実行する経路（リセット・詳細ダイアログの絞り込み・統計からの絞り込み）は
  // すべてここを通る。呼び出し側を数え上げると漏れるので、件数表示の更新は
  // この 1 箇所に集約する。保存した条件の適用だけは検索を走らせないため、
  // applyFilterFields 側で同じ更新を行う。
  updateFiltersSummary();
  const pageLimit = getPageLimit();
  const seq = (logsRequestSeq += 1);
  pushLoading("ログを検索中...");
  try {
    const res = await fetch("/api/logs?" + buildQuery(pageLimit));
    const data = await res.json();
    if (seq !== logsRequestSeq) return; // より新しい検索が始まっているので破棄
    if (data.loading) {
      const message = `ログを読み込み中... ${data.load_progress.toLocaleString()} 行`;
      els.resultCount.textContent = message;
      els.pageInfo.textContent = "-";
      els.rows.innerHTML = "";
      lastPageItems = [];
      els.prev.disabled = true;
      els.next.disabled = true;
      setBackgroundLoading(true, message);
      setLoadingUi(true);
      return;
    }
    setBackgroundLoading(false);
    setLoadingUi(false);
    if (!res.ok) {
      alert(data.error || "取得に失敗しました。");
      return;
    }
    lastTotal = data.total;
    currentPageLimit = pageLimit;
    els.resultCount.textContent = `${data.total.toLocaleString()} 件ヒット`;
    const page = Math.floor(offset / pageLimit) + 1;
    const pages = Math.max(1, Math.ceil(data.total / pageLimit));
    els.pageInfo.textContent = `${page} / ${pages}`;
    els.prev.disabled = offset <= 0;
    els.next.disabled = offset + pageLimit >= data.total;

    els.rows.innerHTML = "";
    lastPageItems = data.items;
    for (const item of data.items) {
      els.rows.appendChild(buildLogRow(item));
    }
    applyRowHighlights();
  } finally {
    popLoading();
  }
}

/**
 * セッション追跡。検索条件とは別枠の機能で、結果は一覧と同じ表にリクエストごとの
 * 見出し行を挟んで描く。ページングはしない（件数の上限はサーバ側で持つ）。
 * はじまり・おわり・最大所要時間はアプリごとに決まった値を使い回すので、ブラウザに覚えておく。
 * セッション ID は調査のたびに変わるので保存しない。
 */
const TRACE_SETTINGS_KEY = "aplv.sessionTrace";

function loadTraceSettings() {
  try {
    const saved = JSON.parse(localStorage.getItem(TRACE_SETTINGS_KEY) || "null");
    if (!saved) return;
    if (typeof saved.start === "string") els.traceStart.value = saved.start;
    if (typeof saved.end === "string") els.traceEnd.value = saved.end;
    if (saved.maxMinutes) els.traceMaxMinutes.value = String(saved.maxMinutes);
    if (typeof saved.contains === "string") els.traceContains.value = saved.contains;
    if (typeof saved.excludes === "string") els.traceExcludes.value = saved.excludes;
    if (saved.mode === "window" || saved.mode === "boundary") els.traceMode.value = saved.mode;
    if (saved.windowSecs) els.traceWindowSecs.value = String(saved.windowSecs);
    syncTraceMode();
  } catch (e) {
    // 読めなくても既定値のまま使える
  }
}

function saveTraceSettings() {
  try {
    localStorage.setItem(
      TRACE_SETTINGS_KEY,
      JSON.stringify({
        start: els.traceStart.value,
        end: els.traceEnd.value,
        maxMinutes: els.traceMaxMinutes.value,
        contains: els.traceContains.value,
        excludes: els.traceExcludes.value,
        mode: els.traceMode.value,
        windowSecs: els.traceWindowSecs.value,
      })
    );
  } catch (e) {
    // 保存できなくても追跡自体は続ける
  }
}

/** 見出し行に付ける状態の表示。判定が確かでないものは警告色にする。 */
function traceBadges(req) {
  const badges = [];
  if (req.end_reason === "standalone") {
    badges.push({ text: "単独の行（どのリクエストの範囲にも入らない）" });
    return badges;
  }
  if (req.end_reason === "window") {
    // 時間だけで区切ったので、はじまり・おわりの確からしさは示せない
    badges.push({ text: `前後 ${lastTraceWindowSecs || "n"} 秒で区切り` });
    if (req.truncated) badges.push({ text: "行数の上限で打ち切り" });
    return badges;
  }
  if (!req.start_found) badges.push({ text: "はじまり不明" });
  if (req.end_reason === "end") {
    if (req.start_found) badges.push({ text: "はじまり〜おわり", ok: true });
  } else if (req.end_reason === "next_start") {
    badges.push({ text: "おわり未検出（次のはじまりの直前まで）" });
  } else {
    badges.push({ text: "おわり未検出（最大所要時間・ファイル末尾まで）" });
  }
  if (req.truncated) badges.push({ text: "行数の上限で打ち切り" });
  return badges;
}

function buildTraceGroupRow(req, index) {
  const tr = document.createElement("tr");
  tr.className = "trace-group";
  const td = document.createElement("td");
  td.colSpan = 6;
  const first = req.items[0];
  const last = req.items[req.items.length - 1];
  const title = document.createElement("span");
  title.textContent = `#${index + 1}  ${req.thread || "(スレッド名なし)"}`;
  td.appendChild(title);
  const meta = document.createElement("span");
  meta.className = "trace-group-meta";
  const range = first === last ? first.timestamp : `${first.timestamp} 〜 ${last.timestamp}`;
  meta.textContent = `${range} / ${req.items.length} 行 / ${formatSourceLabel(req.source)}`;
  meta.title = req.source;
  td.appendChild(meta);
  for (const b of traceBadges(req)) {
    const badge = document.createElement("span");
    badge.className = "trace-badge" + (b.ok ? " ok" : "");
    badge.textContent = b.text;
    td.appendChild(badge);
  }
  tr.appendChild(td);
  return tr;
}

/**
 * 判定方法に合わせて入力欄を出し分ける。
 * 時間窓モードでは、はじまり・おわり・最大所要時間は使わない。
 */
function syncTraceMode() {
  const windowMode = els.traceMode.value === "window";
  els.traceStart.closest("label").hidden = windowMode;
  els.traceEnd.closest("label").hidden = windowMode;
  els.traceMinutesField.hidden = windowMode;
  els.traceWindowField.hidden = !windowMode;
  els.traceWindowNote.hidden = !windowMode;
}

async function runSessionTrace() {
  const id = els.traceId.value.trim();
  const start = els.traceStart.value.trim();
  const end = els.traceEnd.value.trim();
  const windowMode = els.traceMode.value === "window";
  if (!id) {
    alert("セッション ID を入力してください。");
    return;
  }
  if (!windowMode && (!start || !end)) {
    alert("リクエストのはじまりとおわりを入力してください。");
    return;
  }
  if (windowMode && !els.traceWindowSecs.value.trim()) {
    alert("前後の秒数を入力してください。");
    return;
  }
  saveTraceSettings();
  const params = new URLSearchParams({ id });
  params.set("mode", windowMode ? "window" : "boundary");
  if (windowMode) {
    params.set("window_secs", els.traceWindowSecs.value.trim());
    lastTraceWindowSecs = els.traceWindowSecs.value.trim();
  } else {
    params.set("start", start);
    params.set("end", end);
    if (els.traceMaxMinutes.value.trim()) {
      params.set("max_minutes", els.traceMaxMinutes.value.trim());
    }
  }
  if (els.traceContains.value.trim()) params.set("contains", els.traceContains.value.trim());
  if (els.traceExcludes.value.trim()) params.set("excludes", els.traceExcludes.value.trim());
  // 一覧の検索と同じ通し番号を使い、後から始めた方の結果だけを描く
  const seq = (logsRequestSeq += 1);
  pushLoading("セッションを追跡中...");
  try {
    const res = await fetch("/api/session-trace?" + params);
    const data = await res.json();
    if (seq !== logsRequestSeq) return;
    if (data.loading) {
      alert("ログを読み込み中です。完了してから追跡してください。");
      return;
    }
    if (!res.ok) {
      alert(data.error || "追跡に失敗しました。");
      return;
    }
    renderSessionTrace(data);
  } finally {
    popLoading();
  }
}

function renderSessionTrace(data) {
  els.rows.innerHTML = "";
  // 見出し行の位置には null を置き、ハイライト等の「行 i ↔ 項目 i」の対応を保つ
  lastPageItems = [];
  let rowCount = 0;
  data.requests.forEach((req, index) => {
    els.rows.appendChild(buildTraceGroupRow(req, index));
    lastPageItems.push(null);
    for (const item of req.items) {
      const tr = buildLogRow(item);
      if (item.anchor) tr.classList.add("trace-anchor");
      els.rows.appendChild(tr);
      lastPageItems.push(item);
      rowCount += 1;
    }
  });
  let summary =
    `セッション追跡: ${data.requests.length.toLocaleString()} リクエスト / ` +
    `${rowCount.toLocaleString()} 行（ID を含む行 ${data.anchor_total.toLocaleString()} 件）`;
  if (data.filtered_out) summary += ` / 絞り込みで除外 ${data.filtered_out.toLocaleString()} リクエスト`;
  if (data.truncated) summary += " — 上限に達したため一部のみ表示";
  if (data.anchor_total === 0) summary = "セッション追跡: ID を含む行はありませんでした";
  else if (data.requests.length === 0) summary += " — 絞り込みに一致するリクエストはありません";
  els.resultCount.textContent = summary;
  els.pageInfo.textContent = "-";
  els.prev.disabled = true;
  els.next.disabled = true;
  applyRowHighlights();
  applySourceDisplay();
}

els.traceMode.addEventListener("change", () => {
  syncTraceMode();
  saveTraceSettings();
});
syncTraceMode();
els.traceRun.addEventListener("click", runSessionTrace);
els.traceClear.addEventListener("click", () => loadLogs());
loadTraceSettings();

/**
 * 条件欄のタブ切り替え。タブを変えただけでは検索も追跡も走らせない（結果はそのまま）。
 * 選んだタブはブラウザに覚えておく。
 */
const ACTIVE_TAB_KEY = "aplv.activeTab";

function setActiveTab(tab) {
  const trace = tab === "trace";
  els.panelSearch.hidden = trace;
  els.panelTrace.hidden = !trace;
  // 折りたたみボタンと件数表示は、選んでいるタブのものに合わせる
  setFiltersCollapsed(TAB_UI[trace ? "trace" : "search"].fields().hidden);
  els.savedSearchesTitle.textContent =
    trace ? "保存した条件（リクエスト追跡）" : "保存した検索条件";
  els.tabSearch.setAttribute("aria-selected", String(!trace));
  els.tabTrace.setAttribute("aria-selected", String(trace));
  try {
    localStorage.setItem(ACTIVE_TAB_KEY, trace ? "trace" : "search");
  } catch (e) {
    // 覚えられなくても切り替え自体は効く
  }
}

els.tabSearch.addEventListener("click", () => setActiveTab("search"));
els.tabTrace.addEventListener("click", () => setActiveTab("trace"));
try {
  setActiveTab(localStorage.getItem(ACTIVE_TAB_KEY) === "trace" ? "trace" : "search");
} catch (e) {
  setActiveTab("search");
}

function resetFilters() {
  for (const el of [
    els.level,
    els.logger,
    els.thread,
    els.message,
    els.grep,
    els.source,
  ]) {
    el.value = "";
  }
  els.pageLimit.value = String(DEFAULT_PAGE_LIMIT);
  clearDatetimeFields();
  offset = 0;
  loadLogs();
}

els.search.addEventListener("click", () => {
  clearExactQueryRange();
  offset = 0;
  loadLogs();
});

els.reset.addEventListener("click", resetFilters);
els.loadDir.addEventListener("click", loadDirectory);
els.browse.addEventListener("click", openBrowseDialog);
els.rangeFirst1h.addEventListener("click", () => applyFirstHours(1));
els.rangeFirst24h.addEventListener("click", () => applyFirstHours(24));
els.rangeLast1h.addEventListener("click", () => applyLastHours(1));
els.rangeLast24h.addEventListener("click", () => applyLastHours(24));
els.rangeClear.addEventListener("click", () => {
  clearDatetimeFields();
  offset = 0;
  loadLogs();
});
els.browseUp.addEventListener("click", () => {
  if (browseParent) refreshBrowseList(browseParent);
});
els.browseSelect.addEventListener("click", () => {
  els.logDir.value = browsePath;
  els.browseDialog.close();
});

function searchAroundFromDetail(minutes) {
  if (!detailTimestamp) return;
  if (applyAroundMinutes(detailTimestamp, minutes)) {
    els.detail.close();
  }
}

function filterBySameSourceAndThreadFromDetail() {
  if (!detailContext || !detailContext.source || !detailContext.thread) return;
  els.source.value = escapeRegex(detailContext.source);
  els.thread.value = escapeRegex(detailContext.thread);
  offset = 0;
  els.detail.close();
  loadLogs();
}

els.detailSearchAround1m.addEventListener("click", () => searchAroundFromDetail(1));
els.detailSearchAround5m.addEventListener("click", () => searchAroundFromDetail(5));
els.detailFilterSameContext.addEventListener("click", () => filterBySameSourceAndThreadFromDetail());

els.regexSamples.addEventListener("click", () => {
  els.regexSamplesDialog.showModal();
});

/**
 * 検索条件欄の折りたたみ。フィールド部分（#filters-fields）だけを hidden にし、
 * ツールバー・検索/リセットボタン・範囲ヒントは常に表示のままにする。
 * hidden で隠すだけなので、折りたたんでいても入力値は DOM 上に残ったままで、
 * 検索ボタンを押したときに送られるパラメータは開いているときと変わらない。
 */
const FILTERS_COLLAPSED_KEY = "aplv.filtersCollapsed";

/**
 * 有効な検索条件の数。折りたたむと何で絞り込んでいるか見えなくなるため、
 * 件数だけは常に出す。期間は入力欄が 4 つあるが、条件としては 1 つと数える。
 * 表示件数（page-limit）は絞り込み条件ではないので数えない。
 */
function countActiveFilters() {
  // 件数に数えない欄。表示件数・判定方法・最大所要時間・前後の秒数は、値が必ず入っていて
  // 絞り込みの条件ではないため（期間は 4 欄で 1 つと数える）。
  const skipIds = ["page-limit", "trace-mode", "trace-max-minutes", "trace-window-secs",
    "since-date", "since-time", "until-date", "until-time"];
  let count = 0;
  for (const el of TAB_UI[activeTab()].panel().querySelectorAll("input[id], select[id]")) {
    if (skipIds.indexOf(el.id) >= 0) continue;
    if (el.value.trim()) count += 1;
  }
  if (activeTab() === "search" && (els.sinceDate.value || els.untilDate.value)) count += 1;
  return count;
}

/** 件数表示を現在のタブの入力に合わせる。折りたたんでいるときだけ表示する。 */
function updateFiltersSummary() {
  const count = countActiveFilters();
  els.filtersSummary.textContent = count > 0 ? `条件 ${count} 件` : "条件なし";
  els.filtersSummary.hidden = !TAB_UI[activeTab()].fields().hidden;
}

function setFiltersCollapsed(collapsed, tab) {
  const target = tab || activeTab();
  const fields = TAB_UI[target].fields();
  fields.hidden = collapsed;
  if (target === activeTab()) {
    els.filtersToggle.setAttribute("aria-controls", fields.id);
    els.filtersToggle.textContent = collapsed ? "展開する" : "折りたたむ";
    els.filtersToggle.setAttribute("aria-expanded", String(!collapsed));
    updateFiltersSummary();
  }
}

/** 折りたたみ状態をタブごとに覚える。 */
function storedCollapsed(tab) {
  try {
    return localStorage.getItem(FILTERS_COLLAPSED_KEY + "." + tab) === "1";
  } catch (e) {
    return false;
  }
}

els.filtersToggle.addEventListener("click", () => {
  const tab = activeTab();
  const collapsed = !TAB_UI[tab].fields().hidden;
  setFiltersCollapsed(collapsed, tab);
  try {
    localStorage.setItem(FILTERS_COLLAPSED_KEY + "." + tab, collapsed ? "1" : "");
  } catch (e) {
    // ストレージが使えなくても表示の切り替え自体は継続する
  }
});

// 前回の開閉状態をタブごとに復元する（読めない/壊れていても既定の展開状態にする）
setFiltersCollapsed(storedCollapsed("search"), "search");
setFiltersCollapsed(storedCollapsed("trace"), "trace");

/**
 * ハイライトの語はブラウザに覚えておく（検索条件の保存とは別。表示だけの設定なので
 * 保存した検索条件には含めない）。読めない・壊れていても空欄のまま使える。
 */
const HIGHLIGHTS_KEY = "aplv.highlights";

function saveHighlights() {
  try {
    localStorage.setItem(HIGHLIGHTS_KEY, JSON.stringify(els.highlights.map((el) => el.value)));
  } catch (e) {
    // 保存できなくてもハイライト自体は続ける
  }
}

function restoreHighlights() {
  try {
    const saved = JSON.parse(localStorage.getItem(HIGHLIGHTS_KEY) || "null");
    if (!Array.isArray(saved)) return;
    els.highlights.forEach((el, i) => {
      if (typeof saved[i] === "string") el.value = saved[i];
    });
  } catch (e) {
    // 壊れた値は無視して空欄のままにする
  }
}

for (const el of els.highlights) {
  el.addEventListener("input", () => {
    applyRowHighlights();
    saveHighlights();
  });
}
// 最初の一覧を描く前に戻しておく（描画後の applyRowHighlights で色が付く）
restoreHighlights();
els.savedSearches.addEventListener("click", () => {
  renderSavedSearchList();
  els.savedSearchesDialog.showModal();
});

/*
 * ログ書式の管理。
 *
 * サーバ側の aplv-log-formats.txt を読み書きする。ファイルを手で編集する経路も
 * 残してあるので、ここは同じファイルを触っているだけ。正規表現はそのままの形で
 * やり取りする（JSON のエスケープは保存時にサーバ側が面倒を見る）。
 */
async function loadLogFormats() {
  const res = await fetch("/api/log-formats", { cache: "no-store" });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    throw new Error(data.error || "書式の読み込みに失敗しました。");
  }
  return data;
}

function setLogFormatResult(message, kind) {
  els.logFormatResult.hidden = !message;
  els.logFormatResult.textContent = message || "";
  els.logFormatResult.className = message ? `log-format-result is-${kind}` : "log-format-result";
}

/**
 * 編集中かどうかで id 欄とボタンの表示を切り替える。
 *
 * 保存は id での upsert なので、編集の途中で id を書き換えると上書きではなく
 * 別の書式が増える（古いほうも残るので、上限 20 件を意図せず埋めてしまう）。
 * 編集中は id を触らせないことで、この取り違えを起こさせない。
 */
function setLogFormatEditing(editing) {
  els.logFormatId.readOnly = editing;
  els.logFormatIdNote.hidden = !editing;
  els.logFormatSave.textContent = editing ? "更新する" : "登録する";
}

function clearLogFormatEditor() {
  els.logFormatId.value = "";
  els.logFormatName.value = "";
  els.logFormatPattern.value = "";
  els.logFormatTimestamp.value = "";
  els.logFormatEditorTitle.textContent = "新しい書式を登録";
  setLogFormatEditing(false);
  setLogFormatResult("", "info");
}

/** 既存の書式を編集欄へ読み込む（id は固定され、保存すると上書きになる）。 */
function editLogFormat(format) {
  els.logFormatId.value = format.id;
  els.logFormatName.value = format.name;
  els.logFormatPattern.value = format.pattern;
  els.logFormatTimestamp.value = format.timestamp;
  els.logFormatEditorTitle.textContent = `「${format.name}」を編集`;
  setLogFormatEditing(true);
  setLogFormatResult("", "info");
  els.logFormatPattern.focus();
}

async function renderLogFormatList() {
  let data;
  try {
    data = await loadLogFormats();
  } catch (e) {
    els.logFormatEmpty.hidden = false;
    els.logFormatEmpty.textContent = e.message || "書式の読み込みに失敗しました。";
    els.logFormatList.innerHTML = "";
    return;
  }
  els.logFormatFile.textContent = data.file || "(不明)";
  const items = Array.isArray(data.items) ? data.items : [];
  els.logFormatEmpty.textContent = "登録した書式はまだありません。";
  els.logFormatEmpty.hidden = items.length > 0;
  // 書式の件数と行の件数は分けて出す（「書式 3 件」と言われて節が 1 つしか
  // 無いと、利用者は何を直せばよいか分からなくなる）
  const skippedParts = [];
  if (data.skipped_formats) skippedParts.push(`書式 ${data.skipped_formats} 件`);
  if (data.skipped_lines) skippedParts.push(`行 ${data.skipped_lines} 件`);
  els.logFormatSkipped.hidden = skippedParts.length === 0;
  els.logFormatSkipped.textContent = skippedParts.length
    ? `読めなかった部分があります（${skippedParts.join(" / ")}）。`
      + "直すまで登録・削除はできません。理由は起動したターミナルに行番号つきで出ています。"
    : "";

  els.logFormatList.innerHTML = "";
  for (const format of items) {
    const li = document.createElement("li");
    li.className = "saved-search-item";

    const info = document.createElement("div");
    info.className = "saved-search-info";
    const name = document.createElement("span");
    name.className = "saved-search-name";
    name.textContent = `${format.name}（${format.id}）`;
    const detail = document.createElement("span");
    detail.className = "saved-search-meta";
    detail.textContent = format.timestamp;
    info.appendChild(name);
    info.appendChild(detail);
    li.appendChild(info);

    const editBtn = document.createElement("button");
    editBtn.type = "button";
    editBtn.textContent = "編集";
    editBtn.addEventListener("click", () => editLogFormat(format));
    li.appendChild(editBtn);

    const deleteBtn = document.createElement("button");
    deleteBtn.type = "button";
    deleteBtn.className = "saved-search-delete";
    deleteBtn.textContent = "削除";
    deleteBtn.addEventListener("click", async () => {
      if (!confirm(`書式「${format.name}」を削除しますか？`)) return;
      try {
        const params = new URLSearchParams();
        params.set("id", format.id);
        const res = await fetch("/api/log-formats?" + params, { method: "DELETE" });
        const data = await res.json().catch(() => ({}));
        if (!res.ok) {
          alert(data.error || "削除に失敗しました。");
          return;
        }
        await renderLogFormatList();
      } catch (e) {
        alert(e.message || "削除に失敗しました。");
      }
    });
    li.appendChild(deleteBtn);
    els.logFormatList.appendChild(li);
  }
}

function currentLogFormatInput() {
  return {
    id: els.logFormatId.value.trim(),
    name: els.logFormatName.value.trim(),
    pattern: els.logFormatPattern.value,
    timestamp: els.logFormatTimestamp.value.trim(),
  };
}

/** 保存せずにサンプル 1 行で試す。どこを直せばよいかを、その場で返す。 */
async function tryLogFormat() {
  const body = currentLogFormatInput();
  body.sample = els.logFormatSample.value.replace(/\r?\n$/, "");
  if (!body.sample) {
    setLogFormatResult("試すログの行を入れてください。", "error");
    return;
  }
  try {
    const res = await fetch("/api/log-formats/try", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) {
      setLogFormatResult(data.error || "試せませんでした。", "error");
      return;
    }
    if (!data.matched) {
      setLogFormatResult(data.reason || "この行には一致しませんでした。", "error");
      return;
    }
    // 日時書式がまだ空のときは、正規表現だけを見た結果が返る
    const parts = [
      data.timestamp_checked ? `日時: ${data.timestamp}` : `日時の文字列: ${data.ts_text}`,
      `レベル: ${data.level || "(なし)"}`,
      `スレッド: ${data.thread || "(なし)"}`,
      `ロガー: ${data.logger || "(なし)"}`,
      `メッセージ: ${data.message || "(なし)"}`,
    ];
    const note = data.timestamp_checked
      ? ""
      : "（日時書式を入れると、この文字列を日時として読めるかも確かめます）";
    setLogFormatResult("この行から取り出せました — " + parts.join(" / ") + note, "ok");
  } catch (e) {
    setLogFormatResult(e.message || "試せませんでした。", "error");
  }
}

async function saveLogFormat() {
  const body = currentLogFormatInput();
  try {
    const res = await fetch("/api/log-formats", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) {
      setLogFormatResult(data.error || "登録に失敗しました。", "error");
      return;
    }
    setLogFormatResult(`「${data.name}」を登録しました。書式のプルダウンから選べます。`, "ok");
    els.logFormatEditorTitle.textContent = `「${data.name}」を編集`;
    // 登録した直後はその書式の編集中。続けて保存しても同じ id を上書きする
    setLogFormatEditing(true);
    await renderLogFormatList();
  } catch (e) {
    setLogFormatResult(e.message || "登録に失敗しました。", "error");
  }
}

/**
 * ガイドの「部品」をクリックしたら、正規表現の欄のカーソル位置へ差し込む。
 *
 * 正規表現を 1 から書くのは負担が大きい。ログの行を貼り付けて、変わるところだけを
 * 部品で置き換える、という進め方ができるようにする。選択範囲があればそれを置き換える。
 */
function insertLogFormatPart(snippet) {
  const el = els.logFormatPattern;
  const start = el.selectionStart != null ? el.selectionStart : el.value.length;
  const end = el.selectionEnd != null ? el.selectionEnd : el.value.length;
  el.value = el.value.slice(0, start) + snippet + el.value.slice(end);
  const caret = start + snippet.length;
  el.focus();
  el.setSelectionRange(caret, caret);
}

for (const button of document.querySelectorAll(".log-format-part")) {
  button.addEventListener("click", () => {
    insertLogFormatPart(button.dataset.insert);
    // 日時の部品は、対になる日時書式が 1 つに決まる。正規表現と日時書式の食い違いは
    // いちばん多いつまずきなので、選んだ時点で両方そろえる。
    if (button.dataset.timestamp) {
      els.logFormatTimestamp.value = button.dataset.timestamp;
    }
  });
}

// 日時書式だけを選ぶボタン（正規表現を手で書いたときのため）
for (const button of document.querySelectorAll(".log-format-ts-part")) {
  button.addEventListener("click", () => {
    els.logFormatTimestamp.value = button.dataset.timestamp;
    els.logFormatTimestamp.focus();
  });
}

els.manageFormats.addEventListener("click", () => {
  clearLogFormatEditor();
  renderLogFormatList();
  els.logFormatsDialog.showModal();
});
els.logFormatTry.addEventListener("click", tryLogFormat);
els.logFormatSave.addEventListener("click", saveLogFormat);
els.logFormatReset.addEventListener("click", clearLogFormatEditor);
/*
 * このダイアログも form method="dialog" なので、input で Enter を押すと暗黙送信が
 * 走って「閉じる」が発火し、入力中の書式が捨てられる。さらに document 側の Enter
 * ハンドラまで伝播すると、裏で検索も走ってしまう。保存した検索条件の名前欄と
 * 同じ扱いにして、Enter では「登録する」を押したのと同じ動きにする。
 */
for (const input of [els.logFormatId, els.logFormatName, els.logFormatTimestamp]) {
  input.addEventListener("keydown", (e) => {
    if (e.key !== "Enter") return;
    e.stopPropagation();
    if (e.isComposing || e.keyCode === 229) return; // IME 確定の Enter では登録しない
    e.preventDefault();
    saveLogFormat();
  });
}
/*
 * 正規表現とサンプルは textarea なので暗黙送信は起きないが、document 側の Enter
 * ハンドラは拾ってしまう（改行を入れるたびに検索が走る）。伝播だけ止める。
 */
for (const area of [els.logFormatPattern, els.logFormatSample]) {
  area.addEventListener("keydown", (e) => {
    if (e.key === "Enter") e.stopPropagation();
  });
}
// ダイアログを閉じたら、書式の増減をプルダウンへ反映する
// （loadMeta が中で updateMeta まで行うので、読み直すだけでよい）
els.logFormatsDialog.addEventListener("close", () => {
  loadMeta({ silent: true }).catch(() => {});
});
els.savedSearchSave.addEventListener("click", saveCurrentSearch);
/*
 * ダイアログは form method="dialog" なので、名前欄で Enter を押すと暗黙送信が
 * 走って「閉じる」が発火し、保存されないままダイアログが閉じる。Enter でも
 * 保存できるように既定動作を止める。
 */
els.savedSearchName.addEventListener("keydown", (e) => {
  if (e.key !== "Enter") return;
  // document 側の Enter ハンドラ（検索の実行）まで伝播すると、保存の裏で
  // 検索も走ってしまう（対象が INPUT であること以外の条件を見ていないため）。
  // IME 確定の Enter でも同じなので、保存しない場合も伝播だけは止める。
  e.stopPropagation();
  if (e.isComposing || e.keyCode === 229) return; // IME 確定の Enter では保存しない
  e.preventDefault();
  saveCurrentSearch();
});
els.fullPath.addEventListener("change", applySourceDisplay);
// リロードでチェック状態が復元されることがあるので、初期表示でも body のクラスを合わせる
applySourceDisplay();

els.prev.addEventListener("click", () => {
  offset = Math.max(0, offset - currentPageLimit);
  loadLogs();
});
els.next.addEventListener("click", () => {
  if (offset + currentPageLimit < lastTotal) {
    offset += currentPageLimit;
    loadLogs();
  }
});

document.addEventListener("keydown", (e) => {
  if (e.key === "Enter" && e.target.tagName === "INPUT") {
    if (els.panelTrace.contains(e.target)) {
      if (e.isComposing || e.keyCode === 229) return; // IME 確定の Enter では実行しない
      runSessionTrace();
      return;
    }
    if (e.target === els.logDir) {
      loadDirectory();
      return;
    }
    if (els.highlights.includes(e.target)) {
      applyRowHighlights();
      return;
    }
    if (
      e.target === els.sinceDate ||
      e.target === els.sinceTime ||
      e.target === els.untilDate ||
      e.target === els.untilTime
    ) {
      clearExactQueryRange();
    }
    offset = 0;
    loadLogs();
  }
});

loadMeta().then(async (data) => {
  if (!data.loading) {
    await loadLogs();
  }
});

updateRangeUi();
