const limit = 200;
let offset = 0;
let lastTotal = 0;
let browsePath = "";
let metaRange = { first: null, last: null };

const els = {
  meta: document.getElementById("meta"),
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
  search: document.getElementById("search"),
  reset: document.getElementById("reset"),
  rows: document.getElementById("rows"),
  resultCount: document.getElementById("result-count"),
  pageInfo: document.getElementById("page-info"),
  prev: document.getElementById("prev"),
  next: document.getElementById("next"),
  detail: document.getElementById("detail"),
  detailBody: document.getElementById("detail-body"),
  detailSearchAround: document.getElementById("detail-search-around"),
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

function isoToMsJst(iso) {
  const p = parseIsoParts(iso);
  if (!p) return null;
  return Date.UTC(p.y, p.mo - 1, p.d, p.h, p.mi, p.s) - 9 * 3600000;
}

function msJstToFields(ms) {
  const jst = new Date(ms + 9 * 3600000);
  return {
    date:
      jst.getUTCFullYear() +
      "-" +
      pad2(jst.getUTCMonth() + 1) +
      "-" +
      pad2(jst.getUTCDate()),
    time: pad2(jst.getUTCHours()) + ":" + pad2(jst.getUTCMinutes()),
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

function clearDatetimeFields() {
  els.sinceDate.value = "";
  els.sinceTime.value = "";
  els.untilDate.value = "";
  els.untilTime.value = "";
  clearExactQueryRange();
}

function getSinceParam() {
  if (!els.sinceDate.value) return null;
  const time = els.sinceTime.value || "00:00";
  return `${els.sinceDate.value} ${time}:00.000`;
}

function getUntilParam() {
  if (!els.untilDate.value) return null;
  const time = els.untilTime.value || "23:59";
  // 時刻入力は分単位のため、その分の末尾（59.999 秒）まで含める
  return `${els.untilDate.value} ${time}:59.999`;
}

/** ISO 文字列（API meta）を since/until クエリ形式へ。 */
function isoToApiDatetime(iso) {
  return iso.replace("T", " ");
}

/** JST 基準の epoch ms を API の日時文字列へ（ミリ秒まで）。 */
function msJstToApiDatetime(ms) {
  const jst = new Date(ms + 9 * 3600000);
  const y = jst.getUTCFullYear();
  const mo = pad2(jst.getUTCMonth() + 1);
  const d = pad2(jst.getUTCDate());
  const h = pad2(jst.getUTCHours());
  const mi = pad2(jst.getUTCMinutes());
  const sec = pad2(jst.getUTCSeconds());
  const milli = String(jst.getUTCMilliseconds()).padStart(3, "0");
  return `${y}-${mo}-${d} ${h}:${mi}:${sec}.${milli}`;
}

/** クイック選択ボタン用。手入力フィールドより優先する正確な since/until。 */
let exactQueryRange = null;

/** 詳細ダイアログ表示中のログ時刻（ISO）。 */
let detailTimestamp = null;

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
    els.sinceDate.min = msJstToFields(isoToMsJst(metaRange.first)).date;
    els.sinceDate.max = msJstToFields(isoToMsJst(metaRange.last)).date;
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
  const startMs = isoToMsJst(metaRange.first);
  const endMs = isoToMsJst(metaRange.last);
  if (startMs == null || endMs == null) return;
  const untilMs = Math.min(endMs, startMs + hours * 3600000);
  exactQueryRange = {
    since: isoToApiDatetime(metaRange.first),
    until: msJstToApiDatetime(untilMs),
  };
  setDatetimeFields(msJstToFields(startMs), msJstToFields(untilMs));
  offset = 0;
  loadLogs();
}

function applyLastHours(hours) {
  if (!metaRange.first || !metaRange.last) return;
  const endMs = isoToMsJst(metaRange.last);
  const startMs = isoToMsJst(metaRange.first);
  if (endMs == null || startMs == null) return;
  const sinceMs = Math.max(startMs, endMs - hours * 3600000);
  exactQueryRange = {
    since: msJstToApiDatetime(sinceMs),
    until: isoToApiDatetime(metaRange.last),
  };
  setDatetimeFields(msJstToFields(sinceMs), msJstToFields(endMs));
  offset = 0;
  loadLogs();
}

function applyAroundMinutes(isoTimestamp, minutes) {
  const centerMs = isoToMsJst(isoTimestamp);
  if (centerMs == null) return false;
  const delta = minutes * 60 * 1000;
  let sinceMs = centerMs - delta;
  let untilMs = centerMs + delta;
  if (metaRange.first && metaRange.last) {
    const firstMs = isoToMsJst(metaRange.first);
    const lastMs = isoToMsJst(metaRange.last);
    if (firstMs != null) sinceMs = Math.max(firstMs, sinceMs);
    if (lastMs != null) untilMs = Math.min(lastMs, untilMs);
  }
  exactQueryRange = {
    since: msJstToApiDatetime(sinceMs),
    until: msJstToApiDatetime(untilMs),
  };
  setDatetimeFields(msJstToFields(sinceMs), msJstToFields(untilMs));
  offset = 0;
  loadLogs();
  return true;
}

function buildQuery() {
  const params = new URLSearchParams();
  params.set("limit", String(limit));
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

function updateMeta(data) {
  if (data.directory) {
    els.logDir.value = data.directory;
  }
  if (data.files.length === 0) {
    metaRange = { first: null, last: null };
    els.meta.textContent = "ログファイル未読み込み — ディレクトリを選択してください";
    els.fileList.textContent = "";
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
  browsePath = els.logDir.value.trim();
  await refreshBrowseList();
  els.browseDialog.showModal();
}

async function refreshBrowseList() {
  const params = new URLSearchParams();
  if (browsePath) params.set("path", browsePath);
  const res = await fetch("/api/browse?" + params);
  const data = await res.json();
  if (!res.ok) {
    alert(data.error || "ディレクトリ一覧の取得に失敗しました。");
    return;
  }
  browsePath = data.current;
  els.browseCurrent.textContent = data.current;
  els.browseUp.disabled = !data.parent;
  els.browseList.innerHTML = "";
  for (const dir of data.directories) {
    const li = document.createElement("li");
    const btn = document.createElement("button");
    btn.type = "button";
    btn.textContent = dir.split(/[/\\]/).pop() || dir;
    btn.title = dir;
    btn.addEventListener("click", async () => {
      browsePath = dir;
      await refreshBrowseList();
    });
    li.appendChild(btn);
    els.browseList.appendChild(li);
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

async function loadLogs() {
  pushLoading("ログを検索中...");
  try {
    const res = await fetch("/api/logs?" + buildQuery());
    const data = await res.json();
    if (data.loading) {
      const message = `ログを読み込み中... ${data.load_progress.toLocaleString()} 行`;
      els.resultCount.textContent = message;
      els.pageInfo.textContent = "-";
      els.rows.innerHTML = "";
      els.prev.disabled = true;
      els.next.disabled = true;
      setBackgroundLoading(true, message);
      setLoadingUi(true);
      return;
    }
    if (!res.ok) {
      alert(data.error || "取得に失敗しました。");
      return;
    }
    setBackgroundLoading(false);
    setLoadingUi(false);
    lastTotal = data.total;
    els.resultCount.textContent = `${data.total.toLocaleString()} 件ヒット`;
    const page = Math.floor(offset / limit) + 1;
    const pages = Math.max(1, Math.ceil(data.total / limit));
    els.pageInfo.textContent = `${page} / ${pages}`;
    els.prev.disabled = offset <= 0;
    els.next.disabled = offset + limit >= data.total;

    els.rows.innerHTML = "";
    for (const item of data.items) {
      const tr = document.createElement("tr");

      addCell(tr, item.timestamp);
      addCell(tr, item.level, { className: levelClass(item.level) });
      addCell(tr, item.logger, { className: "logger", title: item.logger });
      addCell(tr, item.thread, { title: item.thread });
      addCell(tr, item.message, { className: "message", title: item.message });
      addCell(tr, item.source + ":" + item.line_no, {
        className: "source",
        title: item.source,
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
            "ファイル: " + detail.source + "\n" +
            "行番号: " + detail.line_no + "\n" +
            "時刻: " + detail.timestamp + "\n" +
            "Level: " + detail.level + "\n" +
            "Logger: " + detail.logger + "\n" +
            "Thread: " + detail.thread + "\n\n" +
            detail.raw;
          detailTimestamp = detail.timestamp;
          els.detail.showModal();
        } finally {
          popLoading();
        }
      });
      els.rows.appendChild(tr);
    }
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
els.browseUp.addEventListener("click", async () => {
  const params = new URLSearchParams();
  params.set("path", browsePath);
  const res = await fetch("/api/browse?" + params);
  const data = await res.json();
  if (data.parent) {
    browsePath = data.parent;
    await refreshBrowseList();
  }
});
els.browseSelect.addEventListener("click", () => {
  els.logDir.value = browsePath;
  els.browseDialog.close();
});

els.detailSearchAround.addEventListener("click", () => {
  if (!detailTimestamp) return;
  if (applyAroundMinutes(detailTimestamp, 5)) {
    els.detail.close();
  }
});

els.regexSamples.addEventListener("click", () => {
  els.regexSamplesDialog.showModal();
});

els.prev.addEventListener("click", () => {
  offset = Math.max(0, offset - limit);
  loadLogs();
});
els.next.addEventListener("click", () => {
  if (offset + limit < lastTotal) {
    offset += limit;
    loadLogs();
  }
});

document.addEventListener("keydown", (e) => {
  if (e.key === "Enter" && e.target.tagName === "INPUT") {
    if (e.target === els.logDir) {
      loadDirectory();
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
