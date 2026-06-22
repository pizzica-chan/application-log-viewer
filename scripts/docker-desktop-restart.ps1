#requires -Version 5.1
<#
.SYNOPSIS
  ソース変更を反映するため app イメージを再ビルドしてコンテナを載せ替える。
#>
$ErrorActionPreference = "Stop"

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $RepoRoot

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw "docker が PATH にありません。"
}

Write-Host "==> repo: $RepoRoot"
Write-Host "==> docker compose up -d --build --force-recreate app"
& docker compose up -d --build --force-recreate app
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host ""
Write-Host "==> 再ビルド・再起動しました。"
Write-Host "  URL: http://localhost:8766"
