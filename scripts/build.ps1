$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$sdk = $env:ANDROID_SDK_ROOT
if (-not $sdk) { $sdk = $env:ANDROID_HOME }
if (-not $sdk) { $sdk = "C:\Users\kouki\AppData\Local\Android\Sdk" }
$bt = Join-Path $sdk "build-tools\35.0.0"
$platform = Join-Path $sdk "platforms\android-35\android.jar"
$jdk = $env:JAVA_HOME
if (-not $jdk) { $jdk = "C:\Program Files\Java\jdk-21.0.12" }
$jdkBin = Join-Path $jdk "bin"
$env:JAVA_HOME = $jdk
$env:PATH = "$jdkBin;$env:PATH"

$build = Join-Path $root "build"
Remove-Item -Recurse -Force $build -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force (Join-Path $build "classes") | Out-Null
New-Item -ItemType Directory -Force (Join-Path $build "dex") | Out-Null

& (Join-Path $bt "aapt2.exe") link `
    -o (Join-Path $build "base.apk") `
    -I $platform `
    --manifest (Join-Path $root "app\AndroidManifest.xml") `
    --min-sdk-version 31 `
    --target-sdk-version 35
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

$sources = Get-ChildItem (Join-Path $root "app\src") -Recurse -Filter *.java |
    ForEach-Object { $_.FullName }
& (Join-Path $jdkBin "javac.exe") --release 8 -cp $platform -d (Join-Path $build "classes") $sources
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

$classes = Get-ChildItem (Join-Path $build "classes") -Recurse -Filter *.class |
    ForEach-Object { $_.FullName }
& (Join-Path $bt "d8.bat") --lib $platform --min-api 31 `
    --output (Join-Path $build "dex") $classes
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

& (Join-Path $jdkBin "jar.exe") uf (Join-Path $build "base.apk") `
    -C (Join-Path $build "dex") classes.dex
if ($LASTEXITCODE -ne 0) { throw "jar failed" }

& (Join-Path $bt "zipalign.exe") -f 4 `
    (Join-Path $build "base.apk") (Join-Path $build "aligned.apk")
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

$keystore = $env:KEYSTORE
if (-not $keystore) {
    $keystore = Join-Path $root ".secrets\\local.keystore"
    if (-not (Test-Path $keystore)) {
        New-Item -ItemType Directory -Force (Split-Path -Parent $keystore) | Out-Null
        & (Join-Path $jdkBin "keytool.exe") -genkeypair -noprompt `
            -keystore $keystore -alias xreal `
            -storepass xrealrecorder -keypass xrealrecorder `
            -keyalg RSA -keysize 2048 -validity 10000 `
            -dname "CN=XREAL Recorder, OU=Dev, O=SyameimaruKoa, C=JP"
        if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
    }
    $env:KEYSTORE_PASSWORD = "xrealrecorder"
    $env:KEY_PASSWORD = "xrealrecorder"
    $env:KEY_ALIAS = "xreal"
}

& (Join-Path $bt "apksigner.bat") sign `
    --ks $keystore `
    --ks-pass "pass:$env:KEYSTORE_PASSWORD" `
    --key-pass "pass:$env:KEY_PASSWORD" `
    --ks-key-alias $env:KEY_ALIAS `
    --out (Join-Path $build "xreal-recorder.apk") `
    (Join-Path $build "aligned.apk")
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

Write-Host "APK: $(Join-Path $build 'xreal-recorder.apk')"
