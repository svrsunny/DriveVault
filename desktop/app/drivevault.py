"""DriveVault desktop app: local web interface for an encrypted vault on a USB drive.

Run:  python3 drivevault.py [--vault PATH] [--no-browser] [--port N]
Only listens on 127.0.0.1. Works on Windows, Linux (any distro) and macOS.
"""
import argparse, atexit, errno, getpass, json, mimetypes, os, secrets, shutil, signal, socket, subprocess, sys
import tempfile, threading, time, traceback, webbrowser
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse, parse_qs, quote

sys.path.insert(0, str(Path(__file__).resolve().parent))
from vaultcore import Vault, VaultError, WrongPassword, ROOT, TRASH  # noqa: E402

APP_VERSION = "1.8.0"
APP_DIR = Path(__file__).resolve().parent
IDLE_LOCK_SECONDS = 15 * 60
SESSION_FILE = ".drivevault-session.json"
mimetypes.add_type("text/markdown", ".md")
mimetypes.add_type("video/mp4", ".mp4")
mimetypes.add_type("video/webm", ".webm")
mimetypes.add_type("audio/mp4", ".m4a")


# ---------------------------------------------------------------- local files
def open_with_default_app(path):
    if sys.platform.startswith("win"):
        os.startfile(path)  # noqa
    elif sys.platform == "darwin":
        subprocess.Popen(["open", path])
    else:
        subprocess.Popen(["xdg-open", path], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def reveal_in_file_manager(path):
    if sys.platform.startswith("win"):
        subprocess.Popen(f'explorer /select,"{path}"')
    elif sys.platform == "darwin":
        subprocess.Popen(["open", "-R", path])
    else:
        uri = Path(path).as_uri()
        try:
            r = subprocess.run(["gdbus", "call", "--session", "--dest", "org.freedesktop.FileManager1",
                                "--object-path", "/org/freedesktop/FileManager1",
                                "--method", "org.freedesktop.FileManager1.ShowItems", f"['{uri}']", ""],
                               capture_output=True, timeout=5)
            if r.returncode == 0:
                return
        except (OSError, subprocess.SubprocessError):
            pass
        open_with_default_app(str(Path(path).parent))


def work_root():
    """Where decrypted working copies go. Not /tmp: on many Linux distros /tmp is RAM-backed (tmpfs) and too small for big files."""
    if sys.platform.startswith("win"):
        base = Path(os.environ.get("LOCALAPPDATA") or Path.home() / "AppData" / "Local") / "DriveVault" / "work"
    elif sys.platform == "darwin":
        base = Path.home() / "Library" / "Caches" / "DriveVault" / "work"
    else:
        base = Path(os.environ.get("XDG_CACHE_HOME") or Path.home() / ".cache") / "drivevault" / "work"
    base.mkdir(parents=True, exist_ok=True)
    try:
        os.chmod(base, 0o700)
    except OSError:
        pass
    return base


def downloads_dir():
    """Where "Copy to computer" puts its copies: the user's Downloads folder, or the home folder if there is none."""
    downloads = Path.home() / "Downloads"
    return downloads if downloads.is_dir() else Path.home()


def ensure_space(folder, needed, what):
    free = shutil.disk_usage(folder).free
    if free < needed + (200 << 20):
        raise VaultError(f"Not enough free space for {what}: needs {human_size(needed)}, "
                         f"only {human_size(free)} free at {folder}.")


MEDIA_EXT = {".mkv", ".mp4", ".m4v", ".mov", ".avi", ".webm", ".wmv", ".flv", ".ts", ".m2ts", ".mpg", ".mpeg", ".3gp",
             ".mp3", ".m4a", ".flac", ".wav", ".ogg", ".opus", ".aac", ".wma"}


def find_player():
    """A media player that can play straight from a local http:// stream (no decrypted copy on disk)."""
    if sys.platform.startswith("win"):
        cands = [shutil.which("vlc"), r"C:\Program Files\VideoLAN\VLC\vlc.exe",
                 r"C:\Program Files (x86)\VideoLAN\VLC\vlc.exe", shutil.which("mpv")]
    elif sys.platform == "darwin":
        cands = ["/Applications/VLC.app/Contents/MacOS/VLC", shutil.which("mpv")]
    else:
        cands = [shutil.which(n) for n in ("mpv", "vlc", "celluloid", "smplayer", "haruna", "totem")] + ["/snap/bin/vlc"]
    for c in cands:
        if c and os.path.exists(c):
            return c
    return None


# ---------------------------------------------------------------- system clipboard (files)
def _run(cmd, inp=None, env=None, timeout=10):
    return subprocess.run(cmd, input=inp, capture_output=inp is None, stdout=subprocess.DEVNULL if inp is not None else None,
                          stderr=subprocess.DEVNULL if inp is not None else None, timeout=timeout, env=env,
                          creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))


def _uris_to_paths(text):
    from urllib.parse import unquote
    out = []
    for line in text.splitlines():
        line = line.strip()
        if line.startswith("file://"):
            u = urlparse(line)
            out.append(unquote(u.path))
    return out


def clipboard_get_files():
    """Paths of files/folders copied in the OS file manager (Explorer, Nautilus, Dolphin...)."""
    try:
        if sys.platform.startswith("win"):
            r = _run(["powershell", "-NoProfile", "-STA", "-Command",
                      "[Console]::OutputEncoding=[Text.Encoding]::UTF8; Get-Clipboard -Format FileDropList | ForEach-Object { $_.FullName }"])
            return [l.strip() for l in r.stdout.decode("utf-8", "replace").splitlines() if l.strip()]
        if sys.platform == "darwin":
            r = _run(["osascript", "-e", 'POSIX path of (the clipboard as «class furl»)'])
            p = r.stdout.decode().strip()
            return [p] if p else []
        cmds = []
        if os.environ.get("WAYLAND_DISPLAY") and shutil.which("wl-paste"):
            cmds += [["wl-paste", "-n", "-t", "x-special/gnome-copied-files"], ["wl-paste", "-n", "-t", "text/uri-list"]]
        if shutil.which("xclip"):
            cmds += [["xclip", "-selection", "clipboard", "-t", "x-special/gnome-copied-files", "-o"],
                     ["xclip", "-selection", "clipboard", "-t", "text/uri-list", "-o"]]
        for c in cmds:
            r = _run(c)
            if r.returncode == 0:
                paths = _uris_to_paths(r.stdout.decode("utf-8", "replace"))
                if paths:
                    return paths
    except (OSError, subprocess.SubprocessError):
        pass
    return []


