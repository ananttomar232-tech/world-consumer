#!/usr/bin/env python3
"""Tesla Tycoon - one-command build.

    python3 build_tesla_tycoon.py                 # gradle build -> dist/tesla_tycoon-2.0.0.jar
    python3 build_tesla_tycoon.py --rebake        # first re-bake the six glTF models and regenerate the small resources
    python3 build_tesla_tycoon.py --test          # also run the headless game tests (needs eula; see below)

The Fabric 1.20.1 project lives in ./tesla_tycoon (open it in IntelliJ, or `cd tesla_tycoon && ./gradlew build`).
Everything the jar needs is already committed under tesla_tycoon/src (baked models, textures, banners), so a plain build needs
only a JDK (17 or newer) and internet access for Gradle/Fabric on the first run. --rebake additionally needs
`pip install numpy pillow meshoptimizer` and re-reads the original ZIPs in assets/tesla_tycoon/models.
"""
import argparse
import hashlib
import os
import shutil
import subprocess
import sys
import zipfile

ROOT = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.join(ROOT, "tesla_tycoon")
VERSION = "2.0.0"


def run(cmd, cwd=None, env=None):
    print("+", " ".join(cmd))
    r = subprocess.run(cmd, cwd=cwd, env=env)
    if r.returncode != 0:
        sys.exit("command failed with exit code %d" % r.returncode)


def java_major():
    try:
        out = subprocess.run(["java", "-version"], capture_output=True, text=True).stderr
        for tok in out.replace('"', " ").split():
            if tok[0].isdigit():
                return int(tok.split(".")[0]) if not tok.startswith("1.") else int(tok.split(".")[1])
    except OSError:
        pass
    return 0


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--rebake", action="store_true", help="re-bake the vehicle models and regenerate resources first")
    ap.add_argument("--test", action="store_true", help="run the headless game tests after the build")
    ap.add_argument("--gradle", help="gradle executable (default: the project's ./gradlew)")
    a = ap.parse_args()

    jv = java_major()
    if jv < 17:
        sys.exit("A JDK 17+ is required (found %s). Install one and put it on PATH / JAVA_HOME." % (jv or "none"))
    print("Java %d" % jv)

    if a.rebake:
        run([sys.executable, os.path.join(ROOT, "tools", "bake_models.py")])
        run([sys.executable, os.path.join(ROOT, "tools", "gen_assets.py")])

    gradle = a.gradle or os.path.join(PROJECT, "gradlew.bat" if os.name == "nt" else "gradlew")
    if not a.gradle and os.name != "nt":
        os.chmod(gradle, 0o755)
    run([gradle, "build", "--console=plain"], cwd=PROJECT)

    if a.test:
        os.makedirs(os.path.join(PROJECT, "build", "gametest"), exist_ok=True)
        with open(os.path.join(PROJECT, "build", "gametest", "eula.txt"), "w") as fh:
            fh.write("eula=true\n")          # running the test server means you accept the Minecraft EULA
        run([gradle, "runGametest", "--console=plain"], cwd=PROJECT)

    jar = os.path.join(PROJECT, "build", "libs", "tesla_tycoon-%s.jar" % VERSION)
    if not os.path.exists(jar):
        sys.exit("expected %s but it was not built" % jar)
    dist = os.path.join(ROOT, "dist")
    os.makedirs(dist, exist_ok=True)
    out = os.path.join(dist, "tesla_tycoon-%s.jar" % VERSION)
    shutil.copyfile(jar, out)
    with zipfile.ZipFile(out) as z:
        names = set(z.namelist())
    need = ["fabric.mod.json", "CREDITS.txt", "assets/tesla_tycoon/vehicles/l5_roadster.ttm", "assets/tesla_tycoon/textures/signboard/showroom_banner.png",
            "assets/tesla_tycoon/textures/signboard/city_billboard.png", "assets/tesla_tycoon/textures/signboard/wall_poster.png",
            "assets/tesla_tycoon/shaders/core/vehicle.fsh"]
    missing = [n for n in need if n not in names]
    if missing:
        sys.exit("the jar is missing: %s" % ", ".join(missing))
    sha = hashlib.sha256(open(out, "rb").read()).hexdigest()
    print("\nBuilt %s (%.1f MiB)\nsha256 %s\nInstall: Minecraft 1.20.1 + Fabric Loader 0.15+ + Fabric API; put the jar in .minecraft/mods; in a world type /tycoon start"
          % (out, os.path.getsize(out) / 1048576.0, sha))


if __name__ == "__main__":
    main()
