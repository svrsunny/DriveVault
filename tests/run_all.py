"""Runs every DriveVault test:  python tests/run_all.py  [--quick]  (--quick skips the Android/Gradle parts)
  1. vault core (Python)      2. server API      3. browser UI (Playwright)
  4. Python <-> Kotlin byte compatibility      5. Android app (Robolectric)"""
import os, shutil, subprocess, sys, tempfile, time

HERE = os.path.dirname(os.path.abspath(__file__))
ANDROID = os.path.join(HERE, "..", "android")
GRADLE = os.path.join(ANDROID, "gradlew.bat" if os.name == "nt" else "gradlew")
quick = "--quick" in sys.argv
results = []


def run(name, cmd, cwd=None):
    print(f"\n=== {name}", flush=True)
    t = time.time()
    code = subprocess.call(cmd, cwd=cwd)
    results.append((name, "PASS" if code == 0 else "FAIL", time.time() - t))
    return code == 0


def skip(name, why):
    print(f"\n=== {name}\n  skipped: {why}")
    results.append((name, "SKIP", 0))


py = [sys.executable]
run("vault core", py + [os.path.join(HERE, "test_vault.py")])
run("server API", py + [os.path.join(HERE, "test_api.py")])
try:
    import playwright  # noqa: F401
    run("browser UI", py + [os.path.join(HERE, "test_ui.py")])
except ImportError:
    skip("browser UI", "pip install playwright && python -m playwright install chromium")

if quick:
    skip("Python <-> Kotlin compatibility", "--quick"); skip("Android app", "--quick")
elif not (shutil.which("java") or os.environ.get("JAVA_HOME")):
    skip("Python <-> Kotlin compatibility", "needs JDK 17+"); skip("Android app", "needs JDK 17+ and the Android SDK")
else:
    d = tempfile.mkdtemp(prefix="dvcompat-")
    try:
        ok = run("compat: Python writes", py + [os.path.join(HERE, "make_compat_vault.py"), d])
        # --rerun: Gradle would otherwise skip the test as up-to-date and Kotlin would not touch the new vaults
        ok = ok and run("compat: Kotlin reads + writes", [GRADLE, ":core:test", "--rerun", f"-Ddv.testdir={d}", "--console=plain", "-q"], cwd=ANDROID)
        ok and run("compat: Python reads Kotlin's changes", py + [os.path.join(HERE, "check_compat_vault.py"), d, "kotlin pass 2"])
    finally:
        shutil.rmtree(d, ignore_errors=True)
    run("Android app", [GRADLE, ":app:testDebugUnitTest", "--console=plain", "-q"], cwd=ANDROID)

print("\n=== summary")
for name, res, sec in results:
    print(f"  {res:4}  {name}" + (f"  ({sec:.0f} s)" if sec else ""))
failed = [r for r in results if r[1] == "FAIL"]
print("ALL PASSED" if not failed else f"{len(failed)} FAILED")
sys.exit(1 if failed else 0)
