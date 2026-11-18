"""Recursively fetch Material Components 1.14.0 and its AAR dependencies from Google Maven."""
import os, sys, urllib.request, xml.etree.ElementTree as ET

BASE = "https://dl.google.com/dl/android/maven2"
OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), ".cache", "m3-libs")
os.makedirs(OUT, exist_ok=True)

NS = "{http://maven.apache.org/POM/4.0.0}"
visited = set()
downloaded = []


def get_pom(group, artifact, version):
    url = f"{BASE}/{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}.pom"
    try:
        with urllib.request.urlopen(url, timeout=30) as r:
            return r.read().decode("utf-8", "replace")
    except Exception as e:
        print(f"  pom fail {group}:{artifact}:{version}: {e}")
        return None


def resolve(group, artifact, version, depth=0):
    key = (group, artifact, version)
    if key in visited:
        return
    visited.add(key)
    # Download AAR (or JAR)
    name = f"{artifact}-{version}"
    for ext in ("aar", "jar"):
        url = f"{BASE}/{group.replace('.', '/')}/{artifact}/{version}/{name}.{ext}"
        try:
            with urllib.request.urlopen(url, timeout=60) as r:
                data = r.read()
            path = os.path.join(OUT, f"{name}.{ext}")
            with open(path, "wb") as f:
                f.write(data)
            downloaded.append((f"{group}:{artifact}:{version}", ext, len(data)))
            print(f"  [{len(downloaded):2d}] {group}:{artifact}:{version} ({ext}, {len(data)//1024}KB)")
            break
        except Exception:
            continue
    # Parse POM for dependencies
    if depth > 6:
        return
    pom = get_pom(group, artifact, version)
    if not pom:
        return
    try:
        root = ET.fromstring(pom)
    except Exception:
        return
    deps = root.find(f"{NS}dependencies")
    if deps is None:
        return
    for dep in deps.findall(f"{NS}dependency"):
        g = dep.findtext(f"{NS}groupId", "").strip()
        a = dep.findtext(f"{NS}artifactId", "").strip()
        v = dep.findtext(f"{NS}version", "").strip()
        scope = dep.findtext(f"{NS}scope", "").strip()
        if not g or not a or not v or scope == "import" or scope == "test":
            continue
        if not g.startswith(("androidx.", "com.google.android.material")):
            continue
        resolve(g, a, v, depth + 1)


print("Resolving Material Components 1.14.0 dependencies...")
resolve("com.google.android.material", "material", "1.14.0")
print(f"\nDone: {len(downloaded)} artifacts downloaded to {OUT}")
