# Dot-source 用: Docker デーモン確認と Docker Desktop の起動待ち
# 使用側: . "$PSScriptRoot\Ensure-DockerDesktop.ps1"

function Test-DockerDaemon {
    # docker info は警告を stderr に出すことがあり、Stop だと誤って例外になる
    $oldEap = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'SilentlyContinue'
        $null = docker info 2>&1
        return $LASTEXITCODE -eq 0
    } finally {
        $ErrorActionPreference = $oldEap
    }
}

function Start-DockerDesktopIfNeeded {
    if (Test-DockerDaemon) { return }

    $candidates = @(
        (Join-Path $env:ProgramFiles "Docker\Docker\Docker Desktop.exe")
    )
    $pf86 = [Environment]::GetFolderPath("ProgramFilesX86")
    if ($pf86) {
        $candidates += (Join-Path $pf86 "Docker\Docker\Docker Desktop.exe")
    }
    $exe = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
    if (-not $exe) {
        throw "Docker デーモンに接続できず、Docker Desktop の実行ファイルも見つかりませんでした。Docker Desktop を手動でインストール・起動してください。"
    }

    Write-Host "==> Docker デーモンが応答しません。Docker Desktop を起動します: $exe"
    Start-Process -FilePath $exe -WindowStyle Normal | Out-Null

    $deadline = (Get-Date).AddSeconds(180)
    while (-not (Test-DockerDaemon)) {
        if ((Get-Date) -gt $deadline) {
            throw "タイムアウト: Docker デーモンが起動しませんでした。Docker Desktop を手動で起動してから再実行してください。"
        }
        Start-Sleep -Seconds 3
        Write-Host "    ... Docker 待機中"
    }
    Write-Host "==> Docker デーモンに接続できました。"
}
