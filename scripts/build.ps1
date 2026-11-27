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

Add-Type -AssemblyName System.IO.Compression.FileSystem

$build = Join-Path $root "build"
Remove-Item -Recurse -Force $build -ErrorAction SilentlyContinue
$dirs = @("classes", "dex", "libs", "res-compiled", "gen", "res-extracted")
foreach ($d in $dirs) { New-Item -ItemType Directory -Force (Join-Path $build $d) | Out-Null }

# ---------------------------------------------------------------------------
# Collect dependencies: media3 (fMP4) + Material Components / AndroidX (M3).
# AARs contribute classes.jar (javac classpath + DEX) and res/ (aapt2 -R).
# ---------------------------------------------------------------------------
$mediaDir = Join-Path $root ".cache\media3-libs"
$m3Dir = Join-Path $root ".cache\m3-libs"
$libJars = @()
$resZips = @()
$libPackages = @()

# media3 muxer removed: FFmpeg (libgyroflow_inject.so) writes the container.
foreach ($name in @('annotation', 'guava', 'slf4j-api', 'aspectjrt')) {
    $jar = Join-Path $mediaDir "$name.jar"
    if (Test-Path $jar) { $libJars += $jar }
}

# Material Components / AndroidX: newest artifact per group from SELECTED.txt.
$selectedPath = Join-Path $m3Dir "SELECTED.txt"
if (-not (Test-Path $selectedPath)) { throw "Missing $selectedPath" }
foreach ($line in (Get-Content $selectedPath)) {
    $line = $line.Trim()
    if (-not $line) { continue }
    $file = Join-Path $m3Dir $line
    if (-not (Test-Path $file)) { throw "Missing Material dependency: $file" }

    if ($line.EndsWith('.aar')) {
        $name = $line -replace '\.aar$', ''
        $jar = Join-Path $build "libs\$name.jar"
        $zip = [System.IO.Compression.ZipFile]::OpenRead($file)

        # classes.jar -> javac classpath + DEX
        $ce = $zip.Entries | Where-Object { $_.FullName -eq 'classes.jar' } | Select-Object -First 1
        if ($null -ne $ce) {
            [System.IO.Compression.ZipFileExtensions]::ExtractToFile($ce, $jar, $true)
            $libJars += $jar
        }

        # AndroidManifest.xml package -> --extra-packages for R.java generation
        $me = $zip.Entries | Where-Object { $_.FullName -eq 'AndroidManifest.xml' } | Select-Object -First 1
        if ($null -ne $me) {
            $sr = New-Object System.IO.StreamReader($me.Open())
            $mtext = $sr.ReadToEnd(); $sr.Dispose()
            if ($mtext -match 'package="([^"]+)"') { $libPackages += $Matches[1] }
        }

        # res/ -> aapt2 compile. Skip pure-locale values-* folders: they add no
        # resources we need and aapt2 compile --dir merges locale configs first.
        $resDir = Join-Path $build "res-extracted\$name"
        New-Item -ItemType Directory -Force $resDir | Out-Null
        foreach ($e in $zip.Entries) {
            if (-not $e.FullName.StartsWith('res/')) { continue }
            if ($e.FullName.EndsWith('/')) { continue }
            $rel = $e.FullName.Substring(4)
            $first = $rel.Split('/')[0]
            if ($first -match '^values-([a-z]{2,3}(-r[A-Z]{2})?|b\+)') { continue }
            $dest = Join-Path $resDir ($rel -replace '/', '\')
            $parent = Split-Path -Parent $dest
            if (-not (Test-Path $parent)) { New-Item -ItemType Directory -Force $parent | Out-Null }
            [System.IO.Compression.ZipFileExtensions]::ExtractToFile($e, $dest, $true)
        }
        $zip.Dispose()

        $anyRes = Get-ChildItem $resDir -Recurse -File | Select-Object -First 1
        if ($null -ne $anyRes) {
            $zipOut = Join-Path $build "res-compiled\$name.zip"
            & (Join-Path $bt "aapt2.exe") compile --dir $resDir -o $zipOut
            if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed for $name" }
            $resZips += $zipOut
        }
    } else {
        # Plain jar (annotations, Kotlin stdlib helpers, etc.)
        $libJars += $file
    }
}

# Compile app resources (launcher icon) and add them to the link inputs.
$appResZip = Join-Path $build "res-compiled\app.zip"
& (Join-Path $bt "aapt2.exe") compile --dir (Join-Path $root "app\res") -o $appResZip
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed for app res" }
$resZips += $appResZip

# ---------------------------------------------------------------------------
# aapt2 link: merge app manifest + all AAR resources; generate R.java for the
# app package and every library package (so precompiled library bytecode can
# resolve its R fields at runtime with the final resource IDs).
# ---------------------------------------------------------------------------
$javaDir = Join-Path $build "gen"
$aaptArgs = @(
    "link", "-o", (Join-Path $build "base.apk"),
    "-I", $platform,
    "--manifest", (Join-Path $root "app\AndroidManifest.xml"),
    "--min-sdk-version", "31", "--target-sdk-version", "35",
    "--java", $javaDir, "--auto-add-overlay"
)
if ($libPackages.Count -gt 0) {
    $aaptArgs += @("--extra-packages", (($libPackages | Select-Object -Unique) -join ':'))
}
foreach ($z in $resZips) { $aaptArgs += @("-R", $z) }
$assetsDir = Join-Path $root "app\assets"
if (Test-Path $assetsDir) { $aaptArgs += @("-A", $assetsDir) }
& (Join-Path $bt "aapt2.exe") $aaptArgs
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

# ---------------------------------------------------------------------------
# javac: app sources + generated R.java files.
# ---------------------------------------------------------------------------
$sources = @(Get-ChildItem (Join-Path $root "app\src") -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$sources += @(Get-ChildItem $javaDir -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$classpath = ($libJars + @($platform)) -join ';'
$javacArgs = @("--release", "11", "-cp", $classpath, "-d", (Join-Path $build "classes")) + $sources
& (Join-Path $jdkBin "javac.exe") @javacArgs
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

# ---------------------------------------------------------------------------
# d8: app classes + all library classes. Response file avoids Windows' command
# line limit. --min-api 31 lets d8 emit multiple DEX files when needed.
# ---------------------------------------------------------------------------
$classes = @(Get-ChildItem (Join-Path $build "classes") -Recurse -Filter *.class | ForEach-Object { $_.FullName })
$dexInputs = @($classes) + $libJars
$argFile = Join-Path $build "d8-args.txt"
$argLines = @("--lib", $platform, "--min-api", "31", "--output", (Join-Path $build "dex")) + $dexInputs
[System.IO.File]::WriteAllLines($argFile, $argLines, (New-Object System.Text.UTF8Encoding($false)))
& (Join-Path $bt "d8.bat") "@$argFile"
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

# Add every generated dex file (classes.dex, classes2.dex, ...) to the APK.
$dexFiles = @(Get-ChildItem (Join-Path $build "dex") -Filter 'classes*.dex' | Sort-Object Name)
$jarArgs = @("uf", (Join-Path $build "base.apk"))
foreach ($d in $dexFiles) { $jarArgs += @("-C", (Join-Path $build "dex"), $d.Name) }
# Native libraries must land in lib/<abi>/ so Android extracts them to an
# executable app-nativeLibraryDir (filesDir/assets are mounted noexec).
$jniRoot = Join-Path $root "app\src\main\jniLibs"
if (Test-Path $jniRoot) {
    $nativeStage = Join-Path $build "native"
    foreach ($abi in @("arm64-v8a", "armeabi-v7a", "x86_64", "x86")) {
        $abiDir = Join-Path $jniRoot $abi
        if (Test-Path $abiDir) {
            $stageDir = Join-Path $nativeStage "lib\$abi"
            New-Item -ItemType Directory -Force $stageDir | Out-Null
            foreach ($so in Get-ChildItem $abiDir -Filter *.so) {
                Copy-Item $so.FullName (Join-Path $stageDir $so.Name) -Force
                $jarArgs += @("-C", $nativeStage, "lib/$abi/$($so.Name)")
            }
        }
    }
}
& (Join-Path $jdkBin "jar.exe") @jarArgs
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
