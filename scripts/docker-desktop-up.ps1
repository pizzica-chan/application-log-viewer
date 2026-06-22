#requires -Version 5.1
<#
.SYNOPSIS
  Windows（Docker Desktop）上で Application Log Viewer を docker compose でビルド・起動する。

.DESCRIPTION
  - Docker デーモンが応答しない場合、Docker Desktop の実行ファイルを起動して待機する（最大約 3 分）。
  - リポジトリはこのスクリプトの 1 つ上のディレクトリを自動検出する。
  - 起動時に samples/ をマウントし、サンプルログを自動読み込みする。

.PARAMETER SkipDockerDesktopStart
  true のとき、Docker Desktop の自動起動を試みない（既に起動済み前提）。

.PARAMETER FollowLogs
  起動後に app コンテナのログを追従する（Ctrl+C で終了。コンテナは止まらない）。
#>
param(
    [switch] $SkipDockerDesktopStart,
    [switch] $FollowLogs
)

$ErrorActionPreference = "Stop"

. "$PSScriptRoot\Ensure-DockerDesktop.ps1"

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $RepoRoot

if (-not $SkipDockerDesktopStart) {
    Start-DockerDesktopIfNeeded
} elseif (-not (Test-DockerDaemon)) {
    throw "Docker デーモンに接続できません。-SkipDockerDesktopStart を外すか、Docker Desktop を手動で起動してください。"
}

Write-Host "==> repo: $RepoRoot"
Write-Host "==> docker compose up --build -d --force-recreate"
& docker compose up --build -d --force-recreate
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host ""
Write-Host "==> 起動しました。コンテナ状態:"
& docker compose ps

Write-Host ""
Write-Host "  URL: http://localhost:8766"
Write-Host "  ログ: docker compose logs -f app"
Write-Host "  停止: .\scripts\docker-desktop-down.ps1"
Write-Host "  再ビルド: .\scripts\docker-desktop-restart.ps1"

if ($FollowLogs) {
    Write-Host ""
    Write-Host "==> app ログを追従します（終了は Ctrl+C）…"
    & docker compose logs -f app
}
