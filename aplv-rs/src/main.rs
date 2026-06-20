//! Application Log Viewer (Rust + SQLite) の CLI エントリポイント。
//!
//! デフォルト: http://127.0.0.1:8767
//! 静的ファイル: `../aplv/static`（環境変数 `APLV_STATIC` で上書き可）

use aplv_rs::discovery;
use aplv_rs::web::{self, AppState};
use clap::Parser;
use std::path::PathBuf;

#[derive(Parser)]
#[command(name = "aplv-rs", about = "Java アプリログ Viewer (Rust + SQLite)")]
struct Args {
    /// 起動時に読み込むログディレクトリ
    #[arg(long)]
    dir: Option<PathBuf>,

    #[arg(long, default_value = "127.0.0.1")]
    host: String,

    #[arg(long, default_value = "8767")]
    port: u16,
}

/// Python 版と共有する静的ファイルの場所を解決する。
fn static_dir() -> PathBuf {
    if let Ok(dir) = std::env::var("APLV_STATIC") {
        return PathBuf::from(dir);
    }
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../aplv/static")
}

#[tokio::main]
async fn main() {
    let args = Args::parse();
    let static_dir = static_dir();
    if !static_dir.is_dir() {
        eprintln!("静的ファイルが見つかりません: {}", static_dir.display());
        std::process::exit(1);
    }

    let state = AppState::new(static_dir);
    if let Some(dir) = args.dir {
        let root = dir.canonicalize().expect("ディレクトリを解決");
        let paths = discovery::find_log_files(&root).expect("ログ探索");
        *state.log_root.write().await = Some(root.clone());
        *state.log_paths.write().await = paths.clone();
        state.start_load(root, paths).await;
    }

    let app = web::router(state);
    let addr = format!("{}:{}", args.host, args.port);
    let listener = tokio::net::TcpListener::bind(&addr)
        .await
        .expect("bind");
    println!("Application Log Viewer (Rust): http://{addr}");
    println!("インデックス: {{ログディレクトリ}}/.aplv/index.db");
    axum::serve(listener, app).await.expect("serve");
}
