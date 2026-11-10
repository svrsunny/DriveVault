# Troubleshooting

## Starting DriveVault

| You see | What to do |
|---|---|
| Windows: "Python 3 is needed" | Install Python 3 from python.org and tick **Add python.exe to PATH**, then start `Open Vault (Windows).bat` again. |
| Windows: "Could not install the cryptography package" | The first start needs the internet once. Connect and try again. |
| Linux: `Permission denied` or nothing happens when double-clicking | Many systems don't run programs from USB drives. Open a terminal in the drive's folder and run `sh open-vault-linux.sh`. |
| "This vault uses Argon2id. Run the DriveVault launcher again…" | The computer's Python packages are too old for this vault. Start the vault with the launcher (not `python drivevault.py`) so it can install Argon2 support, or run `pip install --user --upgrade cryptography`. |
| "This vault was made by a newer DriveVault" | Update DriveVault on this drive or phone. |
| "This vault is already open in another DriveVault window" | Lock it in the other window (or close that window) first. |
| The browser shows "DriveVault is not running any more" | The console window was closed. Start DriveVault again from the drive. |
| The page says "Open DriveVault from the launcher" | Use the link that DriveVault opened; bookmarks of the local page don't work because each start uses a new address and token. |

## Unlocking

| You see | What to do |
|---|---|
| "Wrong password" | Check Caps Lock and the keyboard layout. Use **Forgot password?** with the recovery key, security questions or trusted phone. |
| "That recovery key is not right" | The key has 25 letters and digits; spaces, dashes and lower case don't matter. Check for O/0 and I/1 mix-ups (the key never contains 0, 1, O or I). |
| "One or more answers are not right" | Capitals, spaces and punctuation don't matter, but the words do. |
| Unlocking takes several seconds on the phone | Expected on slower phones: the password protection deliberately uses a lot of memory and time. |
| "This drive is … Android phones can't read it" | A warning only. Reformat the drive as exFAT (after copying the vault somewhere) if you want to use it on a phone. |

## While using the vault

| You see | What to do |
|---|---|
| "The drive was removed or stopped responding" | Plug the drive back in, then lock and unlock. If files were open in other apps, they are offered at the next unlock. |
| "Out of space" / "Not enough space" | Free space on the drive (or on the computer for working copies and "Copy to computer"). |
| "The drive is read-only" | Check the drive's write-protect switch, or unplug and replug it. On Linux, a drive that had errors may be mounted read-only; check it with your disk utility. |
| "Decrypted copies from a previous session were left on this computer" | DriveVault stopped without locking. **Save them back** keeps your edits; **Delete them** discards them. |
| A video doesn't play or seek in the browser | Use **Open in app** with VLC or mpv installed. If streaming misbehaves, choose **Use local copy**. |
| Ctrl+C / Ctrl+V with the file manager doesn't work on Linux | Install `wl-clipboard` (Wayland) or `xclip` (X11). |
| History says entries are missing or were altered | Someone or something edited or cut `history.dvl`. Your files are not affected; the warning stays so you know. |
| "Vault index is missing or damaged" | DriveVault already tried the backup copy (`index.dvx.bak`). Restore the `DriveVault/vault` folder from a backup. |

## Android

| You see | What to do |
|---|---|
| The USB drive isn't listed | Check the cable or OTG adapter and that *OTG* is on (some phones have a setting). Tap refresh. Still missing: **Drive not listed? Pick it with the system picker**. |
| "Drive not ready" | The phone is still mounting the drive, or DriveVault has no access. Wait a few seconds and tap **Retry**. If it keeps failing, tap **Change** and use the system picker. |
| "Android no longer lets DriveVault use this drive" | The folder access was removed. Lock, tap **Change** and pick the drive again. |
| "App not installed" / "package conflicts with an existing package" | An app signed with a different key is installed (for example 1.7 or a self-built copy). Uninstall it first; see [INSTALLATION.md → Updating](INSTALLATION.md#updating). |
| "This phone is not the trusted phone for this vault" | The trusted-phone key was removed (for example by uninstalling the app). Use another recovery method, then set up *Trust this phone* again. |
