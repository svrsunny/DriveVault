# Changelog

## 1.8.0 (2026-11-10)
- **Android: the APK is signed with a new release key.** Uninstall DriveVault 1.7 or older before installing 1.8.0, then set up "Trust this phone" again if you used it (your vault on the drive is not affected)
- Stronger password protection: Argon2id (64 MiB, 3 passes) for new vaults and every password change or reset. Older vaults open as before and upgrade on the next password change; after that, DriveVault 1.7 and older can't open them (the app warns first)
- Grid view with photo thumbnails (desktop and Android); thumbnails are made in memory only and dropped on lock
- Android: a drive that isn't ready (or can't be read) shows "Drive not ready / Retry" and never offers "Create vault"
- Android: "Pick the drive with the system picker" for phones where "All files access" can't see USB drives
- Android: smoother drag-to-reorder (rows slide, the list scrolls at the edges); auto-lock choice on the lock screen
- Desktop: a vault can only be open in one DriveVault window at a time; leftovers of interrupted writes are cleaned up on unlock
- Clear error messages ("drive removed", "no space", "read-only drive", "DriveVault is not running") instead of raw errors
- Keys are wiped from memory on lock (best effort)
- Launchers install Argon2 support when the system's encryption library is too old
- Tests: `python tests/run_all.py` runs core, server API, browser UI (Playwright), Python ⇄ Kotlin compatibility and Android tests
- Demo vault with fictional files (`python tools/demo.py`); user and developer documentation in `docs/`

## 1.7.1
- Linux launcher works on any distro (`open-vault-linux.sh`: apt, dnf, pacman, zypper, apk or pip)
- Shorter documentation; screenshots moved to `assets/screenshots/`

## 1.7.0 (2026-10-30)
- NEW: DriveVault for Android (DriveVault-Android.apk): opens the same vault from a USB-C/OTG drive
- Trusted phone recovery: reset a forgotten password from the trusted phone (fingerprint / PIN)
- Desktop shows / removes the trusted phone in Security; history labels for phone actions

## 1.6.0 (2026-10-30)
- "Sort: Custom order": drag files up/down to arrange them; folders stay on top; order is saved (encrypted) per folder and used by the viewer. Top / Up / Down / Bottom buttons, Ctrl+Up/Down.
- In custom order, dragging never moves items into folders; new "Move to..." button picks a folder
- Home button (top-left) + Alt+Space / Ctrl+Home to jump to the top of the vault from anywhere, including Trash and History; clicking "DriveVault" also goes home
- Sort choice is remembered
- Trash still keeps items until you delete them (no automatic clean-up)

## 1.5.0 (2026-10-30)
- Viewer: zoom in/out, Fit, 1:1, mouse-wheel zoom, drag to pan, double-click zoom (photos); zoom for PDF, Word, text and CSV; full screen button (F key)
- Ctrl+V pastes files/folders copied in Explorer/Files straight into the vault (encrypted)
- Ctrl+C copies selected vault items so they can be pasted in Explorer/Files
- Up/Down arrows move, Shift+Up/Down selects a range, Space toggles, Enter opens; click/Ctrl/Shift-click selection
- Back / Forward / Up buttons (also Alt+Left/Right/Up and mouse back/forward buttons)

## 1.4.2 (2026-10-30)
- Media playback: prefers mpv when installed, bigger network cache for VLC
- "Use local copy" button after streaming, for players/files that misbehave when streamed

## 1.4.1 (2026-10-30)
- Finds the existing vault automatically after an update, even if the zip was extracted into a new folder
- Unlock/create screen offers any existing vault it finds instead of a new one
- Drive picker: open an existing vault by typing its folder path

## 1.4.0 (2026-10-30)
- Big files: working copies go to the real disk instead of /tmp (fixes "Disk quota exceeded")
- Free-space check before decrypting or copying
- Videos/music stream straight into VLC, nothing decrypted to disk
- MKV preview attempt in the browser
- Version number shown in the app

## 1.3.0

- Progress bars with %, speed and time left; History (encrypted audit trail); plain activity.log; Copy to computer
## 1.2.0

- Viewer next/previous + arrow keys; Trash; recovery key + security questions; search; multi-select; Enter-key fix
## 1.1.0

- Drive picker showing which drive gets the vault
## 1.0.0

- First release: encrypted vault, local web interface for Windows and Linux
