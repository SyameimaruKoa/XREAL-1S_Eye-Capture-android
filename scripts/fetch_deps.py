"""Fetch all build dependencies into .cache (idempotent, standard library only).

CI and local builds call this before build.ps1. Material/AndroidX dependencies are
resolved recursively (same logic that produced the checked-in SELECTED.txt), then
kotlin-stdlib and the media3 muxer stack are added.

Usage:
    python scripts/fetch_deps.py
"""
import os
import subprocess
import sys
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCRIPTS = os.path.join(ROOT, "scripts")
MEDIA_DIR = os.path.join(ROOT, ".cache", "media3-libs")
GOOGLE = "https://dl.google.com/dl/android/maven2"
CENTRAL = "https://repo1.maven.org/maven2"

MEDIA3 = [
    (GOOGLE, "androidx.media3", "media3-muxer", "1.11.1", "aar", "media3-muxer.aar"),
    (GOOGLE, "androidx.media3", "media3-common", "1.11.1", "aar", "media3-common.aar"),
    (GOOGLE, "androidx.media3", "media3-container", "1.11.1", "aar", "media3-container.aar"),
    (GOOGLE, "androidx.annotation", "annotation", "1.8.1", "jar", "annotation.jar"),
    (CENTRAL, "com.google.guava", "guava", "33.4.8-android", "jar", "guava.jar"),
]
KOTLIN_STDLIB = [
    ("org.jetbrains.kotlin", "kotlin-stdlib", "1.9.24", "jar"),
    ("org.jetbrains.kotlin", "kotlin-stdlib-common", "1.9.24", "jar"),
]
ISO_PARSER = [
    (CENTRAL, "org.mp4parser", "isoparser", "1.9.56", "jar", "isoparser.jar"),
    (CENTRAL, "org.mp4parser", "muxer", "1.9.56", "jar", "muxer.jar"),
    (CENTRAL, "org.slf4j", "slf4j-api", "1.7.36", "jar", "slf4j-api.jar"),
    (CENTRAL, "org.aspectj", "aspectjrt", "1.9.7", "jar", "aspectjrt.jar"),
]


def maven_url(base, group, artifact, version, ext):
    return "%s/%s/%s/%s/%s-%s.%s" % (
        base, group.replace(".", "/"), artifact, version, artifact, version, ext)


def download(url, dest):
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        return False
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    tmp = dest + ".part"
    urllib.request.urlretrieve(url, tmp)
    os.replace(tmp, dest)
    return True


def main():
    py = sys.executable
    print("[1/4] Resolving Material/AndroidX dependencies...")
    subprocess.run([py, os.path.join(SCRIPTS, "fetch_material_deps.py")], check=True)
    print("[2/4] Selecting newest artifacts...")
    subprocess.run([py, os.path.join(SCRIPTS, "sort_material_deps.py")], check=True)
    print("[3/4] Fetching media3 muxer stack...")
    for base, group, artifact, version, ext, name in MEDIA3:
        url = maven_url(base, group, artifact, version, ext)
        dest = os.path.join(MEDIA_DIR, name)
        if download(url, dest):
            print("  media3: %s" % name)
    print("[4/4] Fetching Kotlin stdlib...")
    # build.ps1 reads .cache/m3-libs/SELECTED.txt; scripts/m3-libs/SELECTED.txt
    # is the checked-in copy. Both must list the Kotlin jars.
    selected_paths = [
        os.path.join(ROOT, ".cache", "m3-libs", "SELECTED.txt"),
        os.path.join(ROOT, "scripts", "m3-libs", "SELECTED.txt"),
    ]
    for group, artifact, version, ext in KOTLIN_STDLIB:
        url = maven_url(CENTRAL, group, artifact, version, ext)
        name = "%s-%s.%s" % (artifact, version, ext)
        dest = os.path.join(ROOT, ".cache", "m3-libs", name)
        if download(url, dest):
            print("  kotlin: %s" % name)
    for selected in selected_paths:
        if not os.path.exists(selected):
            continue
        with open(selected, encoding="utf-8") as handle:
            lines = [line.rstrip("\n") for line in handle if line.strip()]
        for _, artifact, version, ext in KOTLIN_STDLIB:
            name = "%s-%s.%s" % (artifact, version, ext)
            if name not in lines:
                lines.append(name)
        with open(selected, "w", encoding="utf-8", newline="\n") as handle:
            handle.write("\n".join(lines) + "\n")
    print("[5/5] Fetching isoparser for MP4 telemetry embedding...")
    for base, group, artifact, version, ext, name in ISO_PARSER:
        url = maven_url(base, group, artifact, version, ext)
        dest = os.path.join(MEDIA_DIR, name)
        if download(url, dest):
            print("  isoparser: %s" % name)
    print("Done. Dependencies are in .cache/")


if __name__ == "__main__":
    sys.exit(main())