def clipboard_set_files(paths):
    """Put files on the OS clipboard so they can be pasted in the file manager."""
    if sys.platform.startswith("win"):
        env = dict(os.environ, DV_PATHS="\n".join(paths))
        r = _run(["powershell", "-NoProfile", "-STA", "-Command", 'Set-Clipboard -LiteralPath ($env:DV_PATHS -split "`n")'],
                 inp=b"", env=env)
        if r.returncode != 0:
            raise VaultError("Could not put the files on the clipboard.")
        return
    if sys.platform == "darwin":
        script = "set the clipboard to {" + ", ".join(f'POSIX file "{p}"' for p in paths) + "}"
        _run(["osascript", "-e", script], inp=b"")
        return
    uris = "\n".join(Path(p).as_uri() for p in paths)
    gnome = ("copy\n" + uris).encode()
    desktop = (os.environ.get("XDG_CURRENT_DESKTOP") or "").lower()
    kind, data = ("x-special/gnome-copied-files", gnome) if "kde" not in desktop else ("text/uri-list", (uris + "\r\n").encode())
    if os.environ.get("WAYLAND_DISPLAY") and shutil.which("wl-copy"):
        _run(["wl-copy", "-t", kind], inp=data)
    elif shutil.which("xclip"):
        _run(["xclip", "-selection", "clipboard", "-t", kind, "-i"], inp=data)
    else:
        raise VaultError("Copying files to the clipboard needs wl-clipboard (Wayland) or xclip (X11). Install one with your package manager.")


class Session:
    """Decrypted working copies on this computer (for Open / Show in folder).
    Changes are encrypted back into the vault on Save or Lock, then the copies are deleted."""

    def __init__(self, vault, directory=None):
        self.vault = vault
        self.dir = Path(directory) if directory else None
        self.files = {}   # local path -> {"id", "mt", "sz"}
        self.roots = {}   # local folder path -> vault folder id (new files here get imported)
        self.by_id = {}   # entry id -> local path

    @classmethod
    def load(cls, vault, directory):
        s = cls(vault, directory)
        data = json.loads((Path(directory) / SESSION_FILE).read_text("utf-8"))
        s.files = {str(Path(directory) / k): v for k, v in data["files"].items()}
        s.roots = {str(Path(directory) / k): v for k, v in data["roots"].items()}
        s.by_id = {v["id"]: k for k, v in s.files.items()}
        return s

    @property
    def count(self):
        return sum(1 for p in self.files if os.path.exists(p))

    def _ensure(self):
        if not self.dir or not self.dir.exists():
            self.dir = Path(tempfile.mkdtemp(prefix="drivevault-", dir=work_root()))
        return self.dir

    def _save(self):
        rel = lambda p: os.path.relpath(p, self.dir)
        data = {"vault": self.vault.vault_id,
                "files": {rel(k): v for k, v in self.files.items()},
                "roots": {rel(k): v for k, v in self.roots.items()}}
        (self.dir / SESSION_FILE).write_text(json.dumps(data), "utf-8")

    def _track(self, path, eid):
        st = os.stat(path)
        self.files[path] = {"id": eid, "mt": st.st_mtime_ns, "sz": st.st_size}
        self.by_id[eid] = path

    def materialize(self, eid, progress=None):
        """Return a local path for the entry, decrypting it if not already there."""
        p = self.by_id.get(eid)
        if p and os.path.exists(p):
            return p
        e = self.vault.get(eid)
        if e["t"] == "d":
            for k, v in self.roots.items():
                if v == eid and os.path.isdir(k):
                    return k
        work = self._ensure()
        ensure_space(work, self.vault.tree_size(eid)[0], "a decrypted working copy")
        slot = work / secrets.token_hex(3)
        slot.mkdir()
        try:
            mapping = self.vault.export(eid, slot, progress)
        except BaseException:
            shutil.rmtree(slot, ignore_errors=True)  # never leave half-decrypted files behind
            raise
        for path, fid in mapping.items():
            self._track(path, fid)
        local = str(slot / e["n"])
        if e["t"] == "d":
            self.roots[local] = eid
        self._save()
        return local

    def pending(self):
        """Working files that changed or were added since they were decrypted."""
        v, jobs = self.vault, []
        for path, info in list(self.files.items()):
            if not os.path.exists(path):
                continue
            st = os.stat(path)
            if st.st_mtime_ns != info["mt"] or st.st_size != info["sz"]:
                jobs.append(("update", path, info["id"], st.st_size))
        for root, folder_id in list(self.roots.items()):
            if not os.path.isdir(root) or folder_id not in v.entries or v.in_trash(folder_id):
                continue
            for dirpath, _, names in os.walk(root):
                for n in names:
                    p = os.path.join(dirpath, n)
                    if p in self.files or n == SESSION_FILE or n.startswith(("~$", ".~lock")):
                        continue
                    jobs.append(("new", p, (root, folder_id), os.path.getsize(p)))
        return jobs

    def sync(self, progress=None, on_saved=None, jobs=None):
        """Encrypt changed / new working files back into the vault. Returns list of saved names."""
        saved, v = [], self.vault
        for kind, path, ref, size in (self.pending() if jobs is None else jobs):
            mt = os.stat(path).st_mtime_ns // 10**6
            with open(path, "rb") as f:
                if kind == "update":
                    eid = ref
                    if eid in v.entries:
                        v.add_file(None, v.entries[eid]["n"], f, mt, replace_id=eid, progress=progress)
                    else:  # removed from the vault meanwhile: keep the user's edit at top level
                        eid = v.add_file(ROOT, os.path.basename(path), f, mt, progress=progress)
                else:
                    root, folder_id = ref
                    parts = [x for x in Path(os.path.relpath(os.path.dirname(path), root)).parts if x != "."]
                    parent = v.mkdirs(folder_id, parts)
                    eid = v.add_file(parent, os.path.basename(path), f, mt, progress=progress)
            self._track(path, eid)
            saved.append(os.path.basename(path))
            if on_saved:
                on_saved(eid, kind)
        if self.dir and self.dir.exists():
            self._save()
        return saved

    def wipe(self):
        """Delete working copies. Returns True if everything was removed."""
        if not self.dir:
            return True
        for path in list(self.files):
            try:  # overwrite before delete (best effort; SSDs may keep old blocks)
                size = os.path.getsize(path)
                with open(path, "r+b") as f:
                    f.write(b"\0" * min(size, 64 << 20))
            except OSError:
                pass
        shutil.rmtree(self.dir, ignore_errors=True)
        ok = not self.dir.exists()
        self.files, self.roots, self.by_id = {}, {}, {}
        if ok:
            self.dir = None
        return ok


def find_stale_sessions(vault_id):
    out = []
    dirs = list(work_root().glob("drivevault-*")) + list(Path(tempfile.gettempdir()).glob("drivevault-*"))
    for d in dirs:
        try:
            data = json.loads((d / SESSION_FILE).read_text("utf-8"))
            if data.get("vault") == vault_id:
                out.append(str(d))
        except (OSError, ValueError):
            pass
    return out



# ---------------------------------------------------------------- drives
APP_BASE = APP_DIR.parent.parent          # folder that holds DriveVault/ and the launchers
LAUNCHERS = ["Open Vault (Windows).bat", "open-vault-linux.sh", "READ ME - DriveVault.txt"]
PHONE_FS = {"exfat", "vfat", "fat32", "fat", "msdos"}
REAL_FS = {"exfat", "vfat", "msdos", "ntfs", "ntfs3", "fuseblk", "ext2", "ext3", "ext4", "btrfs", "xfs", "f2fs", "hfsplus"}


