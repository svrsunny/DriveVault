# Privacy

DriveVault has no accounts, no servers, no analytics and no telemetry. Your files never leave your devices unless you
copy them somewhere yourself.

## What is stored, and where

| What | Where | Readable without the password? |
|---|---|---|
| Your files, their names and folders | `DriveVault/vault/` on the drive | No (encrypted) |
| History of actions (what, when, which computer and user) | `DriveVault/vault/history.dvl` | No (encrypted) |
| Plain activity log | `DriveVault/activity.log` and `activity.1.log` on the drive | **Yes.** Time, computer name, operating system, user name and the action. File and folder names only if you turn that on (History → "Include file and folder names in the plain-text log"; off by default). Failed unlock attempts are recorded here. |
| Password and recovery settings | `DriveVault/vault/vault.json` | Partly: KDF settings, the wrapped (encrypted) keys and the **text of your security questions** are readable; the answers and keys are not |
| Working copies from "Open in app" / "Show in folder" | `%LOCALAPPDATA%\DriveVault\work` on Windows, `~/.cache/drivevault/work` on Linux (or `$XDG_CACHE_HOME`), the app's private cache on Android | Yes, while the vault is unlocked. Overwritten and deleted on lock |
| "Copy to computer" / "To phone" | Your Downloads folder | Yes. These are normal files you asked for; DriveVault doesn't delete them |
| Thumbnails | Memory only | Not stored |
| Desktop settings (sort order, view) | The browser's local storage for the local DriveVault page | Yes; contains no file names |
| Android settings (last drive, sort, auto-lock, trusted-phone key) | The app's private storage; the trusted-phone key is protected by the Android Keystore and the screen lock | Not by other apps |

## Network

- The desktop app opens a web server on `127.0.0.1` (this computer only) and makes no connections to the internet.
- The launchers (`Open Vault (Windows).bat`, `open-vault-linux.sh`) may download the `cryptography` / Argon2 Python
  packages from PyPI or your Linux distribution's repositories the first time they run.
- The Android app has no internet permission.

## Sharing from Android

"Open with" and "Share" give the other app a temporary link that reads the decrypted file directly from the vault. The
other app can do whatever it likes with what it reads (for example save a copy). Links stop working when you lock.
