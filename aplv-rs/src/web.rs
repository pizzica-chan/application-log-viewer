//! axum ベース Web サーバー（Python 版 API 互換）。
//!
//! - `GET /api/meta` — 読み込み状態
//! - `POST /api/load` — ディレクトリ指定・インデックス構築開始
//! - `GET /api/logs` — フィルタ付き一覧
//! - `GET /api/logs/detail` — スタックトレース含む生ログ
//!
//! インデックス構築は [`tokio::task::spawn_blocking`] でバックグラウンド実行。

use crate::discovery;
use crate::index;
use crate::path_util::normalize_path;
use crate::query::{self, QueryFilter};
use axum::body::Body;
use axum::extract::{Query, State};
use axum::http::{header, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use axum::{Json, Router};
use serde::Deserialize;
use serde_json::{json, Value};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use tokio::sync::RwLock;
use tower_http::services::ServeDir;

/// サーバー全体で共有する状態（ログパス・SQLite 接続・読み込み進捗）。
#[derive(Clone)]
pub struct AppState {
    pub log_root: Arc<RwLock<Option<PathBuf>>>,
    pub log_paths: Arc<RwLock<Vec<PathBuf>>>,
    pub load_status: Arc<Mutex<String>>,
    pub load_error: Arc<Mutex<Option<String>>>,
    pub load_progress: Arc<AtomicU64>,
    pub db: Arc<Mutex<rusqlite::Connection>>,
    pub static_dir: PathBuf,
}

impl AppState {
    pub fn new(static_dir: PathBuf) -> Self {
        Self {
            log_root: Arc::new(RwLock::new(None)),
            log_paths: Arc::new(RwLock::new(Vec::new())),
            load_status: Arc::new(Mutex::new("idle".to_string())),
            load_error: Arc::new(Mutex::new(None)),
            load_progress: Arc::new(AtomicU64::new(0)),
            db: Arc::new(Mutex::new(
                rusqlite::Connection::open_in_memory().expect("in-memory sqlite"),
            )),
            static_dir,
        }
    }

    fn status(&self) -> String {
        self.load_status.lock().expect("lock").clone()
    }

    pub async fn meta_payload(&self) -> Value {
        let log_root = self.log_root.read().await.clone();
        let files: Vec<String> = self
            .log_paths
            .read()
            .await
            .iter()
            .map(|p| normalize_path(p))
            .collect();
        let status = self.status();
        let loading = status == "loading";
        let progress = self.load_progress.load(Ordering::Relaxed);

        let (total, first, last) = if loading {
            (progress, None, None)
        } else if status == "ready" {
            let conn = self.db.lock().expect("db lock");
            let count = index::entry_count(&conn).unwrap_or(0);
            let bounds = index::timestamp_bounds(&conn).unwrap_or((None, None));
            (count, bounds.0, bounds.1)
        } else {
            (0, None, None)
        };

        let mut payload = json!({
            "directory": log_root.as_ref().map(|p| normalize_path(p)),
            "files": files,
            "loading": loading,
            "load_status": status,
            "load_progress": progress,
            "total": total,
            "first": first,
            "last": last,
        });
        if let Some(err) = self.load_error.lock().expect("lock").clone() {
            payload["load_error"] = json!(err);
        }
        payload
    }

    /// バックグラウンドスレッドでインデックス構築を開始する。
    pub async fn start_load(&self, root: PathBuf, paths: Vec<PathBuf>) {
        {
            let mut st = self.load_status.lock().expect("lock");
            if *st == "loading" {
                return;
            }
            *st = "loading".to_string();
        }
        *self.load_error.lock().expect("lock") = None;
        self.load_progress.store(0, Ordering::Relaxed);

        let state = self.clone();
        tokio::task::spawn_blocking(move || {
            if let Err(e) = state.load_blocking(root, paths) {
                *state.load_status.lock().expect("lock") = "error".to_string();
                *state.load_error.lock().expect("lock") = Some(e);
            }
        });
    }

    /// 同期的なインデックス構築（`spawn_blocking` 内から呼ぶ）。
    fn load_blocking(&self, root: PathBuf, paths: Vec<PathBuf>) -> Result<(), String> {
        index_store::ensure_tmp_dir_for(Some(&root));
        let mut conn = index::open_or_create(&root).map_err(|e| e.to_string())?;
        let rebuild = index::needs_rebuild(&conn, &paths).map_err(|e| e.to_string())?;
        let progress = self.load_progress.clone();
        let total = if paths.is_empty() {
            drop(conn);
            index_store::delete_index_files(&root);
            conn = index::open_or_create(&root).map_err(|e| e.to_string())?;
            index::clear_index(&conn).map_err(|e| e.to_string())?;
            0
        } else if rebuild {
            drop(conn);
            index_store::delete_index_files(&root);
            conn = index::open_or_create(&root).map_err(|e| e.to_string())?;
            index::build_index(&conn, &paths, |n| {
                progress.store(n, Ordering::Relaxed);
            })
            .map_err(|e| e.to_string())?
        } else {
            index::entry_count(&conn).map_err(|e| e.to_string())?
        };

        {
            let mut db = self.db.lock().map_err(|e| e.to_string())?;
            *db = conn;
        }

        self.load_progress.store(total, Ordering::Relaxed);
        *self.load_status.lock().map_err(|e| e.to_string())? = "ready".to_string();
        Ok(())
    }
}

pub fn router(state: AppState) -> Router {
    let static_dir = state.static_dir.clone();
    Router::new()
        .route("/", get(serve_index))
        .nest_service("/static", ServeDir::new(static_dir))
        .route("/api/meta", get(api_meta))
        .route("/api/browse", get(api_browse))
        .route("/api/load", post(api_load))
        .route("/api/logs", get(api_logs))
        .route("/api/logs/detail", get(api_logs_detail))
        .with_state(state)
}

async fn serve_index(State(state): State<AppState>) -> Result<Response, ApiError> {
    let path = state.static_dir.join("index.html");
    let body = tokio::fs::read(&path)
        .await
        .map_err(|e| ApiError::internal(format!("index.html: {e}")))?;
    Ok(Response::builder()
        .status(StatusCode::OK)
        .header(header::CONTENT_TYPE, "text/html; charset=utf-8")
        .body(Body::from(body))
        .expect("response"))
}

async fn api_meta(State(state): State<AppState>) -> Json<Value> {
    Json(state.meta_payload().await)
}

#[derive(Deserialize)]
struct BrowseQuery {
    path: Option<String>,
}

async fn api_browse(
    State(state): State<AppState>,
    Query(q): Query<BrowseQuery>,
) -> Result<Json<Value>, ApiError> {
    let current = if let Some(p) = q.path.filter(|s| !s.is_empty()) {
        PathBuf::from(&p)
    } else if let Some(root) = state.log_root.read().await.clone() {
        root
    } else {
        std::env::current_dir().map_err(|e| ApiError::bad(e.to_string()))?
    };
    Ok(Json(browse_directory(&current)?))
}

fn browse_directory(raw: &Path) -> Result<Value, ApiError> {
    let current = raw
        .canonicalize()
        .map_err(|_| ApiError::bad(format!("ディレクトリが見つかりません: {}", raw.display())))?;
    let current_display = normalize_path(&current);
    if !current.is_dir() {
        return Err(ApiError::bad(format!(
            "ディレクトリが見つかりません: {}",
            current_display
        )));
    }
    let parent = current
        .parent()
        .filter(|p| *p != current)
        .map(|p| normalize_path(p));
    let mut directories = Vec::new();
    let entries = std::fs::read_dir(&current).map_err(|e| ApiError::bad(format!("ディレクトリを読み取れません: {e}")))?;
    for entry in entries.flatten() {
        let path = entry.path();
        if path.is_dir() {
            let name = path.file_name().and_then(|n| n.to_str()).unwrap_or("");
            if !name.starts_with('.') {
                if let Ok(canon) = path.canonicalize() {
                    directories.push(normalize_path(&canon));
                }
            }
        }
    }
    directories.sort_by_key(|a| a.to_lowercase());
    Ok(json!({
        "current": current_display,
        "parent": parent,
        "directories": directories,
    }))
}

#[derive(Deserialize)]
struct LoadBody {
    directory: String,
}

async fn api_load(
    State(state): State<AppState>,
    Json(body): Json<LoadBody>,
) -> Result<(StatusCode, Json<Value>), ApiError> {
    if body.directory.is_empty() {
        return Err(ApiError::bad("directory を指定してください".to_string()));
    }
    let root = PathBuf::from(&body.directory)
        .canonicalize()
        .map_err(|_| ApiError::bad(format!("ディレクトリが見つかりません: {}", body.directory)))?;
    if !root.is_dir() {
        return Err(ApiError::bad(format!(
            "ディレクトリが見つかりません: {}",
            root.display()
        )));
    }
    let paths = discovery::find_log_files(&root).map_err(|e| ApiError::bad(e.to_string()))?;
    *state.log_root.write().await = Some(root.clone());
    *state.log_paths.write().await = paths.clone();
    *state.load_status.lock().expect("lock") = "idle".to_string();
    state.start_load(root, paths).await;
    Ok((StatusCode::OK, Json(state.meta_payload().await)))
}

#[derive(Deserialize)]
struct LogsQuery {
    level: Option<String>,
    logger: Option<String>,
    thread: Option<String>,
    message: Option<String>,
    grep: Option<String>,
    source: Option<String>,
    since: Option<String>,
    until: Option<String>,
    limit: Option<String>,
    offset: Option<String>,
}

async fn api_logs(
    State(state): State<AppState>,
    Query(q): Query<LogsQuery>,
) -> Result<Json<Value>, ApiError> {
    let status = state.status();
    if status == "loading" {
        let progress = state.load_progress.load(Ordering::Relaxed);
        return Ok(Json(json!({
            "loading": true,
            "load_progress": progress,
            "total": 0,
            "offset": 0,
            "limit": 0,
            "items": [],
        })));
    }
    if status == "error" {
        let err = state
            .load_error
            .lock()
            .expect("lock")
            .clone()
            .unwrap_or_else(|| "読み込みに失敗しました".to_string());
        return Err(ApiError::internal(err));
    }

    let limit = q
        .limit
        .as_deref()
        .unwrap_or("500")
        .parse::<u64>()
        .map(|n| n.min(5000))
        .map_err(|_| ApiError::bad("limit/offset は整数で指定してください".to_string()))?;
    let offset = q
        .offset
        .as_deref()
        .unwrap_or("0")
        .parse::<u64>()
        .map_err(|_| ApiError::bad("limit/offset は整数で指定してください".to_string()))?;

    let filter = build_filter(&q)?;
    let conn = state.db.lock().map_err(|e| ApiError::internal(e.to_string()))?;
    let (total, page) = query::query_logs(&conn, &filter, offset, limit)
        .map_err(|e| ApiError::internal(e.to_string()))?;
    let items: Vec<Value> = page.iter().map(query::entry_to_json).collect();
    Ok(Json(json!({
        "total": total,
        "offset": offset,
        "limit": limit,
        "items": items,
    })))
}

fn build_filter(q: &LogsQuery) -> Result<QueryFilter, ApiError> {
    let since = q
        .since
        .as_deref()
        .filter(|s| !s.is_empty())
        .map(query::parse_datetime)
        .transpose()
        .map_err(ApiError::bad)?;
    let until = q
        .until
        .as_deref()
        .filter(|s| !s.is_empty())
        .map(query::parse_datetime)
        .transpose()
        .map_err(ApiError::bad)?;

    Ok(QueryFilter {
        levels: q
            .level
            .as_deref()
            .filter(|s| !s.is_empty())
            .and_then(query::parse_level_filter),
        logger_re: opt_regex(q.logger.as_deref())?,
        thread_re: opt_regex(q.thread.as_deref())?,
        message_re: opt_regex(q.message.as_deref())?,
        source_re: opt_regex(q.source.as_deref())?,
        grep_re: opt_regex(q.grep.as_deref())?,
        since,
        until,
    })
}

fn opt_regex(pat: Option<&str>) -> Result<Option<regex::Regex>, ApiError> {
    match pat.filter(|s| !s.is_empty()) {
        Some(p) => query::compile_regex(p)
            .map(Some)
            .map_err(ApiError::bad),
        None => Ok(None),
    }
}

#[derive(Deserialize)]
struct DetailQuery {
    source: String,
    line_no: String,
    timestamp: Option<String>,
}

async fn api_logs_detail(
    State(state): State<AppState>,
    Query(q): Query<DetailQuery>,
) -> Result<Json<Value>, ApiError> {
    if q.source.is_empty() {
        return Err(ApiError::bad("source を指定してください".to_string()));
    }
    let line_no: i64 = q
        .line_no
        .parse()
        .map_err(|_| ApiError::bad("line_no は整数で指定してください".to_string()))?;
    let conn = state.db.lock().map_err(|e| ApiError::internal(e.to_string()))?;
    let entry = index::find_entry(
        &conn,
        &q.source,
        line_no,
        q.timestamp.as_deref().filter(|s| !s.is_empty()),
    )
    .map_err(|e| ApiError::internal(e.to_string()))?
    .ok_or_else(|| ApiError::not_found("該当行が見つかりません".to_string()))?;

    let raw = index::read_entry_raw(
        Path::new(&entry.source),
        entry.byte_offset as u64,
        entry.end_byte_offset.map(|v| v as u64),
    )
    .map_err(|e| ApiError::internal(e.to_string()))?;

    Ok(Json(json!({
        "source": entry.source,
        "line_no": entry.line_no,
        "timestamp": entry.timestamp.format("%Y-%m-%dT%H:%M:%S%.6f").to_string(),
        "level": entry.level,
        "logger": entry.logger,
        "thread": entry.thread,
        "raw": raw,
    })))
}

struct ApiError {
    status: StatusCode,
    message: String,
}

impl ApiError {
    fn bad(msg: String) -> Self {
        Self {
            status: StatusCode::BAD_REQUEST,
            message: msg,
        }
    }
    fn not_found(msg: String) -> Self {
        Self {
            status: StatusCode::NOT_FOUND,
            message: msg,
        }
    }
    fn internal(msg: String) -> Self {
        Self {
            status: StatusCode::INTERNAL_SERVER_ERROR,
            message: msg,
        }
    }
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        (self.status, Json(json!({ "error": self.message }))).into_response()
    }
}