def _linux_mounts():
    out = []
    try:
        for line in open("/proc/mounts", encoding="utf-8"):
            dev, mnt, fs = line.split()[:3]
            out.append((dev, mnt.replace("\\040", " "), fs))
    except OSError:
        pass
    return out


def _win_volume(root):
    import ctypes
    label, fs = ctypes.create_unicode_buffer(261), ctypes.create_unicode_buffer(261)
    ok = ctypes.windll.kernel32.GetVolumeInformationW(ctypes.c_wchar_p(root), label, 261, None, None, None, fs, 261)
    return (label.value, fs.value) if ok else ("", "")


def drive_info(base):
    """Describe the drive that holds `base`."""
    base = Path(base).resolve()
    info = {"base": str(base), "root": base.anchor or "/", "label": "", "fs": "", "kind": "drive",
            "total": 0, "free": 0}
    if sys.platform.startswith("win"):
        import ctypes
        root = base.anchor
        info["root"] = root
        info["label"], info["fs"] = _win_volume(root)
        t = ctypes.windll.kernel32.GetDriveTypeW(ctypes.c_wchar_p(root))
        info["kind"] = {2: "removable", 3: "fixed", 4: "network"}.get(t, "drive")
        sysdrive = (os.environ.get("SystemDrive", "C:") + "\\").upper()
        if root.upper() == sysdrive:
            info["kind"] = "system"
    else:
        best = ("", "/", "")
        for dev, mnt, fs in _linux_mounts():
            if (str(base) + "/").startswith(mnt.rstrip("/") + "/") and len(mnt) >= len(best[1]):
                best = (dev, mnt, fs)
        info["root"], info["fs"] = best[1], best[2]
        info["label"] = os.path.basename(best[1].rstrip("/")) if best[1] != "/" else ""
        info["kind"] = "system" if best[1] in ("/", "/home") else (
            "removable" if best[1].startswith(("/media/", "/run/media/")) else "drive")
    try:
        u = shutil.disk_usage(base)
        info["total"], info["free"] = u.total, u.free
    except OSError:
        pass
    info["fs"] = {"fuseblk": "ntfs", "ntfs3": "ntfs", "msdos": "vfat"}.get(info["fs"].lower(), info["fs"].lower())
    info["phoneOk"] = info["fs"] in PHONE_FS
    info["hasVault"] = (base / "DriveVault" / "vault" / "vault.json").exists()
    info["isAppDrive"] = base == APP_BASE.resolve()
    return info


def list_drives():
    bases = []
    if sys.platform.startswith("win"):
        import ctypes
        ctypes.windll.kernel32.SetErrorMode(1)  # no "insert a disk" pop-ups for empty card readers
        mask = ctypes.windll.kernel32.GetLogicalDrives()
        for i in range(26):
            if mask & (1 << i):
                root = f"{chr(65 + i)}:\\"
                if ctypes.windll.kernel32.GetDriveTypeW(ctypes.c_wchar_p(root)) in (2, 3) and os.path.exists(root):
                    bases.append(root)
    else:
        for dev, mnt, fs in _linux_mounts():
            if (dev.startswith("/dev/") and fs in REAL_FS and os.path.isdir(mnt)
                    and mnt.startswith(("/media/", "/run/media/", "/mnt/"))):
                bases.append(mnt)
    if str(APP_BASE.resolve()) not in [str(Path(b).resolve()) for b in bases]:
        bases.insert(0, str(APP_BASE))
    out = []
    for b in bases:
        try:
            out.append(drive_info(b))
        except OSError:
            pass
    return out


def vault_base_from_path(p):
    """Accept the drive/folder that holds DriveVault, the DriveVault folder, or the vault folder itself."""
    if not p:
        return None
    p = Path(str(p).strip().strip('"')).expanduser()
    for cand in (p, p.parent, p.parent.parent):
        if (cand / "DriveVault" / "vault" / "vault.json").is_file():
            return cand
    return None


SKIP_DIRS = {"windows", "program files", "program files (x86)", "programdata", "$recycle.bin", "system volume information",
             "proc", "sys", "dev", "usr", "lib", "lib64", "bin", "sbin", "etc", "var", "snap", "boot", "node_modules"}


def find_vaults(max_depth=3, budget=4000):
    """Look for existing vaults: next to the app, its parent folders, and on every drive (a few folders deep)."""
    out, seen = [], set()

    def hit(base):
        base = Path(base)
        try:
            key = str(base.resolve())
        except OSError:
            return
        if key not in seen and (base / "DriveVault" / "vault" / "vault.json").is_file():
            seen.add(key)
            out.append(base)

    for anc in [APP_BASE, *list(APP_BASE.parents)[:3]]:
        hit(anc)
    roots = [d["base"] for d in list_drives()]
    if not sys.platform.startswith("win"):
        roots += [str(Path.home())]
    left = [budget]

    def walk(folder, depth):
        hit(folder)
        if depth >= max_depth or left[0] <= 0:
            return
        try:
            with os.scandir(folder) as it:
                for d in it:
                    left[0] -= 1
                    if left[0] <= 0:
                        return
                    if (d.is_dir(follow_symlinks=False) and not d.name.startswith((".", "$"))
                            and d.name.lower() not in SKIP_DIRS and d.name != "DriveVault"):
                        walk(d.path, depth + 1)
        except OSError:
            pass

    for r in roots:
        walk(r, 0)
    return out


def install_app_on(base):
    """Copy DriveVault itself onto another drive so it can be opened from there on any computer."""
    base = Path(base)
    dst = base / "DriveVault" / "app"
    if dst.resolve() == APP_DIR:
        return
    shutil.copytree(APP_DIR, dst, dirs_exist_ok=True, ignore=shutil.ignore_patterns("__pycache__"))
    for name in LAUNCHERS:
        src = APP_BASE / name
        if src.exists() and not (base / name).exists():
            shutil.copy2(src, base / name)


# ---------------------------------------------------------------- progress + activity
HOST = socket.gethostname() or "computer"
OS_NAME = {"win32": "Windows", "darwin": "macOS"}.get(sys.platform, "Linux")
try:
    USER = getpass.getuser()
except Exception:
    USER = ""


class Task:
    def __init__(self, label, total, unit):
        self.id, self.label, self.total, self.unit = secrets.token_hex(4), label, max(0, int(total)), unit
        self.done, self.start = 0, time.time()

    def add(self, n):
        self.done += n


class Tasks:
    def __init__(self):
        self.items, self.lock = {}, threading.Lock()

    @contextmanager
    def run(self, label, total, unit="bytes"):
        t = Task(label, total, unit)
        with self.lock:
            self.items[t.id] = t
        try:
            yield t
        finally:
            with self.lock:
                self.items.pop(t.id, None)

    def snapshot(self):
        now = time.time()
        with self.lock:
            return [{"id": t.id, "label": t.label, "done": t.done, "total": t.total, "unit": t.unit,
                     "elapsed": now - t.start} for t in self.items.values()]


def human_size(n):
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if n < 1024 or unit == "TB":
            return f"{n:.0f} {unit}" if unit == "B" else f"{n:.1f} {unit}"
        n /= 1024


