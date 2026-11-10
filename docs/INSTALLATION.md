# Installation

DriveVault has two parts: the **desktop app**, which lives on the USB drive and runs on Windows and Linux, and the
**Android app**. Both open the same vault. Downloads are on the
[Releases page](https://github.com/svrsunny/DriveVault/releases):

| File | What it is |
|---|---|
| `DriveVault-v1.8.0.zip` | Everything that goes on the drive (launchers, short guide, the app), plus the Android APK |
| `DriveVault-Android-v1.8.0.apk` | The Android app on its own |
| `SHA256SUMS.txt` | Checksums to verify the downloads |

To check a download on Windows: `certutil -hashfile DriveVault-v1.8.0.zip SHA256`; on Linux: `sha256sum -c SHA256SUMS.txt`.
Compare the result with `SHA256SUMS.txt`.

## 1. Prepare the drive

Format the USB drive as **exFAT** (Windows: right-click the drive → Format → File system *exFAT*; Linux: your disk
utility, or `mkfs.exfat`). **Formatting erases the drive.** exFAT works on Windows, Linux and Android; NTFS or ext4 drives
work on the desktop but most phones can't read them.

## 2. Desktop: Windows

1. Install **Python 3** from [python.org](https://www.python.org/downloads/windows/) and tick
   **"Add python.exe to PATH"** in the installer. (DriveVault is tested with Python 3.12.)
2. Extract `DriveVault-v1.8.0.zip` to the **root** of the drive, so the drive has `Open Vault (Windows).bat` and a
   `DriveVault` folder at the top.
3. Double-click **`Open Vault (Windows).bat`**. The first time, it installs the `cryptography` Python package for your
   user (internet needed once). Windows SmartScreen may warn because the files aren't code-signed: choose
   *More info → Run anyway* only if you downloaded the zip from the Releases page.
4. DriveVault opens in your browser. Keep the black console window open while you use the vault.

## 3. Desktop: Linux (any distribution)

1. Python 3 is usually installed already.
2. Extract the zip to the root of the drive.
3. Open a terminal in the drive's folder and run:

   ```bash
   sh open-vault-linux.sh
   ```

   The first time, it installs `cryptography` and Argon2 support with your package manager (apt, dnf, pacman, zypper or
   apk; it may ask for your password), or with pip.
4. Optional: install **VLC** or **mpv** to stream videos straight from the vault, and **wl-clipboard** (Wayland) or
   **xclip** (X11) for Ctrl+C / Ctrl+V with your file manager.

## 4. Android (8.0 or newer)

1. Download `DriveVault-Android-v1.8.0.apk` on the phone and open it. Allow *Install unknown apps* for your browser or
   file manager when Android asks.
2. Open DriveVault and allow **All files access** (needed to read and write the vault on a USB drive).
3. Plug in the drive (USB-C, or a USB OTG adapter) and pick it. Some phones need *OTG* turned on in Settings.
4. If the drive isn't listed, tap **"Drive not listed? Pick it with the system picker"** (or **"Or pick the drive with the
   system picker"** if you didn't allow All files access) and choose the drive in Android's folder picker.

Permissions the app asks for: storage access only. It has no internet permission.

## Creating the vault

Start DriveVault, check that the drive shown is your USB drive (**Change** to pick another), choose a password and save
the **recovery key** away from the drive. When you create a vault from the phone, DriveVault also copies the desktop app
onto the drive.

## Updating

- **Desktop:** extract the new zip over the old files on the drive and allow it to replace them. Only `DriveVault/app`
  and the launchers change; your encrypted files in `DriveVault/vault` are not touched.
- **Android:** install the new APK over the old one.
  - **Upgrading from 1.7 or earlier, or from any build that wasn't downloaded from this repository's Releases page:**
    the 1.8.0 APK is signed with a different key, so Android refuses to install it over the old app. Uninstall the old
    app first. Your vault on the drive is not affected, but uninstalling removes the **trusted phone** key: make sure you
    know the password (or have the recovery key), and after reinstalling, set up *Trust this phone* again in Security.
- **Older apps and new vaults:** after a vault's password is changed in 1.8, DriveVault 1.7 and older can't open it.
  Update DriveVault on every drive and phone you use.

## Uninstalling

- **Desktop:** delete the launchers and `DriveVault/app` from the drive. Keep `DriveVault/vault` unless you want to delete
  your encrypted files. Working copies on the computer are removed on lock; the empty folder
  `%LOCALAPPDATA%\DriveVault` (Windows) or `~/.cache/drivevault` (Linux) can be deleted.
- **Android:** uninstall the app as usual. The vault on the drive stays.
- **Deleting the vault:** delete the `DriveVault` folder on the drive. This cannot be undone.
