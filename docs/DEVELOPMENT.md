# Development

## Tools

| For | You need |
|---|---|
| Desktop app and its tests | Python 3 (tested with 3.12), `pip install cryptography` (44 or newer for Argon2id) |
| Browser UI tests | `pip install playwright` and `python -m playwright install chromium` |
| Android app and Kotlin tests | **JDK 21** (the `core` module uses a Java 21 toolchain), the Android SDK (platform 35). Gradle is downloaded by the wrapper. |

Tell Gradle where the Android SDK is, either with the `ANDROID_HOME` environment variable or a git-ignored
`android/local.properties` (see `android/local.properties.example`):

```
sdk.dir=C\:\\Users\\you\\AppData\\Local\\Android\\Sdk
```

## Run the desktop app from the source tree

```bash
python desktop/app/drivevault.py --vault /path/to/test-folder/DriveVault/vault
```

Always pass `--vault` when developing. Without it, DriveVault looks for an existing vault next to the app and on your
drives, and could open your real one.

## Demo data and screenshots

`tools/demo.py` builds a vault full of fictional files (generated photos, notes, a budget CSV, a Word file, history) and
serves it with a fictional computer name, user and drive, so nothing about your machine appears in the app.

```bash
python tools/demo.py              # creates the demo on first run, opens it in the browser (password: demo password)
python tools/demo.py --reset      # delete the demo vault and build it again
python tools/demo.py --dir PATH   # keep the demo in another folder (default: <temp>/drivevault-demo)
```

The demo refuses to use a folder that isn't empty and isn't a demo folder. To remove it, delete the demo folder.

`python tools/screenshots.py` starts its own demo in a temporary folder, drives the real app with Playwright and writes
`assets/screenshots/*.png`. Look at every image before committing it. Android screenshots are not automated: the app
blocks screenshots (`FLAG_SECURE`).

## Tests

```bash
python tests/run_all.py           # everything
python tests/run_all.py --quick   # without the Kotlin / Android parts
```

| Test | Covers |
|---|---|
| `tests/test_vault.py` | Vault core: encryption round trips, range reads, tamper and truncation detection, trash, custom order, history, recovery, Argon2id upgrade, key wiping, single-instance lock, leftover clean-up |
| `tests/test_api.py` | The local server: cookie and `Host` checks, upload, range requests, trash, recovery, password change, "Open in app" write-back, recovery of left-over copies, plain log, error responses |
| `tests/test_ui.py` | The desktop UI in Chromium with Playwright: create, upload, browse, search, sort, custom order drag, multi-select, trash, viewer, download, Copy to computer, Open in app, clipboard, drag and drop, grid view, history, security, lock / unlock, password reset. `--headed` shows the browser. |
| Python ⇄ Kotlin compatibility | `make_compat_vault.py` writes vaults with Python, `CompatTest.kt` reads and changes them with Kotlin (`gradlew :core:test --rerun -Ddv.testdir=…`), `check_compat_vault.py` reads Kotlin's result with Python |
| `android/app/src/test` | Robolectric UI flow tests: create, add files, browse, viewer, trash, custom order, move, history, security, grid view, lock, drive-not-ready |

The browser tests and the server tests run the app in-process on a temporary vault: "Open in app", the clipboard and the
Downloads folder are redirected, so nothing on your computer is opened or changed.

`:core:test` needs `--rerun` when you call it by hand, because Gradle otherwise reports it up to date and skips Kotlin's
part of the compatibility test.

## Build

```bash
cd android
./gradlew :app:assembleDebug      # debug APK: app/build/outputs/apk/debug/app-debug.apk
```

The desktop app needs no build: `desktop/` is what goes on the drive. The Android build copies `desktop/` into the APK so
the phone can put the desktop app on a new drive. Release builds: [RELEASING.md](RELEASING.md).

## Changing the vault format

Read [FORMAT.md](FORMAT.md) and the rules in [CONTRIBUTING.md](../CONTRIBUTING.md#rules-that-keep-vaults-safe): changes
must be additive, and the spec, both implementations and the compatibility test change together.
