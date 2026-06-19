@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"

where python >nul 2>&1
if errorlevel 1 (
    echo Python が見つかりません。Python 3.10 以上をインストールし、PATH に追加してください。
    pause
    exit /b 1
)

set "HOST=127.0.0.1"
set "PORT=8766"

echo Application Log Viewer を起動しています...
echo ブラウザ: http://%HOST%:%PORT%
echo 停止: Ctrl+C
echo.

start "" "http://%HOST%:%PORT%"
python -m aplv --host %HOST% --port %PORT% %*

if errorlevel 1 (
    echo.
    echo 起動に失敗しました。pip install -e . を実行済みか確認してください。
    pause
)
