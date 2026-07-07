#requires -Version 5.1
<#
.SYNOPSIS
  リポジトリ内の .ps1 / .psm1 が UTF-8 BOM かつ安全な引用符かを検証する。

.EXAMPLE
  .\scripts\verify-ps1-encoding.ps1
  .\scripts\verify-ps1-encoding.ps1 -FixBom
#>
param(
    [switch] $FixBom,
    [string] $Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
)

$ErrorActionPreference = "Stop"
$utf8Bom = New-Object System.Text.UTF8Encoding $true
$failures = @()

function Test-Utf8Bom([string] $path) {
    $bytes = [System.IO.File]::ReadAllBytes($path)
    if ($bytes.Length -lt 3) { return $false }
    return ($bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF)
}

function Repair-Utf8Bom([string] $path) {
    $text = [System.IO.File]::ReadAllText($path)
    [System.IO.File]::WriteAllText($path, $text, $utf8Bom)
}

Get-ChildItem -Path $Root -Recurse -Include *.ps1,*.psm1 -File |
    Where-Object { $_.FullName -notmatch '\\node_modules\\|\\\.git\\' } |
    ForEach-Object {
        $path = $_.FullName
        $rel = $path.Substring($Root.Length).TrimStart('\', '/')

        if (-not (Test-Utf8Bom $path)) {
            if ($FixBom) {
                Repair-Utf8Bom $path
                Write-Host "FIX BOM: $rel"
            } else {
                $failures += "UTF-8 BOM なし: $rel"
            }
        }

        $content = [System.IO.File]::ReadAllText($path)
        if ($content -match 'throw\s+"[^"]*[。．\.][\s]*-[A-Za-z]') {
            $failures += "危険な throw (二重引用符 + -Switch): $rel → 単一引用符を推奨"
        }
    }

if ($failures.Count -eq 0) {
    Write-Host "OK: すべての PowerShell スクリプトを検証しました。"
    exit 0
}

Write-Host "検証に失敗しました:" -ForegroundColor Red
foreach ($f in $failures) {
    Write-Host "  - $f"
}
Write-Host ""
Write-Host "BOM 自動修正: .\scripts\verify-ps1-encoding.ps1 -FixBom"
exit 1
