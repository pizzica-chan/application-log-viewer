@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"

where cargo >nul 2>&1
if errorlevel 1 (
    if exist "%USERPROFILE%\.cargo\bin\cargo.exe" (
        set "PATH=%USERPROFILE%\.cargo\bin;%PATH%"
    ) else (
        echo Rust ^(cargo^) が見つかりません。https://www.rust-lang.org/tools/install からインストールしてください。
        pause
        exit /b 1
    )
)

set "HOST=127.0.0.1"
set "PORT=8767"

echo Application Log Viewer ^(Rust^) を起動しています...
echo ブラウザ: http://%HOST%:%PORT%
echo 停止: Ctrl+C
echo.

start "" "http://%HOST%:%PORT%"
cargo run --manifest-path "%~dp0aplv-rs\Cargo.toml" --release -- --host %HOST% --port %PORT% %*

if errorlevel 1 (
    echo.
    echo 起動に失敗しました。初回は cargo build --release を実行してください。
    pause
)
