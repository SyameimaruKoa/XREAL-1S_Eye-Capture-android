"""Select newest version of each artifact from downloaded m3-libs."""
import os, re
from collections import defaultdict

D = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), ".cache", "m3-libs")
files = [f for f in os.listdir(D) if f.endswith((".aar", ".jar"))]

def parse(name):
    # artifact-version.ext
    base = os.path.splitext(name)[0]
    m = re.match(r"^(.*)-(\d[\d.]*(?:-[a-z0-9]+)?)$", base)
    if not m:
        return (base, "0")
    return (m.group(1), m.group(2))

def vkey(v):
    return tuple(int(x) if x.isdigit() else 0 for x in re.split(r"[.\-]", v) if x)

by_art = defaultdict(list)
for f in files:
    a, v = parse(f)
    by_art[a].append((v, f))

selected = []
for a, versions in sorted(by_art.items()):
    versions.sort(key=lambda x: vkey(x[0]))
    v, f = versions[-1]
    selected.append(f)
    # remove older
    for ov, of in versions[:-1]:
        try:
            os.remove(os.path.join(D, of))
        except OSError:
            pass

sel_path = os.path.join(D, "SELECTED.txt")
with open(sel_path, "w", encoding="utf-8") as fh:
    for f in sorted(selected):
        fh.write(f + "\n")

print(f"Selected {len(selected)} artifacts (removed {len(files)-len(selected)} older duplicates)")
for f in sorted(selected):
    sz = os.path.getsize(os.path.join(D, f)) // 1024
    print(f"  {f} ({sz}KB)")
