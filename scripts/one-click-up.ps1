#requires -Version 5.1
<#
.SYNOPSIS
  Docker Desktop 停止状態から、ビルド・起動・ブラウザ表示までを 1 本化する（Windows 側から実行）。

.DESCRIPTION
  - エクスプローラーから docker-up.bat または scripts\one-click-up.cmd をダブルクリックする想定。
  - Docker Desktop が止まっていれば起動し、デーモン応答まで待つ（最大約 3 分）。
  - samples/ をマウントし、起動時にサンプルログを自動読み込みする。

.PARAMETER SkipDockerDesktopStart
  Docker Desktop の自動起動を試みない。

.PARAMETER FollowLogs
  起動後に app コンテナのログを追従する（Ctrl+C で終了。コンテナは止まらない）。

.PARAMETER NoPause
  成功時に Enter 待ちをしない（他スクリプトから呼ぶとき用）。

.PARAMETER NoBrowser
  ブラウザを自動で開かない。
#>
param(
    [switch] $SkipDockerDesktopStart,
    [switch] $FollowLogs,
    [switch] $NoPause,
    [switch] $NoBrowser
)

$ErrorActionPreference = "Stop"
$AppUrl = "http://localhost:8766"

. "$PSScriptRoot\Ensure-DockerDesktop.ps1"

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $RepoRoot

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw "docker が PATH にありません。Docker Desktop をインストールしてください。"
}

if (-not $SkipDockerDesktopStart) {
    Start-DockerDesktopIfNeeded
} elseif (-not (Test-DockerDaemon)) {
    throw "Docker デーモンに接続できません。-SkipDockerDesktopStart を外すか、Docker Desktop を手動で起動してください。"
}

Write-Host "==> Application Log Viewer を Docker で起動します"
Write-Host "==> repo: $RepoRoot"
Write-Host "==> docker compose up --build -d --force-recreate"
& docker compose up --build -d --force-recreate
if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "失敗しました（終了コード $LASTEXITCODE）。" -ForegroundColor Red
    if (-not $NoPause) {
        Write-Host "Enter キーで閉じます..."
        $null = Read-Host
    }
    exit $LASTEXITCODE
}

Write-Host ""
Write-Host "==> 起動しました。コンテナ状態:"
& docker compose ps

Write-Host ""
Write-Host "==> 完了: $AppUrl"

if (-not $NoBrowser) {
    Start-Process $AppUrl
}

Write-Host ""
Write-Host "  ログ: docker compose logs -f app"
Write-Host "  停止: docker-down.bat または .\scripts\one-click-down.ps1"
Write-Host "  再ビルド: .\scripts\one-click-restart.ps1"

if ($FollowLogs) {
    Write-Host ""
    Write-Host "==> app ログを追従します（終了は Ctrl+C）。コンテナは止まりません。"
    & docker compose logs -f app
} elseif (-not $NoPause) {
    Write-Host ""
    Write-Host "Enter キーで閉じます..."
    $null = Read-Host
}
