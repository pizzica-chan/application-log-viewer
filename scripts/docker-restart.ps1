#requires -Version 5.1
<#
.SYNOPSIS
  このリポジトリの compose が起動中なら停止してから起動する。未起動なら起動だけする。

.DESCRIPTION
  エクスプローラーから docker-restart.bat をダブルクリックする想定。
  起動処理は one-click-up.ps1（ビルド・ブラウザ表示）に任せる。
#>
param(
    [switch] $SkipDockerDesktopStart,
    [switch] $FollowLogs,
    [switch] $NoPause,
    [switch] $NoBrowser
)

$ErrorActionPreference = "Stop"

. "$PSScriptRoot\Ensure-DockerDesktop.ps1"

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $RepoRoot

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw "docker が PATH にありません。Docker Desktop をインストールしてください。"
}

if (-not $SkipDockerDesktopStart) {
    Start-DockerDesktopIfNeeded
} elseif (-not (Test-DockerDaemon)) {
    throw 'Docker デーモンに接続できません。-SkipDockerDesktopStart を外すか、Docker Desktop を手動で起動してください。'
}

$psOutput = & docker compose ps --status running -q
if ($LASTEXITCODE -ne 0) {
    throw "docker compose ps に失敗しました（終了コード $LASTEXITCODE）。"
}
$running = @($psOutput | Where-Object { $_ -and $_.ToString().Trim() })

$upArgs = @{}
if ($SkipDockerDesktopStart) { $upArgs.SkipDockerDesktopStart = $true }
if ($FollowLogs) { $upArgs.FollowLogs = $true }
if ($NoPause) { $upArgs.NoPause = $true }
if ($NoBrowser) { $upArgs.NoBrowser = $true }

if ($running.Count -gt 0) {
    Write-Host "==> 起動中のため、停止してから起動します"
    & "$PSScriptRoot\one-click-down.ps1" -NoPause
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
} else {
    Write-Host "==> 未起動のため、起動します"
}

& "$PSScriptRoot\one-click-up.ps1" @upArgs
exit $LASTEXITCODE
