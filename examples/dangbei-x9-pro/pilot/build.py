#!/usr/bin/env python3
"""DBOS Pilot build — aapt2 + javac + d8 + zipalign + apksigner.

Mirrors the repo's no-Gradle philosophy: the Android SDK is the only
dependency. Outputs out/DBOSPilot.apk and dist/DBOSPilot.apk.

Environment overrides:
  SCOUT_BT        build-tools directory (default: ~/Library/Android/sdk/build-tools/36.0.0)
  SCOUT_PLATFORM  platform jar (default: android-34, falling back to android-35)
"""
import os
import shutil
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
HOME = Path.home()
BT = Path(os.environ.get("SCOUT_BT", HOME / "Library/Android/sdk/build-tools/36.0.0"))
PLATFORM = Path(os.environ.get(
    "SCOUT_PLATFORM",
    next(p for p in [
        HOME / "Library/Android/sdk/platforms/android-34/android.jar",
        HOME / "Library/Android/sdk/platforms/android-35/android.jar",
    ] if p.exists())))

OUT = HERE / "out"
DIST = HERE / "dist"
KEYSTORE = HERE / "debug.keystore"


def run(*cmd):
    print("+", " ".join(str(c) for c in cmd))
    subprocess.run([str(c) for c in cmd], check=True)


def main():
    for d in (OUT / "obj", OUT / "gen", DIST):
        # exists() guard, not mkdir(exist_ok=True): sandboxed environments in
        # this workspace intercept the mkdir syscall even with exist_ok set.
        if not d.is_dir():
            d.mkdir(parents=True)

    print("[1/5] aapt2 compile + link")
    run(BT / "aapt2", "compile", "--dir", HERE / "res", "-o", OUT / "res.zip")
    run(BT / "aapt2", "link", "-o", OUT / "base.apk",
        "-I", PLATFORM, "--manifest", HERE / "AndroidManifest.xml",
        "--java", OUT / "gen", OUT / "res.zip")

    print("[2/5] javac")
    srcs = sorted(HERE.glob("src/**/*.java")) + [OUT / "gen/com/dbos/pilot/R.java"]
    run("javac", "-source", "8", "-target", "8", "-nowarn",
        "-bootclasspath", PLATFORM, "-classpath", PLATFORM,
        "-d", OUT / "classes", *srcs)

    print("[3/5] d8")
    classes = sorted(OUT.glob("classes/**/*.class"))
    run(BT / "d8", "--release", "--min-api", "26", "--lib", PLATFORM,
        "--output", OUT, *classes)

    print("[4/5] package + zipalign")
    unsigned = OUT / "pilot-unsigned.apk"
    shutil.copyfile(OUT / "base.apk", unsigned)
    subprocess.run(["zip", "-q", "-j", str(unsigned), str(OUT / "classes.dex")], check=True)
    run(BT / "zipalign", "-f", "4", unsigned, OUT / "pilot-aligned.apk")

    print("[5/5] sign")
    apk = OUT / "DBOSPilot.apk"
    run(BT / "apksigner", "sign", "--ks", KEYSTORE, "--ks-pass", "pass:android",
        "--key-pass", "pass:android", "--out", apk, OUT / "pilot-aligned.apk")
    run(BT / "apksigner", "verify", apk)

    if not DIST.is_dir():
        DIST.mkdir(parents=True)
    shutil.copyfile(apk, DIST / "DBOSPilot.apk")
    print("OK:", apk.stat().st_size, "bytes -> out/ and dist/")


if __name__ == "__main__":
    sys.exit(main())
