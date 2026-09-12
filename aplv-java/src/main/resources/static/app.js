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
  highlight: document.getElementById("highlight"),
  fullPath: document.getElementById("full-path"),
  savedSearches: document.getElementById("saved-searches"),
  savedSearchesDialog: document.getElementById("saved-searches-dialog"),
  savedSearchName: document.getElementById("saved-search-name"),
  savedSearchSave: document.getElementById("saved-search-save"),
  savedSearchList: document.getElementById("saved-search-list"),
  savedSearchEmpty: document.getElementById("saved-search-empty"),
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

function levelClass(level) {
  if (!level) return "";
  const upper = String(level).toUpperCase();
  if (upper === "ERROR") return "level-error";
  if (upper === "WARN" || upper === "WARNING") return "level-warn";
  if (upper === "INFO") return "level-info";
  if (upper === "DEBUG") return "level-debug";
  if (upper === "TRACE") return "level-trace";
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

function getHighlightNeedle() {
  const text = els.highlight.value.trim();
  return text ? text.toLowerCase() : "";
}

/** ハイライト判定。grep と同様にスタックトレース含む raw を優先する（検索結果は変えない）。 */
function rowMatchesHighlight(item, needle) {
  if (!needle) return false;
  const haystack = item.raw || [
    item.timestamp,
    item.level,
    item.logger,
    item.thread,
    item.message,
    item.source,
    item.line_no,
  ].filter((v) => v != null && v !== "").join(" ");
  return haystack.toLowerCase().includes(needle);
}

function applyRowHighlights() {
  const needle = getHighlightNeedle();
  const rows = els.rows.querySelectorAll("tr");
  for (let i = 0; i < rows.length; i += 1) {
    const item = lastPageItems[i];
    rows[i].classList.toggle(
      "row-highlight",
      Boolean(item && rowMatchesHighlight(item, needle))
    );
  }
}

function setLoadingUi(loading) {
  els.search.disabled = loading;
  els.reset.disabled = loading;
  els.loadDir.disabled = loading;
  els.browse.disabled = loading;
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

function updateParseWarning(data) {
  const skipped = data.skipped_lines || 0;
  if (skipped <= 0) {
    els.parseWarning.hidden = true;
    els.parseWarningText.textContent = "";
    els.parseWarningSamples.innerHTML = "";
    return;
  }
  els.parseWarning.hidden = false;
  els.parseWarningText.textContent =
    `${skipped.toLocaleString()} 行を Java アプリログ形式として認識できませんでした` +
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
    (data.first ? ` / ${data.first} 〜 ${data.last}` : "");
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
      body: JSON.stringify({ directory }),
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
 * 検索条件の保存・呼び出し（localStorage、ブラウザ単位）。
 * ハイライトやフルパス表示など表示設定は対象外。検索条件欄（.filters）の
 * input/select を id -> value のマップとして保存し、適用時は同じ id の要素へ書き戻す。
 */
const SAVED_SEARCHES_KEY = "aplv.savedSearches";

function loadSavedSearches() {
  try {
    const raw = localStorage.getItem(SAVED_SEARCHES_KEY);
    const list = raw ? JSON.parse(raw) : [];
    return Array.isArray(list) ? list : [];
  } catch (e) {
    return [];
  }
}

function writeSavedSearches(list) {
  try {
    localStorage.setItem(SAVED_SEARCHES_KEY, JSON.stringify(list));
  } catch (e) {
    alert("検索条件の保存に失敗しました（ブラウザのストレージが使用できません）。");
  }
}

function collectFilterFields() {
  const fields = {};
  for (const el of document.querySelectorAll(".filters input[id], .filters select[id]")) {
    fields[el.id] = el.value;
  }
  return fields;
}

function applyFilterFields(fields) {
  for (const [id, value] of Object.entries(fields || {})) {
    const el = document.getElementById(id);
    if (el) el.value = value;
  }
  // クイック範囲ボタンが設定する秒未満の精度は保存対象外。日時欄の値（分単位）で
  // 検索すれば同じ範囲が再現されるため、古い厳密範囲は捨てる。
  clearExactQueryRange();
  updateRangeUi();
}

function formatSavedAt(iso) {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "";
  const p2 = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p2(d.getMonth() + 1)}-${p2(d.getDate())} ${p2(d.getHours())}:${p2(d.getMinutes())}`;
}

function renderSavedSearchList() {
  const list = loadSavedSearches().sort((a, b) => (a.savedAt < b.savedAt ? 1 : -1));
  els.savedSearchList.innerHTML = "";
  els.savedSearchEmpty.hidden = list.length > 0;
  for (const saved of list) {
    const li = document.createElement("li");
    li.className = "saved-search-item";

    const info = document.createElement("div");
    info.className = "saved-search-info";
    const name = document.createElement("span");
    name.className = "saved-search-name";
    name.textContent = saved.name;
    name.title = saved.name;
    const date = document.createElement("span");
    date.className = "saved-search-date";
    date.textContent = formatSavedAt(saved.savedAt);
    info.appendChild(name);
    info.appendChild(date);

    const buttons = document.createElement("div");
    buttons.className = "saved-search-buttons";
    const applyBtn = document.createElement("button");
    applyBtn.type = "button";
    applyBtn.textContent = "適用";
    applyBtn.addEventListener("click", () => {
      applyFilterFields(saved.fields);
      els.savedSearchesDialog.close();
      offset = 0;
      loadLogs();
    });
    const deleteBtn = document.createElement("button");
    deleteBtn.type = "button";
    deleteBtn.className = "saved-search-delete";
    deleteBtn.textContent = "削除";
    deleteBtn.addEventListener("click", () => {
      if (!confirm(`「${saved.name}」を削除しますか？`)) return;
      writeSavedSearches(loadSavedSearches().filter((s) => s.id !== saved.id));
      renderSavedSearchList();
    });
    buttons.appendChild(applyBtn);
    buttons.appendChild(deleteBtn);

    li.appendChild(info);
    li.appendChild(buttons);
    els.savedSearchList.appendChild(li);
  }
}

/** 現在の検索条件欄の内容に名前を付けて保存する。同名があれば確認のうえ上書きする。 */
function saveCurrentSearch() {
  const name = els.savedSearchName.value.trim();
  if (!name) {
    alert("名前を入力してください。");
    return;
  }
  const list = loadSavedSearches();
  const existing = list.find((s) => s.name === name);
  if (existing && !confirm(`「${name}」は既に保存されています。上書きしますか？`)) return;
  const fields = collectFilterFields();
  const savedAt = new Date().toISOString();
  if (existing) {
    existing.fields = fields;
    existing.savedAt = savedAt;
  } else {
    list.push({
      id: String(Date.now()) + "-" + Math.random().toString(36).slice(2, 8),
      name,
      savedAt,
      fields,
    });
  }
  writeSavedSearches(list);
  els.savedSearchName.value = "";
  renderSavedSearchList();
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

async function loadLogs() {
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
      els.rows.appendChild(tr);
    }
    applyRowHighlights();
  } finally {
    popLoading();
  }
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

els.highlight.addEventListener("input", applyRowHighlights);
els.savedSearches.addEventListener("click", () => {
  renderSavedSearchList();
  els.savedSearchesDialog.showModal();
});
els.savedSearchSave.addEventListener("click", saveCurrentSearch);
/*
 * ダイアログは form method="dialog" なので、名前欄で Enter を押すと暗黙送信が
 * 走って「閉じる」が発火し、保存されないままダイアログが閉じる。Enter でも
 * 保存できるように既定動作を止める。
 */
els.savedSearchName.addEventListener("keydown", (e) => {
  if (e.key !== "Enter") return;
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
    if (e.target === els.logDir) {
      loadDirectory();
      return;
    }
    if (e.target === els.highlight) {
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