EVENT_TEXT = {
    "created": "VAULT CREATED", "unlocked": "UNLOCKED", "unlock_failed": "UNLOCK FAILED", "locked": "LOCKED",
    "password_changed": "PASSWORD CHANGED", "password_reset": "PASSWORD RESET", "recovery_key_created": "RECOVERY KEY CREATED",
    "recovery_key_removed": "RECOVERY KEY REMOVED", "questions_set": "SECURITY QUESTIONS SET", "questions_removed": "SECURITY QUESTIONS REMOVED",
    "recover_failed": "RECOVERY FAILED", "encrypted": "ENCRYPTED (added)", "folder_created": "FOLDER CREATED", "renamed": "RENAMED",
    "moved": "MOVED", "trashed": "MOVED TO TRASH", "restored": "RESTORED", "deleted": "DELETED FOREVER", "trash_emptied": "TRASH EMPTIED",
    "opened": "DECRYPTED (opened)", "revealed": "DECRYPTED (show in folder)", "downloaded": "DECRYPTED (downloaded)",
    "exported": "DECRYPTED (copied to computer)", "clipboard": "DECRYPTED (copied to clipboard)", "reordered": "CUSTOM ORDER CHANGED", "shared": "DECRYPTED (shared to another app)",
    "device_added": "TRUSTED PHONE ADDED", "device_removed": "TRUSTED PHONE REMOVED", "streamed": "PLAYED (streamed to media player)", "viewed": "VIEWED", "saved_back": "RE-ENCRYPTED (edited copy saved)",
    "added_back": "ENCRYPTED (new file from working folder)", "settings": "SETTINGS CHANGED", "history_exported": "HISTORY EXPORTED",
}


class Activity:
    """Writes every event to the encrypted history (when unlocked) and to the plain activity.log next to the vault."""
    MAX_LOG = 5 * 1024 * 1024

    def __init__(self, app):
        self.app = app
        self.failed = 0          # failed attempts while locked, reported into history at next unlock
        self.recent_views = {}

    @property
    def log_path(self):
        return self.app.vault.root.parent / "activity.log"

    def names_in_plain_log(self):
        try:
            return bool(self.app.vault._read_meta().get("settings", {}).get("plainLogNames"))
        except (OSError, ValueError):
            return False

    def _plain_line(self, ev):
        ts = time.strftime("%Y-%m-%d %H:%M:%S %z", time.localtime(ev["t"] / 1000))
        what = EVENT_TEXT.get(ev["ev"], ev["ev"].upper())
        bits = []
        if self.names_in_plain_log():
            if ev.get("name"):
                bits.append(f'"{ev["name"]}"')
            if ev.get("from"):
                bits.append(f'from "{ev["from"]}"')
            if ev.get("path"):
                bits.append(f'in "{ev["path"]}"')
            if ev.get("dest"):
                bits.append(f'to "{ev["dest"]}"')
        elif ev.get("name"):
            bits.append("1 folder" if ev.get("kind") == "d" else "1 item")
        if ev.get("files"):
            bits.append(f'{ev["files"]} file(s)')
        if ev.get("size"):
            bits.append(f'({human_size(ev["size"])})')
        for k in ("via", "why", "note"):
            if ev.get(k):
                bits.append(f'[{ev[k]}]')
        return f'{ts}  {HOST} ({OS_NAME}{", " + USER if USER else ""})  {what}  {" ".join(bits)}'.rstrip()

    def log(self, ev, **fields):
        event = {"t": int(time.time() * 1000), "ev": ev, "host": HOST, "os": OS_NAME, "user": USER}
        event.update({k: v for k, v in fields.items() if v not in (None, "")})
        v = self.app.vault
        if v.unlocked:
            try:
                v.history_append(event)
            except Exception:
                pass
        try:
            if v.root.parent.exists():
                lp = self.log_path
                if lp.exists() and lp.stat().st_size > self.MAX_LOG:
                    os.replace(lp, lp.with_name("activity.1.log"))
                with open(lp, "a", encoding="utf-8") as f:
                    f.write(self._plain_line(event) + "\n")
        except OSError:
            pass

    def where(self, eid):
        try:
            return "/".join(x["name"] for x in self.app.vault.path_of(eid))
        except VaultError:
            return ""

    def item(self, ev, eid, **extra):
        """Log an event about a vault entry (name, folder, size)."""
        v = self.app.vault
        e = v.entries.get(eid)
        if not e:
            return self.log(ev, **extra)
        size, files = (e["s"], 1) if e["t"] == "f" else v.tree_size(eid)
        self.log(ev, name=e["n"], path=self.where(e["p"]), kind=e["t"], size=size,
                 files=files if e["t"] == "d" else None, **extra)


# ---------------------------------------------------------------- app state
class App:
    def __init__(self, vault_dir, base=None):
        self.base = Path(base) if base else Path(vault_dir).parent.parent
        self.vault = Vault(vault_dir)
        self.session = Session(self.vault)
        self.tasks = Tasks()
        self.streams = {}
        self.activity = Activity(self)
        self.last_activity = time.time()
        self.notice = ""

    def select(self, base):
        with self.vault.lock:
            if self.vault.unlocked:
                raise VaultError("Lock the vault before switching drives.")
            found = vault_base_from_path(base)
            allowed = {str(Path(d["base"]).resolve()): d["base"] for d in list_drives()}
            key = str(Path(base).resolve()) if base else ""
            if found:
                self.base = found
            elif key in allowed:
                self.base = Path(allowed[key])
            else:
                raise VaultError("No DriveVault vault found at that location.")
            self.vault = Vault(self.base / "DriveVault" / "vault")
            self.session = Session(self.vault)
            self.activity.failed = 0

    def touch(self):
        self.last_activity = time.time()

    def sync(self, session=None, label="Saving edited files into the vault"):
        session = session or self.session
        jobs = session.pending()
        if not jobs:
            return []
        with self.tasks.run(label, sum(j[3] for j in jobs)) as t:
            return session.sync(t.add, lambda eid, kind: self.activity.item(
                "saved_back" if kind == "update" else "added_back", eid), jobs)

    def lock(self, reason="", why="manual"):
        saved, leftover = [], False
        with self.vault.lock:
            if self.vault.unlocked:
                try:
                    saved = self.sync(label="Locking: saving edited files")
                finally:
                    leftover = not self.session.wipe()
                    self.activity.log("locked", why=why, note=f"{len(saved)} edited file(s) saved" if saved else None)
                    self.vault.lock_now()
                    self.streams.clear()
                    self.session = Session(self.vault)
        if reason:
            self.notice = reason
        return {"saved": saved, "leftover": leftover}

    def idle_watch(self):
        while True:
            time.sleep(20)
            if (self.vault.unlocked and self.session.count == 0
                    and time.time() - self.last_activity > IDLE_LOCK_SECONDS):
                self.lock("Locked automatically after 15 minutes without activity.", why="auto-lock")


