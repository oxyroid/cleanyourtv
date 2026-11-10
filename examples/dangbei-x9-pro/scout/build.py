#!/usr/bin/env python3
"""Build DBOS Scout (aapt2 + javac + d8 + zipalign + apksigner, no Gradle).

Run from the repository root:   python3 examples/dangbei-x9-pro/scout/build.py
Or from the scout folder:       python3 build.py

Output: out/DBOSScout.apk next to this script, signed with out/debug.keystore
(a fresh one is generated when missing), and copied to dist/DBOSScout.apk.

Needs the Android SDK: build-tools 34 or newer and the android-34 (or 35)
platform jar. Override the defaults with SCOUT_BT and SCOUT_PLATFORM.
"""
import os
import shutil
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
OUT = HERE / "out"
BT = Path(os.environ.get("SCOUT_BT",
                         os.path.expanduser("~/Library/Android/sdk/build-tools/36.0.0")))
PLATFORM = Path(os.environ.get(
    "SCOUT_PLATFORM",
    os.path.expanduser("~/Library/Android/sdk/platforms/android-34/android.jar")))

if not PLATFORM.is_file():
    PLATFORM = Path(os.path.expanduser(
        "~/Library/Android/sdk/platforms/android-35/android.jar"))
if not PLATFORM.is_file():
    sys.exit("no Android platform jar found: set SCOUT_PLATFORM to android.jar")
if not (BT / "aapt2").is_file():
    sys.exit("no build-tools found: set SCOUT_BT to the build-tools folder")


def run(*cmd, cwd=None):
    print("+", " ".join(str(c) for c in cmd))
    subprocess.run([str(c) for c in cmd], check=True, cwd=cwd)


shutil.rmtree(OUT, ignore_errors=True)
if not (OUT / "gen").is_dir():
    (OUT / "gen").mkdir(parents=True)

print("[1/5] aapt2 compile + link")
run(BT / "aapt2", "compile", "--dir", HERE / "res", "-o", OUT / "res.zip")
run(BT / "aapt2", "link", "-o", OUT / "base.apk", "-I", PLATFORM,
    "--manifest", HERE / "AndroidManifest.xml", "--java", OUT / "gen",
    OUT / "res.zip")

print("[2/5] javac")
sources = sorted((HERE / "src").rglob("*.java"))
run("javac", "-source", "8", "-target", "8", "-nowarn",
    "-bootclasspath", PLATFORM, "-classpath", PLATFORM,
    "-d", OUT / "classes", *sources, OUT / "gen" / "com" / "dbos" / "scout" / "R.java")

print("[3/5] d8")
classes = sorted((OUT / "classes").rglob("*.class"))
run(BT / "d8", "--release", "--min-api", "26", "--lib", PLATFORM,
    "--output", OUT, *classes)

print("[4/5] package + zipalign")
shutil.copy(OUT / "base.apk", OUT / "unsigned.apk")
run("zip", "-q", "-j", "unsigned.apk", "classes.dex", cwd=OUT)
run(BT / "zipalign", "-f", "4", OUT / "unsigned.apk", OUT / "aligned.apk")

print("[5/5] sign")
# The keystore lives beside the sources, not in out/ (wiped every build), so every build
# signs with the same key and `adb install -r` upgrades in place. Committed on purpose:
# it is the standard Android debug key (store/key pass "android"), and without it a
# rebuilt Scout cannot install over the one already on the TV.
keystore = HERE / "debug.keystore"
if not keystore.is_file():
    run("keytool", "-genkeypair", "-keystore", keystore, "-alias", "androiddebugkey",
        "-storepass", "android", "-keypass", "android", "-keyalg", "RSA",
        "-keysize", "2048", "-validity", "10000",
        "-dname", "CN=Android Debug,O=Android,C=US")
run(BT / "apksigner", "sign", "--ks", keystore, "--ks-pass", "pass:android",
    "--key-pass", "pass:android", "--out", OUT / "DBOSScout.apk", OUT / "aligned.apk")
run(BT / "apksigner", "verify", OUT / "DBOSScout.apk")

if not (HERE / "dist").is_dir():
    (HERE / "dist").mkdir()
shutil.copy(OUT / "DBOSScout.apk", HERE / "dist" / "DBOSScout.apk")
print(f"OK: {(OUT / 'DBOSScout.apk').stat().st_size} bytes -> out/ and dist/")
