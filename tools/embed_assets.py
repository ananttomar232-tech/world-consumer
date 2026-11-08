#!/usr/bin/env python3
"""Embed the small binary assets (Gradle wrapper + credits) into build_mod.py.

build_mod.py stays a single self-contained script, so these files from assets/ are stored inside it
(zlib + base64, 100 chars per line, sha256-verified at generation time) between the markers

    # <<<EMBEDDED_ASSETS_BEGIN>>>   ...   # <<<EMBEDDED_ASSETS_END>>>

The 3D models are NOT embedded: they stay untouched as files in assets/models/ and are copied
byte-for-byte into the generated workspace.

Re-run after changing the wrapper files or assets/CREDITS.txt:
    python3 tools/embed_assets.py
"""
import base64
import hashlib
import os
import sys
import zlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TARGET = os.path.join(ROOT, "build_mod.py")
BEGIN = "# <<<EMBEDDED_ASSETS_BEGIN>>>\n"
END = "# <<<EMBEDDED_ASSETS_END>>>"

# key used by build_mod.py -> file under assets/
ASSETS = [
    ("wrapper/gradle-wrapper.jar", "wrapper/gradle-wrapper.jar"),
    ("wrapper/gradlew", "wrapper/gradlew"),
    ("wrapper/gradlew.bat", "wrapper/gradlew.bat"),
    ("CREDITS.txt", "CREDITS.txt"),
]


def block():
    lines = ["EMBEDDED_ASSETS = {"]
    total = 0
    for key, rel in ASSETS:
        with open(os.path.join(ROOT, "assets", rel), "rb") as fh:
            raw = fh.read()
        b64 = base64.b64encode(zlib.compress(raw, 9)).decode("ascii")
        total += len(b64)
        lines.append("    %r: (%r, [" % (key, hashlib.sha256(raw).hexdigest()))
        for i in range(0, len(b64), 100):
            lines.append("        %r," % b64[i:i + 100])
        lines.append("    ]),")
    lines.append("}")
    print("embedding %d assets (%d KiB of base64)" % (len(ASSETS), total // 1024))
    return "\n".join(lines) + "\n"


def main():
    with open(TARGET, encoding="utf-8") as fh:
        src = fh.read()
    a, b = src.find(BEGIN), src.find(END)
    if a < 0 or b < 0 or b < a:
        sys.exit("markers not found in build_mod.py")
    new = src[:a + len(BEGIN)] + block() + src[b:]
    with open(TARGET, "w", encoding="utf-8") as fh:
        fh.write(new)
    print("updated", TARGET)


if __name__ == "__main__":
    main()
