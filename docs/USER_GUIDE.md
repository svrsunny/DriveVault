# User guide

- [Desktop](#desktop)
  - [Create, unlock, lock](#create-unlock-lock)
  - [Adding files](#adding-files)
  - [Browsing and finding files](#browsing-and-finding-files)
  - [Viewing files](#viewing-files)
  - [Working with files](#working-with-files)
  - [Trash](#trash)
  - [History and activity log](#history-and-activity-log)
  - [Password and recovery](#password-and-recovery)
  - [Keyboard shortcuts](#keyboard-shortcuts)
- [Android](#android)
- [Good habits](#good-habits)

## Desktop

### Create, unlock, lock

- Start DriveVault from the drive ([installation](INSTALLATION.md)). The first screen shows which drive the vault is on.
  Press **Change** to pick another drive or to type a folder path.
- **First time:** choose a password and write down the **recovery key** that DriveVault shows. Keep it away from the
  drive: printed, in a password manager or as a photo on your phone.
- **Lock** locks the vault and keeps DriveVault running; **Lock & quit** locks and stops it. Always lock before you unplug
  the drive. Closing the console window also locks.
- DriveVault locks itself after 15 minutes without activity, unless decrypted copies are open (see *Open in app*).
- Only one DriveVault window can have a vault open at a time.

### Adding files

- **Upload files** / **Upload folder**, or drag files and folders into the window.
- **Ctrl+V** pastes files and folders you copied in Explorer or your Linux file manager.
- Everything is encrypted onto the drive. Big uploads show percentage, speed and time left.

### Browsing and finding files

- Click a folder to open it; use the path at the top (breadcrumbs) or the back / forward / up / home buttons.
- **Search** looks through the whole vault (not the Trash).
- **Sort** by name, size or date, or choose **Custom order** and drag files up and down. Folders always stay on top, and
  dragging never moves a file into a folder (use **Move to…**). The order is saved in the vault.
- The grid button switches between a list and a **grid with photo thumbnails**.
- Select with the checkboxes, Ctrl+click, Shift+click, Shift+↑/↓ or Ctrl+A. The bar that appears offers
  Move to…, Copy to computer and Move to Trash.

### Viewing files

Click a file name to open the viewer: photos, video, audio, PDF, Word (.docx), CSV and text. Use ← / → for the previous /
next file, + / − / 0 / 1 to zoom, F for full screen and Esc to close. Large videos play better with **Open in app**,
which streams them into VLC or mpv without writing a decrypted copy.

### Working with files

- **Download** saves one decrypted file through the browser. **Copy to computer** decrypts the selection (files or
  folders) into your Downloads folder. **Ctrl+C** puts the selection on the clipboard for your file manager.
- **Open in app** decrypts a temporary copy and opens it in its normal program. **Show in folder** does the same and
  shows it in Explorer / Files. Save your changes there: when you lock (or press **Save changes now**), DriveVault
  encrypts the edited files back into the vault and deletes the copies. New files saved into a shown folder are added too.
- If DriveVault stopped without locking (crash, power cut), the next unlock offers to **save** or **delete** the
  left-over copies.
- **Rename** (F2), **New folder**, **Move to…**.

### Trash

Deleted items go to **Trash** and stay there until you remove them: **Restore**, **Delete forever** or **Empty trash**.
Nothing is deleted automatically. Deleting forever removes the encrypted data from the drive.

### History and activity log

- **History** is an encrypted record of everything added, opened, copied out, renamed, moved and deleted, and of every
  unlock, with the computer it happened on. DriveVault checks it every time and warns if entries were removed or changed.
  Filter it, search it, or **Export CSV** (an unencrypted file that includes file names).
- `DriveVault/activity.log` is a plain-text log that can be read **without** the password (also from **Activity log** on
  the unlock screen). It shows file names only if you turn on the option in History. Failed unlock attempts are written there.

### Password and recovery

Open **Security**:

- **Change password**. Vaults made before 1.8 switch to stronger protection (Argon2id) at this point; DriveVault 1.7 and
  older can't open them afterwards, so update everywhere first.
- **Recovery key**: create or replace it. **Forgot password?** on the unlock screen sets a new password with the key.
- **Security questions**: three answers that can also reset the password. Spelling, capitals and punctuation don't
  matter. They are easier to guess than the key.
- **Trusted Android phone**: set up in the Android app; shown and removable here.

### Keyboard shortcuts

| Keys | Action |
|---|---|
| ↑ / ↓, Shift+↑ / ↓, Space, Enter | Move, extend the selection, toggle, open |
| Ctrl+A, Esc | Select all, clear the selection |
| Ctrl+C / Ctrl+V | Copy to / paste from your file manager |
| Del, F2 | Move to Trash (Delete forever inside Trash), rename |
| Alt+← / Alt+→ / Alt+↑, Backspace | Back, forward, up |
| Alt+Space or Ctrl+Home | Home (top of the vault) |
| Ctrl+↑ / ↓ | Move the selection up / down in custom order |
| In the viewer: ← / →, + / − / 0 / 1, F, Esc | Previous / next, zoom / fit / 1:1, full screen, close |

## Android

- **Choose the drive** on the first screen. Drives with a vault show **Has vault**. If a drive shows **Not ready**, wait a
  moment and tap it or Retry. If your USB drive isn't listed at all, tap **Drive not listed? Pick it with the system picker**.
- **Unlock** with the same password as on the computer. The lock screen also lets you choose the **auto-lock** time:
  DriveVault locks when it has been in the background that long.
- **Add files:** the **+** button (files, a whole folder, or a new folder), or **Share → DriveVault** from another app.
- **Select** with a long press. The bottom bar offers Move, To phone (decrypted copy in Downloads), Share, Trash and More
  (rename, open with, custom order moves).
- **Open with** and **Share** let another app read the file through a temporary link, without a decrypted copy on the
  phone. The links stop working when you lock.
- **Grid view** (top bar) shows photo thumbnails. In **Custom order**, drag the ≡ handle in list view.
- **Security → Trust this phone** lets this phone reset a forgotten password after confirming its fingerprint / PIN.
- Screenshots of DriveVault are blocked by design.

## Good habits

- Use 4–5 random words as the password, and keep the recovery key somewhere safe and separate from the drive.
- Back up the `DriveVault/vault` folder now and then by copying it to another drive. It stays encrypted.
- Don't edit or delete anything inside `DriveVault/vault` by hand.
- Only files inside the vault are protected; anything else on the drive is not.
