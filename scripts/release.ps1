<#
.SYNOPSIS
    ビルド済み APK を GitHub Release（pre-release）にアップロードします。

.DESCRIPTION
    build.ps1 でビルドした APK を、AndroidManifest.xml の android:versionName をタグとして
    GitHub Release にアップロードします。デフォルトは pre-release です。
    同名のタグ／release が既に存在する場合は上書きせず失敗します。
    実際にアップロードする前に、実機で動作確認済みであることを確認してください。

.PARAMETER Version
    タグ名（例: v0.1.22）。省略時は AndroidManifest.xml の android:versionName に "v" を付けた値。

.PARAMETER Notes
    release の本文。省略時は直近のコミットメッセージを使用します。

.PARAMETER SkipBuild
    既存の build\xreal-recorder.apk を使い、ビルドをスキップします。

.PARAMETER NoPrerelease
    pre-release ではなく通常の release として公開します。

.EXAMPLE
    .\scripts\release.ps1
    Manifest の versionName をタグとしてビルド＋アップロードします。

.EXAMPLE
    .\scripts\release.ps1 -Version v0.1.22 -Notes "Material 3 UI 反映"
    タグと本文を明示してアップロードします。

.EXAMPLE
    .\scripts\release.ps1 -SkipBuild
    直近にビルドした APK をそのままアップロードします。

.EXAMPLE
    .\scripts\release.ps1 -h
    このヘルプを表示します（引数なしでも同じヘルプを表示します）。
#>
[CmdletBinding()]
param(
    [string]$Version,
    [string]$Notes,
    [switch]$SkipBuild,
    [switch]$NoPrerelease,
    [Alias('h', 'help')]
    [switch]$ShowHelp
)

#region ヘルプ表示
if ($ShowHelp) {
    Get-Help $PSCommandPath -Detailed
    exit 0
}
#endregion

$ErrorActionPreference = "Stop"

#region パス・バージョン解決
$root = Split-Path -Parent $PSScriptRoot
$manifest = Join-Path $root "app\AndroidManifest.xml"
$apk = Join-Path $root "build\xreal-recorder.apk"

$manifestText = Get-Content $manifest -Raw -Encoding UTF8
if ($manifestText -notmatch 'android:versionName="([^"]+)"') {
    throw "android:versionName を AndroidManifest.xml から取得できません"
}
$manifestVersion = $Matches[1]
if (-not $Version) { $Version = "v$manifestVersion" }
if ($Version -notmatch '^v\d') { $Version = "v$Version" }
if (-not $Notes) {
    $Notes = (git -C $root log -1 --pretty=%B).Trim()
    if (-not $Notes) { $Notes = "XREAL 1S Eye Recorder $Version" }
}
#endregion

#region ビルド
if (-not $SkipBuild) {
    Write-Host "Building APK..."
    & (Join-Path $PSScriptRoot "build.ps1")
    if ($LASTEXITCODE -ne 0) { throw "build.ps1 が失敗しました" }
}
if (-not (Test-Path $apk)) { throw "APK が見つかりません: $apk" }
$apkItem = Get-Item $apk
Write-Host ("APK: {0} ({1:N2} MB, {2})" -f $apkItem.FullName,
    ($apkItem.Length / 1MB), $apkItem.LastWriteTime)
#endregion

#region GitHub 認証・既存 release チェック
$auth = gh auth status 2>&1
if ($LASTEXITCODE -ne 0) {
    throw "gh の認証が完了していません。'gh auth login' を実行してください。`n$auth"
}
# gh writes "release not found" to stderr when absent; suppress it so Stop does not throw.
$savedEap = $ErrorActionPreference
$ErrorActionPreference = "Continue"
$null = gh release view $Version 2>$null
$viewExit = $LASTEXITCODE
$ErrorActionPreference = $savedEap
if ($viewExit -eq 0) {
    throw "release $Version は既に存在します。Version を変更するか、既存 release を削除してください。"
}
#endregion

#region アップロード
$ghArgs = @(
    "release", "create", $Version, $apkItem.FullName,
    "--title", "$Version",
    "--notes", $Notes
)
if (-not $NoPrerelease) { $ghArgs += "--prerelease" }
Write-Host ("Uploading: {0}" -f ($ghArgs -join ' '))
& gh @ghArgs
if ($LASTEXITCODE -ne 0) { throw "gh release create が失敗しました" }
Write-Host "Released: $Version"
#endregion
