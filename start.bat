@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"

where java >nul 2>&1
if errorlevel 1 (
    echo Java が見つかりません。JDK 8 以上をインストールし、PATH に追加してください。
    pause
    exit /b 1
)

set "JAR=%~dp0aplv-java\target\aplv-java.jar"
if not exist "%JAR%" (
    where mvn >nul 2>&1
    if errorlevel 1 (
        echo Maven が見つかりません。aplv-java をビルドするには Maven 3.6 以上が必要です。
        pause
        exit /b 1
    )
    echo JAR が見つかりません。ビルドしています...
    pushd "%~dp0aplv-java"
    mvn -q clean package
    if errorlevel 1 (
        echo ビルドに失敗しました。
        popd
        pause
        exit /b 1
    )
    popd
)

set "HOST=127.0.0.1"
set "PORT=8766"

echo Application Log Viewer を起動しています...
echo ブラウザ: http://%HOST%:%PORT%
echo 停止: Ctrl+C
echo.

start "" "http://%HOST%:%PORT%"
java -jar "%JAR%" --host %HOST% --port %PORT% %*

if errorlevel 1 (
    echo.
    echo 起動に失敗しました。aplv-java のビルドが完了しているか確認してください。
    pause
)
