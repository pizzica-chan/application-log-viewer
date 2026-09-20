<#
.SYNOPSIS
    検索条件の保存ファイルを先に用意する。

.DESCRIPTION
    docker-compose.yml は aplv-saved-searches.json を単一ファイルとして bind mount する。
    ホスト側にファイルが無いと Docker が同名のディレクトリを作ってしまい、以後アプリが
    「保存ファイルが通常ファイルではありません」で失敗し続ける。起動前に空の JSON を置く。
#>

function Initialize-SavedSearchesFile {
    param(
        [Parameter(Mandatory = $true)]
        [string] $RepoRoot
    )

    $path = Join-Path $RepoRoot "aplv-saved-searches.json"
    if (Test-Path -LiteralPath $path -PathType Container) {
        throw "$path がディレクトリになっています（Docker が作った可能性があります）。削除してから実行してください。"
    }
    if (Test-Path -LiteralPath $path -PathType Leaf) {
        return
    }
    $utf8 = New-Object System.Text.UTF8Encoding $false
    [System.IO.File]::WriteAllText(
        $path,
        "{`r`n  `"version`": 1,`r`n  `"items`": []`r`n}`r`n",
        $utf8
    )
}
