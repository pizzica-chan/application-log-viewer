const limit = 200;
let offset = 0;
let lastTotal = 0;
let browsePath = "";

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
  since: document.getElementById("since"),
  until: document.getElementById("until"),
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
  if (els.since.value) params.set("since", els.since.value.replace("T", " ") + ":00.000");
  if (els.until.value) params.set("until", els.until.value.replace("T", " ") + ":00.000");
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
    els.meta.textContent = "ログファイル未読み込み — ディレクトリを選択してください";
    els.fileList.textContent = "";
    setBackgroundLoading(false);
    setLoadingUi(false);
    clearLoadPoll();
    return;
  }
  if (data.load_error) {
    els.meta.textContent = `読み込みエラー: ${data.load_error}`;
    els.fileList.textContent = data.files.join(" | ");
    setBackgroundLoading(false);
    setLoadingUi(false);
    clearLoadPoll();
    return;
  }
  if (data.loading) {
    const message = `ログを読み込み中... ${data.load_progress.toLocaleString()} 行`;
    els.meta.textContent = `${message} / ファイル ${data.files.length} 件`;
    els.fileList.textContent = data.files.join(" | ");
    setBackgroundLoading(true, message);
    setLoadingUi(true);
    scheduleLoadPoll();
    return;
  }
  els.meta.textContent =
    `${data.total.toLocaleString()} 行 / ファイル ${data.files.length} 件` +
    (data.first ? ` / ${data.first} 〜 ${data.last}` : "");
  els.fileList.textContent = data.files.join(" | ");
  setBackgroundLoading(false);
  setLoadingUi(false);
  clearLoadPoll();
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
    els.since,
    els.until,
    els.grep,
    els.source,
  ]) {
    el.value = "";
  }
  offset = 0;
  loadLogs();
}

els.search.addEventListener("click", () => {
  offset = 0;
  loadLogs();
});

els.reset.addEventListener("click", resetFilters);
els.loadDir.addEventListener("click", loadDirectory);
els.browse.addEventListener("click", openBrowseDialog);
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
    offset = 0;
    loadLogs();
  }
});

loadMeta().then(async (data) => {
  if (!data.loading) {
    await loadLogs();
  }
});