# ---------------------------------------------------------------- HTTP
class LimitedReader:
    def __init__(self, f, n): self.f, self.left = f, n
    def read(self, n=-1):
        if self.left <= 0:
            return b""
        n = self.left if n < 0 else min(n, self.left)
        b = self.f.read(n)
        self.left -= len(b)
        return b


def make_handler(app, token, port):
    cookie_name = f"dv{port}"
    allowed_hosts = {f"127.0.0.1:{port}", f"localhost:{port}"}
    ui = (APP_DIR / "ui.html").read_bytes()

    class H(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *a):
            pass

        # -- helpers
        def _send(self, code, body=b"", ctype="application/json", headers=None):
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Referrer-Policy", "no-referrer")
            for k, v in (headers or {}).items():
                self.send_header(k, v)
            self.end_headers()
            if self.command != "HEAD":
                self.wfile.write(body)

        def _json(self, obj, code=200):
            self._send(code, json.dumps(obj).encode())

        def _err(self, msg, code=400):
            self._json({"error": msg}, code)

        def _body(self):
            return json.loads(self._raw or b"{}")

        def _authed(self):
            if self.headers.get("Host") not in allowed_hosts:
                return False
            for part in (self.headers.get("Cookie") or "").split(";"):
                k, _, v = part.strip().partition("=")
                if k == cookie_name and secrets.compare_digest(v, token):
                    return True
            return False

        def _q(self):
            u = urlparse(self.path)
            return u.path, {k: v[0] for k, v in parse_qs(u.query).items()}

        # -- routing
        def do_GET(self):
            path, q = self._q()
            if self.headers.get("Host") not in allowed_hosts:
                return self._send(403, b"Forbidden", "text/plain")
            if path.startswith("/s/"):
                tok = path.split("/")[2] if path.count("/") >= 2 else ""
                eid = app.streams.get(tok)
                if not eid or not app.vault.unlocked:
                    return self._send(404, b"Stream ended. Open it again from DriveVault.", "text/plain")
                try:
                    return self._stream(eid, False, log=False)
                except (BrokenPipeError, ConnectionResetError):
                    return
                except VaultError as e:
                    return self._send(404, str(e).encode(), "text/plain")
            if path == "/" and q.get("t"):
                if not secrets.compare_digest(q["t"], token):
                    return self._send(403, b"Bad link. Start DriveVault again.", "text/plain")
                return self._send(303, headers={
                    "Location": "/",
                    "Set-Cookie": f"{cookie_name}={token}; HttpOnly; SameSite=Strict; Path=/"})
            if not self._authed():
                return self._send(403, b"Open DriveVault from the launcher.", "text/plain")
            if path == "/static/mammoth.min.js" and (APP_DIR / "mammoth.min.js").exists():
                return self._send(200, (APP_DIR / "mammoth.min.js").read_bytes(), "text/javascript")
            if path == "/":
                return self._send(200, ui, "text/html; charset=utf-8", {
                    "Content-Security-Policy": "default-src 'self'; img-src 'self' blob: data:; "
                    "media-src 'self' blob:; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; "
                    "frame-src 'self'; object-src 'none'; base-uri 'none'"})
            self._api("GET", path, q)

        do_HEAD = do_GET

        def do_POST(self):
            self._dispatch("POST")

        def do_PUT(self):
            self._dispatch("PUT")

        def _dispatch(self, method):
            path, q = self._q()
            if not self._authed():
                self.close_connection = True
                return self._send(403, b"Forbidden", "text/plain")
            self._raw = b""
            if method == "POST":  # always drain the body so keep-alive connections stay in sync
                n = int(self.headers.get("Content-Length") or 0)
                if n > 1_000_000:
                    self.close_connection = True
                    return self._err("Request too large", 413)
                self._raw = self.rfile.read(n) if n else b""
            self._api(method, path, q)

        def _api(self, method, path, q):
            v = app.vault
            try:
                if path == "/api/state":
                    notice, app.notice = app.notice, ""
                    try:
                        loc = drive_info(app.base)
                    except OSError:
                        loc = {"base": str(app.base), "missing": True}
                    return self._json({"exists": v.exists, "unlocked": v.unlocked,
                                       "vaultPath": str(v.root), "location": loc, "open": app.session.count,
                                       "trash": safe_trash_count(v),
                                       "recovery": v.recovery_info(), "kdf": v.kdf_name() if v.exists else None,
                                       "notice": notice, "idleMinutes": IDLE_LOCK_SECONDS // 60,
                                       "version": APP_VERSION})
                if path == "/api/progress":
                    return self._json({"tasks": app.tasks.snapshot()})
                if path == "/api/plainlog":
                    lp = app.activity.log_path
                    lines = lp.read_text("utf-8", errors="replace").splitlines()[-500:] if lp.exists() else []
                    return self._json({"path": str(lp), "lines": lines, "names": app.activity.names_in_plain_log()})
                app.touch()
                if method == "GET" and path == "/api/vaults":
                    cur = str(app.base.resolve())
                    return self._json({"vaults": [dict(drive_info(b), here=str(b.resolve()) == cur) for b in find_vaults()]})
                if method == "GET" and path == "/api/drives":
                    return self._json({"drives": list_drives()})
                if method == "POST" and path == "/api/select":
                    app.select(self._body().get("base", ""))
                    return self._json({"ok": True})
                if method == "POST" and path == "/api/create":
                    v.root.mkdir(parents=True, exist_ok=True)
                    v.create(self._body().get("password", ""))
                    try:
                        install_app_on(app.base)
                    except OSError:
                        pass
                    app.activity.log("created", note=f"on {drive_label(app.base)}")
                    return self._json({"ok": True})
                if method == "POST" and path == "/api/unlock":
                    try:
                        v.unlock(self._body().get("password", ""))
                    except WrongPassword:
                        app.activity.failed += 1
                        app.activity.log("unlock_failed", via="password")
                        raise
                    app.activity.log("unlocked", via="password", note=join_notes(self._failed_note(), cleaned_note(v)))
                    return self._json({"ok": True, "stale": len(find_stale_sessions(v.vault_id))})
                if method == "POST" and path == "/api/lock":
                    return self._json(app.lock())
                if method == "POST" and path == "/api/quit":
                    res = app.lock(why="lock & quit")
                    self._json(res)
                    threading.Thread(target=self.server.shutdown, daemon=True).start()
                    return
                if method == "POST" and path == "/api/stale":
                    action, saved = self._body().get("action"), []
                    for d in find_stale_sessions(v.vault_id):
                        s = Session.load(v, d)
                        if action == "save":
                            saved += app.sync(s, "Recovering files from the last session")
                        s.wipe()
                    return self._json({"saved": saved})
                if method == "POST" and path == "/api/passwd":
                    b = self._body()
                    v.change_password(b.get("old", ""), b.get("new", ""))
                    app.activity.log("password_changed")
                    return self._json({"ok": True})
                if method == "GET" and path == "/api/list":
                    fid = q.get("id", ROOT)
                    items = v.children(fid)
                    for i in items:
                        if fid == TRASH:
                            op = i.get("op", ROOT)
                            try:
                                i["from"] = "/".join(x["name"] for x in v.path_of(op)) if op in v.entries or op == ROOT else "Vault"
                            except VaultError:
                                i["from"] = "Vault"
                        i.pop("p", None), i.pop("b", None), i.pop("op", None)
                    return self._json({"path": v.path_of(fid), "items": items,
                                       "inTrash": fid == TRASH or v.in_trash(fid), "trash": v.trash_count()})
                if method == "GET" and path == "/api/search":
                    items = v.search(q.get("q", ""))
                    for i in items:
                        i["where"] = "/".join(x["name"] for x in v.path_of(i["p"]))
                        i.pop("p", None), i.pop("b", None)
                    return self._json({"items": items})
                if method == "POST" and path == "/api/trash":
                    ids = self._body().get("ids", [])
                    for i in ids:
                        if i in v.entries and not v.in_trash(i):
                            app.activity.item("trashed", i)
                    return self._json({"n": v.trash(ids), "trash": v.trash_count()})
                if method == "POST" and path == "/api/restore":
                    ids = self._body().get("ids", [])
                    n = v.restore(ids)
                    for i in ids:
                        app.activity.item("restored", i)
                    return self._json({"n": n, "trash": v.trash_count()})
                if method == "POST" and path == "/api/empty-trash":
                    ids = [k for k, e in list(v.entries.items()) if e["p"] == TRASH]
                    return self._json({"deleted": self._purge(ids, "Emptying trash", "trash_emptied"), "trash": 0})
                if method == "POST" and path == "/api/recover":
                    b = self._body()
                    kind = b.get("kind")
                    if kind not in ("key", "questions"):
                        raise VaultError("Unknown recovery method.")
                    via = "recovery key" if kind == "key" else "security questions"
                    try:
                        v.recover(kind, b.get("key") if kind == "key" else b.get("answers", []), b.get("password", ""))
                    except WrongPassword:
                        app.activity.failed += 1
                        app.activity.log("recover_failed", via=via)
                        raise
                    app.activity.log("password_reset", via=via, note=self._failed_note())
                    app.activity.log("unlocked", via=via, note=cleaned_note(v))
                    return self._json({"ok": True, "stale": len(find_stale_sessions(v.vault_id))})
                if method == "GET" and path == "/api/security":
                    return self._json(v.recovery_info())
                if method == "POST" and path == "/api/security/key":
                    key = v.set_recovery_key(self._body().get("password", ""))
                    app.activity.log("recovery_key_created")
                    return self._json({"key": key})
                if method == "POST" and path == "/api/security/questions":
                    b = self._body()
                    v.set_questions(b.get("password", ""), b.get("questions", []), b.get("answers", []))
                    app.activity.log("questions_set")
                    return self._json({"ok": True})
                if method == "POST" and path == "/api/security/remove":
                    b = self._body()
                    if b.get("kind") not in ("key", "questions", "device"):
                        raise VaultError("Unknown recovery method.")
                    v.remove_recovery(b.get("password", ""), b["kind"])
                    app.activity.log({"key": "recovery_key_removed", "questions": "questions_removed", "device": "device_removed"}[b["kind"]])
                    return self._json({"ok": True})
                if method == "POST" and path == "/api/mkdir":
                    b = self._body()
                    eid = v.mkdir(b.get("parent", ROOT), b.get("name", ""))
                    app.activity.item("folder_created", eid)
                    return self._json({"id": eid})
                if method == "POST" and path == "/api/rename":
                    b = self._body()
                    old = v.get(b["id"])["n"]
                    v.rename(b["id"], b.get("name", ""))
                    app.activity.item("renamed", b["id"], **{"from": old})
                    return self._json({"ok": True})
                if method == "POST" and path == "/api/move":
                    b = self._body()
                    for i in b.get("ids", []):
                        before = app.activity.where(v.get(i)["p"])
                        v.move(i, b.get("parent", ROOT))
                        app.activity.item("moved", i, **{"from": before})
                    return self._json({"ok": True})
                if method == "POST" and path == "/api/delete":
                    n = self._purge(self._body().get("ids", []), "Deleting forever", "deleted")
                    return self._json({"deleted": n, "trash": v.trash_count()})
                if method == "PUT" and path == "/api/upload":
                    n = self.headers.get("Content-Length")
                    if n is None:
                        return self._err("Missing Content-Length")
                    parent = q.get("parent", ROOT)
                    rel = [p for p in q.get("rel", "").split("/") if p][:-1]
                    with v.lock:
                        parent = v.mkdirs(parent, rel) if rel else parent
                    eid = v.add_file(parent, q.get("name", ""), LimitedReader(self.rfile, int(n)),
                                     int(q["mtime"]) if q.get("mtime") else None)
                    app.activity.item("encrypted", eid)
                    return self._json({"id": eid})
                if method == "GET" and path == "/api/file":   # thumb=1: grid thumbnail, not logged one by one
                    return self._stream(q["id"], q.get("dl") == "1", log=q.get("thumb") != "1")
                if method == "POST" and path == "/api/open":
                    b = self._body()
                    e = v.get(b["id"])
                    if (b.get("mode") != "reveal" and e["t"] == "f" and not b.get("copy")
                            and os.path.splitext(e["n"])[1].lower() in MEDIA_EXT):
                        player = find_player()
                        if player:
                            tok = secrets.token_urlsafe(18)
                            args = [player]
                            name = os.path.basename(player).lower()
                            if "vlc" in name:  # bigger network cache = smoother playback/seeking of large files
                                args += ["--network-caching=3000"]
                            elif name.startswith("mpv"):
                                args += ["--force-window=immediate", "--cache=yes"]
                            app.streams[tok] = b["id"]
                            url = f"http://127.0.0.1:{port}/s/{tok}/{quote(e['n'])}"
                            subprocess.Popen(args + [url], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                            app.activity.item("streamed", b["id"], note=os.path.basename(player))
                            return self._json({"ok": True, "streamed": os.path.splitext(os.path.basename(player))[0],
                                               "open": app.session.count})
                    size, files = v.tree_size(b["id"])
                    with app.tasks.run(f"Decrypting {v.get(b['id'])['n']}", size) as t:
                        fresh = b["id"] not in app.session.by_id
                        local = app.session.materialize(b["id"], t.add)
                    app.activity.item("revealed" if b.get("mode") == "reveal" else "opened", b["id"],
                                      note=None if fresh else "already decrypted")
                    try:
                        if b.get("mode") == "reveal":
                            reveal_in_file_manager(local)
                        else:
                            open_with_default_app(local)
                    except OSError:
                        raise VaultError(f"No app found to open it. The decrypted copy is at: {local}")
                    return self._json({"ok": True, "open": app.session.count})
                if method == "POST" and path == "/api/sync":
                    return self._json({"saved": app.sync(), "open": app.session.count})
                if method == "POST" and path == "/api/reorder":
                    b = self._body()
                    parent = b.get("parent", ROOT)
                    if parent == TRASH or (parent != ROOT and v.in_trash(parent)):
                        raise VaultError("Custom order is not available in Trash.")
                    n = v.reorder(parent, b.get("ids", []))
                    if b.get("log", True):
                        app.activity.log("reordered", path=app.activity.where(parent) if parent != ROOT else "Vault", files=n)
                    return self._json({"n": n})
                if method == "POST" and path == "/api/copy":
                    ids = [i for i in self._body().get("ids", []) if i in v.entries]
                    if not ids:
                        raise VaultError("Nothing selected.")
                    total = sum(v.tree_size(i)[0] for i in ids)
                    paths = []
                    with app.tasks.run(f"Decrypting {len(ids)} item(s) for the clipboard", total) as t:
                        for i in ids:
                            paths.append(app.session.materialize(i, t.add))
                    clipboard_set_files(paths)
                    for i in ids:
                        app.activity.item("clipboard", i)
                    return self._json({"n": len(ids), "open": app.session.count})
                if method == "POST" and path == "/api/paste":
                    return self._json(self._paste(self._body().get("parent", ROOT)))
                if method == "POST" and path == "/api/export":
                    return self._json(self._export(self._body().get("ids", [])))
                if method == "GET" and path == "/api/history":
                    return self._json(self._history(q))
                if method == "GET" and path == "/api/history.csv":
                    return self._history_csv()
                if method == "POST" and path == "/api/settings":
                    b = self._body()
                    meta = v._read_meta()
                    meta.setdefault("settings", {})["plainLogNames"] = bool(b.get("plainLogNames"))
                    with v.lock:
                        v._need()
                        v._write_meta(meta)
                    app.activity.log("settings", note="file names in plain log: " + ("on" if b.get("plainLogNames") else "off"))
                    return self._json({"ok": True})
                if method == "POST" and path == "/api/openlog":
                    open_with_default_app(str(app.activity.log_path))
                    return self._json({"ok": True})
                return self._err("Not found", 404)
            except WrongPassword as e:
                self.close_connection = True
                time.sleep(1)
                return self._err(str(e), 401)
            except VaultError as e:
                self.close_connection = True
                return self._err(str(e))
            except (BrokenPipeError, ConnectionResetError):
                return
            except OSError as e:
                self.close_connection = True
                return self._err(*os_error_message(e, app.vault.root))
            except Exception as e:   # a bug or a malformed request: say so plainly, keep the details in the console
                self.close_connection = True
                traceback.print_exc()
                return self._err(f"Something went wrong ({type(e).__name__}). Details are in the DriveVault window.", 500)

        def _failed_note(self):
            n, app.activity.failed = app.activity.failed, 0
            return f"{n} failed attempt(s) before this unlock" if n else None

        def _purge(self, ids, label, ev):
            v = app.vault
            ids = [i for i in ids if i in v.entries]
            total = sum(v.tree_size(i)[1] for i in ids)
            size = sum(v.tree_size(i)[0] for i in ids)
            if ev == "deleted":
                for i in ids:
                    app.activity.item("deleted", i)
            with app.tasks.run(label, total, "files"):
                n = v.delete(ids)
            if ev == "trash_emptied":
                app.activity.log("trash_emptied", files=total, size=size, note=f"{len(ids)} item(s)")
            return n

        def _paste(self, parent):
            v = app.vault
            v._check_parent(parent) if parent != ROOT else None
            srcs = [p for p in clipboard_get_files() if os.path.exists(p)]
            if not srcs:
                return {"none": True}
            work = str(work_root())
            jobs, total = [], 0           # (src file, [folder parts])
            for src in srcs:
                src = os.path.abspath(src)
                if os.path.isdir(src):
                    top = os.path.basename(src.rstrip("\\/")) or "Folder"
                    jobs.append((None, [top]))
                    for dp, dns, fns in os.walk(src):
                        rel = [top] + [x for x in Path(os.path.relpath(dp, src)).parts if x != "."]
                        for d in dns:
                            jobs.append((None, rel + [d]))
                        for f in fns:
                            fp = os.path.join(dp, f)
                            jobs.append((fp, rel))
                            total += os.path.getsize(fp)
                else:
                    jobs.append((src, []))
                    total += os.path.getsize(src)
            files, folders = 0, set()
            with app.tasks.run(f"Encrypting {len(srcs)} pasted item(s)", total) as t:
                for fp, parts in jobs:
                    with v.lock:
                        dest = v.mkdirs(parent, parts) if parts else parent
                    if parts:
                        folders.add(parts[0])
                    if fp is None:
                        continue
                    with open(fp, "rb") as f:
                        eid = v.add_file(dest, os.path.basename(fp), f, int(os.path.getmtime(fp) * 1000), progress=t.add)
                    files += 1
                    app.activity.item("encrypted", eid, note="pasted" + (" from a DriveVault working copy" if fp.startswith(work) else ""))
            v.save_index()
            return {"files": files, "folders": len(folders), "size": total, "items": len(srcs)}

        def _export(self, ids):
            v = app.vault
            ids = [i for i in ids if i in v.entries]
            if not ids:
                raise VaultError("Nothing selected.")
            dest = downloads_dir() / time.strftime("DriveVault export %Y-%m-%d %H%M%S")
            dest.mkdir(parents=True, exist_ok=True)
            total = sum(v.tree_size(i)[0] for i in ids)
            try:
                ensure_space(dest, total, "the copy")
            except VaultError:
                shutil.rmtree(dest, ignore_errors=True)
                raise
            files = 0
            with app.tasks.run(f"Copying {len(ids)} item(s) to this computer", total) as t:
                try:
                    for i in ids:
                        files += len(v.export(i, dest, t.add, unique=True))
                        app.activity.item("exported", i, dest=str(dest))
                except BaseException:
                    shutil.rmtree(dest, ignore_errors=True)
                    raise
            try:
                reveal_in_file_manager(str(dest))
            except OSError:
                pass
            return {"dest": str(dest), "files": files, "size": total}

        def _history(self, q):
            events, problems = app.vault.history_read()
            kind = q.get("type", "")
            groups = {"added": {"encrypted", "added_back", "folder_created", "saved_back"},
                      "out": {"opened", "revealed", "downloaded", "exported", "viewed", "streamed", "clipboard", "shared"},
                      "removed": {"trashed", "restored", "deleted", "trash_emptied"},
                      "access": {"created", "unlocked", "locked", "unlock_failed", "recover_failed", "password_changed",
                                 "password_reset", "recovery_key_created", "recovery_key_removed", "questions_set",
                                 "questions_removed", "settings", "history_exported", "device_added", "device_removed"},
                      "changes": {"renamed", "moved", "reordered"}}
            text = q.get("q", "").lower()
            out = [e for e in reversed(events)
                   if (not kind or e["ev"] in groups.get(kind, ()))
                   and (not text or text in json.dumps(e, ensure_ascii=False).lower())]
            off, lim = int(q.get("offset", 0)), int(q.get("limit", 200))
            return {"events": out[off:off + lim], "total": len(out), "all": len(events), "problems": problems,
                    "labels": EVENT_TEXT, "plainLogNames": app.activity.names_in_plain_log(),
                    "logPath": str(app.activity.log_path)}

        def _history_csv(self):
            import csv, io
            events, _ = app.vault.history_read()
            buf = io.StringIO()
            w = csv.writer(buf)
            w.writerow(["#", "time", "event", "name", "folder", "from", "to", "size_bytes", "files", "details", "computer", "os", "user"])
            for e in events:
                w.writerow([e["seq"], time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(e["t"] / 1000)),
                            EVENT_TEXT.get(e["ev"], e["ev"]), e.get("name", ""), e.get("path", ""), e.get("from", ""),
                            e.get("dest", ""), e.get("size", ""), e.get("files", ""),
                            " ".join(str(e[k]) for k in ("via", "why", "note") if e.get(k)),
                            e.get("host", ""), e.get("os", ""), e.get("user", "")])
            app.activity.log("history_exported", note=f"{len(events)} events")
            body = buf.getvalue().encode("utf-8-sig")
            return self._send(200, body, "text/csv; charset=utf-8", {
                "Content-Disposition": "attachment; filename=\"DriveVault history.csv\""})

        def _stream(self, eid, download, log=True):
            e = app.vault.get(eid)
            if e["t"] != "f":
                raise VaultError("Not a file.")
            size = e["s"]
            ctype = mimetypes.guess_type(e["n"])[0] or "application/octet-stream"
            if ctype.startswith("text/"):
                ctype += "; charset=utf-8"
            start, end, code = 0, size - 1, 200
            rng = self.headers.get("Range")
            if rng and rng.startswith("bytes=") and size:
                a, _, b = rng[6:].split(",")[0].partition("-")
                if a:
                    start, end = int(a), int(b) if b else size - 1
                else:
                    start, end = max(0, size - int(b)), size - 1
                end = min(end, size - 1)
                if start > end:
                    return self._send(416, headers={"Content-Range": f"bytes */{size}"})
                code = 206
            length = max(0, end - start + 1)
            disp = "attachment" if download else "inline"
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(length))
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            if not ctype.startswith("application/pdf"):  # never let vault files run scripts on this origin
                self.send_header("Content-Security-Policy", "sandbox; default-src 'none'; img-src 'self' data:; style-src 'unsafe-inline'; media-src 'self'")
            self.send_header("Content-Disposition", f"{disp}; filename*=UTF-8''{quote(e['n'])}")
            if code == 206:
                self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
            self.end_headers()
            if self.command == "HEAD":
                return
            if not log:
                pass
            elif download:
                app.activity.item("downloaded", eid)
            elif start == 0 and time.time() - app.activity.recent_views.get(eid, 0) > 120:
                app.activity.recent_views[eid] = time.time()
                app.activity.item("viewed", eid)
            if length == 0:
                return
            for part in app.vault.read_range(eid, start, end):
                self.wfile.write(part)

    return H


