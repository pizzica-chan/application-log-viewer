@echo off
setlocal
chcp 65001 >nul

rem エクスプローラーからのダブルクリック起動（cmd /c）ではウィンドウが即閉じるため cmd /k で再実行
if not defined APLV_TEST_STAY (
    echo %CMDCMDLINE% | find /i " /c " >nul
    if not errorlevel 1 (
        set APLV_TEST_STAY=1
        cmd /k "%~f0" %*
        exit /b
    )
)

cd /d "%~dp0"

where mvn >nul 2>&1
if errorlevel 1 (
    echo Maven が見つかりません。テスト実行には Maven 3.6 以上が必要です。
    goto :finish
)

echo テストを実行しています...
echo.

pushd "%~dp0aplv-java"
if errorlevel 1 (
    echo aplv-java ディレクトリが見つかりません: %~dp0aplv-java
    set "EXIT_CODE=1"
    goto :finish
)

call mvn test %*
set "EXIT_CODE=%ERRORLEVEL%"
popd

echo.
if %EXIT_CODE% equ 0 (
    echo すべてのテストが成功しました。
) else (
    echo テストに失敗しました。（終了コード: %EXIT_CODE%）
)

:finish
echo.
pause
