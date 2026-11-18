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
    ("androidx.media3", "media3-muxer", "1.11.1", "aar", "media3-muxer.aar"),
    ("androidx.media3", "media3-common", "1.11.1", "aar", "media3-common.aar"),
    ("androidx.media3", "media3-container", "1.11.1", "aar", "media3-container.aar"),
    ("androidx.annotation", "annotation", "1.8.1", "jar", "annotation.jar"),
    ("com.google.guava", "guava", "33.4.8-android", "jar", "guava.jar"),
]
KOTLIN_STDLIB = [
    ("org.jetbrains.kotlin", "kotlin-stdlib", "1.9.24", "jar"),
    ("org.jetbrains.kotlin", "kotlin-stdlib-common", "1.9.24", "jar"),
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
    for group, artifact, version, ext, name in MEDIA3:
        url = maven_url(GOOGLE, group, artifact, version, ext)
        dest = os.path.join(MEDIA_DIR, name)
        if download(url, dest):
            print("  media3: %s" % name)
    print("[4/4] Fetching Kotlin stdlib...")
    for group, artifact, version, ext in KOTLIN_STDLIB:
        url = maven_url(CENTRAL, group, artifact, version, ext)
        dest = os.path.join(MEDIA_DIR, "..", "m3-libs", "%s-%s.%s" % (artifact, version, ext))
        dest = os.path.normpath(dest)
        if download(url, dest):
            print("  kotlin: %s-%s.%s" % (artifact, version, ext))
    print("Done. Dependencies are in .cache/")


if __name__ == "__main__":
    sys.exit(main())
