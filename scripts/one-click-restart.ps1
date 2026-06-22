#requires -Version 5.1
<#
.SYNOPSIS
  ソース変更を反映するため app イメージを再ビルドしてコンテナを載せ替える。

.PARAMETER NoPause
  成功時に Enter 待ちをしない。

.PARAMETER NoBrowser
  ブラウザを自動で開かない。
#>
param(
    [switch] $NoPause,
    [switch] $NoBrowser
)

$ErrorActionPreference = "Stop"
$AppUrl = "http://localhost:8766"

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $RepoRoot

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw "docker が PATH にありません。"
}

. "$PSScriptRoot\Ensure-DockerDesktop.ps1"

if (-not (Test-DockerDaemon)) {
    Write-Host "==> Docker に接続できません。Docker Desktop の起動を試みます..."
    Start-DockerDesktopIfNeeded
}

Write-Host "==> repo: $RepoRoot"
Write-Host "==> docker compose up -d --build --force-recreate app"
& docker compose up -d --build --force-recreate app
if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "失敗しました（終了コード $LASTEXITCODE）。未起動なら docker-up.bat を先に実行してください。" -ForegroundColor Red
    if (-not $NoPause) {
        Write-Host "Enter キーで閉じます..."
        $null = Read-Host
    }
    exit $LASTEXITCODE
}

Write-Host ""
Write-Host "==> 再ビルド・再起動しました: $AppUrl"

if (-not $NoBrowser) {
    Start-Process $AppUrl
}

if (-not $NoPause) {
    Write-Host ""
    Write-Host "Enter キーで閉じます..."
    $null = Read-Host
}
