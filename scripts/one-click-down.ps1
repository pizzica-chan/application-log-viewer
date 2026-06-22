#requires -Version 5.1
<#
.SYNOPSIS
  one-click-up で起動したコンテナを停止する。

.PARAMETER NoPause
  終了時に Enter 待ちしない。
#>
param(
    [switch] $NoPause
)

$ErrorActionPreference = "Stop"

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
Write-Host "==> docker compose down"
& docker compose down
if ($LASTEXITCODE -ne 0) {
    Write-Host "docker compose down が失敗しました (code $LASTEXITCODE)。" -ForegroundColor Yellow
    if (-not $NoPause) {
        $null = Read-Host "Enter キーで閉じます..."
    }
    exit $LASTEXITCODE
}

Write-Host "==> 停止しました。"

if (-not $NoPause) {
    $null = Read-Host "Enter キーで閉じます..."
}