WIN_DRIVE_GONE = {21, 55, 433, 1006, 1167}   # not ready, network/device gone, device not connected
WIN_NO_SPACE = {39, 112}


def os_error_message(e, vault_root):
    """(message, http status) for a file-system error, worded for people rather than programmers."""
    win = getattr(e, "winerror", None)
    where = f" ({e.filename})" if e.filename else ""
    if e.errno in (errno.ENOSPC, errno.EDQUOT) or win in WIN_NO_SPACE:
        return f"Out of space while writing{where}. Free some space and try again.", 507
    if win in WIN_DRIVE_GONE or e.errno in (errno.ENODEV, errno.ENXIO, errno.EIO) or not Path(vault_root).exists():
        return "The drive was removed or stopped responding. Plug it back in, then lock and unlock the vault.", 503
    if e.errno == errno.EROFS:
        return "The drive is read-only. Check the drive's write-protect switch, or unplug it and plug it back in.", 403
    if e.errno in (errno.EACCES, errno.EPERM):
        return f"No permission to use this file{where}. If it is open in another program, close it and try again.", 403
    return f"Drive error: {e.strerror or e}. Is the drive still connected?", 500


def cleaned_note(v):
    tmp, blobs = v.cleaned
    parts = ([f"{tmp} leftover temp file(s)"] if tmp else []) + ([f"{blobs} unreferenced blob(s)"] if blobs else [])
    return "removed " + " and ".join(parts) + " from an interrupted write" if parts else None


