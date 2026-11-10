# Features

✓ = available, — = not available on that platform.

## Vault and security

| Feature | Desktop (Windows, Linux) | Android |
|---|---|---|
| Create a vault on a drive, unlock, lock | ✓ | ✓ |
| Same vault and password on every platform | ✓ | ✓ |
| Files, names and folders encrypted (AES-256-GCM), password protected with Argon2id | ✓ | ✓ |
| Change password (upgrades older vaults to Argon2id) | ✓ | ✓ |
| Recovery key, security questions | ✓ set up, use | ✓ set up, use |
| Trusted phone (reset a forgotten password with the phone's fingerprint / PIN) | shows / removes it | ✓ set up, use |
| Auto-lock | after 15 min without activity (paused while decrypted copies are open) | when the app is in the background: immediately, 1, 2 (default), 5 or 15 min; also on the lock screen |
| Encrypted, tamper-evident history; filters; export as CSV | ✓ | ✓ |
| Plain-text `activity.log` (file names off by default) | ✓ view, open | ✓ view |
| Only one DriveVault window at a time per vault | ✓ | — |
| Screenshots of the app blocked | — | ✓ |

## Files

| Feature | Desktop | Android |
|---|---|---|
| Drive picker that shows which drives already have a vault | ✓ | ✓ |
| Open a vault by typing its folder path | ✓ | ✓ |
| "Drive not ready / Retry" when a drive can't be read | ✓ (drive not connected) | ✓ |
| Pick the drive with Android's folder picker (when "All files access" can't see USB drives) | — | ✓ |
| Browse folders, breadcrumbs, back / forward / up / home | ✓ (Alt+←/→/↑, Alt+Space, Ctrl+Home, mouse back/forward) | ✓ |
| List view and grid view with photo thumbnails | ✓ | ✓ |
| Search the whole vault | ✓ | ✓ |
| New folder, rename, Move to… | ✓ (F2 renames) | ✓ |
| Trash with restore, delete forever, empty trash (no automatic clean-up) | ✓ | ✓ |
| Sort by name, size, date or custom order | ✓ | ✓ |
| Custom order by dragging (folders stay on top; dragging never moves into folders) | ✓ (also Top / Up / Down / Bottom, Ctrl+↑/↓) | ✓ list view (Move up / down in grid view) |
| Multi-select | ✓ checkbox, Shift / Ctrl click, Shift+↑/↓, Ctrl+A | ✓ long press, select all |
| Upload files and folders | ✓ buttons, drag and drop, Ctrl+V from the file manager | ✓ file / folder picker, "Share to DriveVault" from other apps |
| Copy out | ✓ Download, Copy to computer (to Downloads), Ctrl+C to the file manager | ✓ To phone (Downloads), Share |
| Open in another app, edits saved back | ✓ "Open in app" / "Show in folder": changes are encrypted back on lock; left-over copies after a crash are offered at the next unlock | ✓ "Open with" streams the file to the other app (read-only, no copy on the phone) |
| Progress with %, speed and time left | ✓ | ✓ |

## Viewer

| Type | Desktop | Android |
|---|---|---|
| Photos | ✓ zoom, 1:1, pan, full screen | ✓ pinch / buttons to zoom, swipe for next |
| Video, audio | ✓ in the browser; "Open in app" streams to VLC or mpv without a decrypted copy | ✓ in the app (Media3) |
| PDF | ✓ | ✓ (Android's PDF renderer) |
| Word (.docx), CSV, text | ✓ | ✓ |
| Next / previous | ✓ ← / →, buttons | ✓ |
