# FAQ

**Is my data safe if I lose the drive?**
Without your password (or recovery key, security answers or trusted phone) the vault can't be decrypted. Someone can
see that a DriveVault vault is there, roughly how many files it holds and how big they are, the text of your security
questions and the plain `activity.log`. See [SECURITY.md](../SECURITY.md).

**I forgot my password. Can you reset it?**
No. There is no backdoor and no server. Use **Forgot password?** with your recovery key, security questions or trusted
phone. Without any of them the data is lost.

**Does DriveVault upload anything?**
No. The desktop app only talks to your browser on `127.0.0.1`, and the Android app has no internet permission. The
launchers download the Python packages they need the first time. See [PRIVACY.md](../PRIVACY.md).

**Why do I need Python on the computer?**
The desktop app is a Python program that runs from the drive, so nothing has to be installed except Python and one
package. The upside is that the same files work on Windows and every Linux distribution.

**Does it work on macOS?**
There is no macOS launcher and macOS isn't tested.

**Why exFAT?**
It is the one file system that Windows, Linux and Android all read and write. DriveVault works on NTFS or ext4 drives on
the desktop, but most phones can't open those.

**Can I use the vault on two computers at the same time?**
No. A drive can only be in one place, and on a computer only one DriveVault window can have the vault open.

**Can I copy the vault folder as a backup?**
Yes. Copy the whole `DriveVault/vault` folder while the vault is locked. The copy stays encrypted and opens with the same
password. Don't copy it while it is unlocked and being changed.

**Are deleted files gone?**
Items go to Trash first and stay there until you restore them, delete them forever or empty the trash. Nothing is removed
automatically. "Delete forever" deletes the encrypted data from the drive.

**What does "Open in app" leave on my computer?**
A decrypted working copy while the vault is unlocked. On lock, DriveVault saves your changes back into the vault, then
overwrites and deletes the copy. On SSDs, traces can remain at the hardware level.

**Why can't I take screenshots on the phone?**
DriveVault blocks screenshots and the recent-apps preview so vault contents don't end up in your gallery or app switcher.

**Has it been audited?**
No. It uses well-known crypto libraries and is tested, but no independent security audit has been done.

## Known limitations

- Not independently audited; one maintainer.
- Desktop needs Python 3; no macOS launcher; the desktop files and the APK are not code-signed by a trusted publisher.
- The APK is installed by hand (not on Google Play).
- File count and approximate sizes are visible without the password; so are the security-question texts and the plain log.
- Malware on an unlocked computer or phone can read the vault.
- Android: "Open with" gives other apps read-only access. Edits made in another app on the phone are not saved back.
- Android: custom order is dragged in list view; in grid view use More → Move up / down.
- The Argon2id unlock time on phones (64 MiB, 3 passes) is an estimate; slow phones may take a few seconds.
- After a vault's password is changed in 1.8, DriveVault 1.7 and older can't open it.