def join_notes(*notes):
    return "; ".join(n for n in notes if n) or None


def safe_trash_count(v):
    try:
        return v.trash_count() if v.unlocked else 0
    except RuntimeError:
        return 0


def drive_label(base):
    try:
        d = drive_info(base)
        return (f'{d["label"]} ({d["root"].rstrip(chr(92))})' if d.get("label") else d.get("root", "")) or str(base)
    except OSError:
        return str(base)


def default_vault_dir():
    # app lives in <drive>/DriveVault/app -> vault in <drive>/DriveVault/vault
    return APP_DIR.parent / "vault"


def main():
    ap = argparse.ArgumentParser(description="DriveVault")
    ap.add_argument("--vault", default=str(default_vault_dir()))
    ap.add_argument("--port", type=int, default=0)
    ap.add_argument("--no-browser", action="store_true")
    ap.add_argument("--token", default=None, help=argparse.SUPPRESS)
    args = ap.parse_args()

    app = App(args.vault)
    if not app.vault.exists and args.vault == str(default_vault_dir()):
        found = find_vaults()
        if found:  # updated/moved app: reuse the existing vault instead of offering to create a new one
            app.base = found[0]
            app.vault = Vault(found[0] / "DriveVault" / "vault")
            app.session = Session(app.vault)
    token = args.token or secrets.token_urlsafe(24)
    server = ThreadingHTTPServer(("127.0.0.1", args.port), None)
    port = server.server_address[1]
    server.RequestHandlerClass = make_handler(app, token, port)
    server.daemon_threads = True
    url = f"http://127.0.0.1:{port}/?t={token}"

    def cleanup(*_):
        try:
            app.lock(why="app closed")
        except Exception:
            pass
    atexit.register(cleanup)
    for sig in (signal.SIGINT, signal.SIGTERM, getattr(signal, "SIGBREAK", None)):
        if sig:
            signal.signal(sig, lambda *_: (cleanup(), os._exit(0)))
    if sys.platform.startswith("win"):  # lock + delete working copies when the console window is closed
        try:
            import ctypes
            HANDLER = ctypes.WINFUNCTYPE(ctypes.c_int, ctypes.c_uint)
            def on_close(_evt):
                cleanup()
                return 0
            main._h = HANDLER(on_close)
            ctypes.windll.kernel32.SetConsoleCtrlHandler(main._h, True)
        except Exception:
            pass

    threading.Thread(target=app.idle_watch, daemon=True).start()
    print(f"\n  DriveVault {APP_VERSION} is running.")
    print(f"  Vault: {app.vault.root}")
    print(f"  Open:  {url}\n")
    print("  Keep this window open while you use the vault.")
    print("  Use 'Lock & quit' in the browser (or close this window) when you are done.\n")
    if not args.no_browser:
        webbrowser.open(url)
    try:
        server.serve_forever()
    finally:
        cleanup()
        print("  Vault locked. You can unplug the drive.")


if __name__ == "__main__":
    main()
