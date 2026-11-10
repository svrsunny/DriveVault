"""DriveVault core: encrypted vault on a removable drive. See FORMAT.md."""
import base64, hashlib, hmac, json, os, re, secrets, shutil, struct, threading, time, unicodedata, uuid
from pathlib import Path

from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.exceptions import InvalidTag

CHUNK = 1 << 20
TAG = 16
MAGIC = b"DVB1"
ITERATIONS = 600_000              # PBKDF2, for vaults made before 1.8 (or when Argon2id isn't installed)
ARGON2 = {"m": 65536, "t": 3, "p": 1}   # password KDF since 1.8: 64 MiB, 3 passes (SECURITY.md)
NEW_KDF = "argon2id"              # tests set "pbkdf2-sha256" to make pre-1.8 style vaults
ROOT = "root"
TRASH = "trash"
KEY_ITERATIONS = 200_000          # recovery key has ~125 bits of entropy
QUESTION_ITERATIONS = 1_000_000   # answers are guessable, so make each guess expensive
KEY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
AAD_KEY = b"drivevault/v1/recovery-key"
AAD_QUESTIONS = b"drivevault/v1/recovery-questions"
FORBIDDEN = set('<>:"/\\|?*') | {chr(i) for i in range(32)}


class VaultError(Exception):
    pass


class WrongPassword(VaultError):
    pass


def _b64(b): return base64.b64encode(b).decode()
def _unb64(s): return base64.b64decode(s)
def _hmac(key, msg): return hmac.new(key, msg, hashlib.sha256).digest()
def _kek(password, salt, iters):
    return hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, iters, 32)
def _argon2id_impl():
    """cryptography >= 44 has Argon2id; older installs can use argon2-cffi. None if neither is installed."""
    try:
        from cryptography.hazmat.primitives.kdf.argon2 import Argon2id
        return lambda pw, salt, m, t, p: Argon2id(salt=salt, length=32, iterations=t, lanes=p, memory_cost=m).derive(pw)
    except ImportError:
        pass
    try:
        from argon2.low_level import hash_secret_raw, Type
        return lambda pw, salt, m, t, p: hash_secret_raw(pw, salt, t, m, p, 32, Type.ID)
    except ImportError:
        return None


def argon2_available(): return _argon2id_impl() is not None


def _password_kek(kdf, password):
    name = kdf.get("name")
    if name == "pbkdf2-sha256":
        return _kek(password, _unb64(kdf["salt"]), int(kdf["iterations"]))
    if name == "argon2id":
        impl = _argon2id_impl()
        if impl is None:
            raise VaultError("This vault uses Argon2id. Run the DriveVault launcher again so it can install it, "
                             "or install the Python package 'argon2-cffi'.")
        return impl(password.encode("utf-8"), _unb64(kdf["salt"]), int(kdf["m"]), int(kdf["t"]), int(kdf["p"]))
    raise VaultError("This vault was made by a newer DriveVault. Update DriveVault to open it.")


def _new_password_kdf():
    salt = _b64(os.urandom(16))
    if NEW_KDF == "argon2id" and argon2_available():
        return {"name": "argon2id", **ARGON2, "salt": salt}
    return {"name": "pbkdf2-sha256", "iterations": ITERATIONS, "salt": salt}


def _nonce(i): return b"\x00\x00\x00\x00" + struct.pack(">Q", i)
def _aad(i, final): return MAGIC + struct.pack(">Q", i) + (b"\x01" if final else b"\x00")
def nchunks(size): return max(1, -(-size // CHUNK))


def _fsync_write(path, data):
    with open(path, "wb") as f:
        f.write(data)
        f.flush()
        os.fsync(f.fileno())


def _read_full(src, n):
    buf = bytearray()
    while len(buf) < n:
        b = src.read(n - len(buf))
        if not b:
            break
        buf += b
    return bytes(buf)


def normalize_answer(a):
    a = unicodedata.normalize("NFKC", a or "").lower()
    return " ".join(re.sub(r"[\W_]+", " ", a).split())


def normalize_key(k):
    return re.sub(r"[^A-Z0-9]", "", (k or "").upper())


def new_recovery_key():
    raw = "".join(secrets.choice(KEY_ALPHABET) for _ in range(25))
    return "-".join(raw[i:i + 5] for i in range(0, 25, 5))


def clean_name(name):
    name = (name or "").strip().rstrip(".")
    if not name or name in (".", "..") or len(name) > 255 or any(c in FORBIDDEN for c in name):
        raise VaultError("Invalid name. Avoid  < > : \" / \\ | ? *")
    return name


class AlreadyOpen(VaultError):
    pass


class _InstanceLock:
    """OS file lock on <vault>/.lock while the vault is unlocked, so two DriveVault windows can't write the same index.
    The OS drops it if the process dies, so a crash never leaves the vault stuck."""

    def __init__(self, path):
        self.path, self.f = path, None

    def acquire(self):
        if self.f:
            return
        f = open(self.path, "a+b")
        try:
            if os.name == "nt":
                import msvcrt
                f.seek(0)
                msvcrt.locking(f.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(f.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            f.close()
            raise AlreadyOpen("This vault is already open in another DriveVault window. Lock it there first.")
        self.f = f

    def release(self):
        if not self.f:
            return
        try:
            if os.name == "nt":
                import msvcrt
                self.f.seek(0)
                msvcrt.locking(self.f.fileno(), msvcrt.LK_UNLCK, 1)
        except OSError:
            pass
        self.f.close()   # closing also drops an fcntl lock
        self.f = None


class Vault:
    def __init__(self, root):
        self.root = Path(root)
        self.lock = threading.RLock()
        self.instance = _InstanceLock(self.root / ".lock")
        self.cleaned = (0, 0)   # (tmp files, orphan blobs) removed at the last unlock
        self._clear()

    def _clear(self):
        for k in (getattr(self, "master", None), getattr(self, "index_key", None), getattr(self, "content_key", None)):
            if isinstance(k, bytearray):
                k[:] = bytes(len(k))
        self.master = self.index_key = self.content_key = None
        self.entries = {}
        self.seq = 0
        self.meta = None
        self.hist_seq = None
        self.index_h = 0
        self._index_blobs = None

    # ---------- header ----------
    @property
    def header_path(self): return self.root / "vault.json"
    @property
    def exists(self): return self.header_path.exists()
    @property
    def unlocked(self): return self.master is not None
    @property
    def vault_id(self):
        if self.meta is None and self.exists:
            self.meta = json.loads(self.header_path.read_text("utf-8"))
        return self.meta["id"] if self.meta else None

    def create(self, password):
        with self.lock:
            if self.exists:
                raise VaultError("A vault already exists here.")
            if (self.root / "index.dvx").exists() or (self.root / "index.dvx.bak").exists():
                raise VaultError("This folder holds vault data but its vault.json is missing or unreadable. "
                                 "Not creating a new vault over it.")
            if len(password) < 8:
                raise VaultError("Use a password of at least 8 characters.")
            (self.root / "blobs").mkdir(parents=True, exist_ok=True)
            self.instance.acquire()
            try:
                master = os.urandom(32)
                meta = {"format": "drivevault", "version": 1, "id": str(uuid.uuid4()), "kdf": {}, "key": {}}
                self._wrap(meta, master, password)
                _fsync_write(self.header_path, json.dumps(meta, indent=2).encode())
                self.meta = meta
                self._set_keys(master)
                self.entries, self.seq = {}, 0
                self.save_index()
            except BaseException:
                self._clear()
                self.instance.release()
                raise

    def _wrap(self, meta, master, password):
        """(Re-)wrap the master key under a password with a fresh KDF, so a password change upgrades PBKDF2 to Argon2id."""
        meta["kdf"] = _new_password_kdf()
        kek = _password_kek(meta["kdf"], password)
        nonce = os.urandom(12)
        meta["key"] = {"nonce": _b64(nonce),
                       "ct": _b64(AESGCM(kek).encrypt(nonce, bytes(master), b"drivevault/v1/masterkey"))}

    def _unwrap(self, meta, password):
        if meta.get("format") != "drivevault" or meta.get("version") != 1:
            raise VaultError("Unsupported vault format.")
        if not password:
            raise WrongPassword("Wrong password.")
        kek = _password_kek(meta["kdf"], password)
        try:
            return AESGCM(kek).decrypt(_unb64(meta["key"]["nonce"]), _unb64(meta["key"]["ct"]),
                                       b"drivevault/v1/masterkey")
        except InvalidTag:
            raise WrongPassword("Wrong password.")

    def _set_keys(self, master):
        # bytearrays so lock can overwrite them (best effort: Python and OpenSSL may hold other copies)
        self.master = bytearray(master)
        self.index_key = bytearray(_hmac(master, b"drivevault/v1/index-key"))
        self.content_key = bytearray(_hmac(master, b"drivevault/v1/content-key"))

    def kdf_name(self):
        """"argon2id", or "pbkdf2-sha256" for a vault whose password hasn't been changed since before 1.8."""
        try:
            return self._read_meta()["kdf"]["name"]
        except (OSError, ValueError, KeyError):
            return None

    def unlock(self, password):
        with self.lock:
            if not self.exists:
                raise VaultError("No vault found on this drive.")
            meta = json.loads(self.header_path.read_text("utf-8"))
            master = self._unwrap(meta, password)
            self.meta = meta
            self._open(master)

    def _open(self, master):
        """Every way of unlocking ends here: take the instance lock, load the index, tidy leftovers."""
        self.instance.acquire()
        try:
            self._set_keys(master)
            self._load_index()
        except BaseException:
            self._clear()
            self.instance.release()
            raise
        self.cleaned = self._clean_leftovers()

    def _clean_leftovers(self):
        """Remove what an interrupted write leaves behind: *.tmp files, and blobs that no index copy refers to.
        Blobs are only removed when both index copies decrypted and the newest one is index.dvx, so a damaged
        index never causes data to be deleted."""
        tmp = orphans = 0
        for p in list(self.root.glob("*.tmp")) + list(self.root.glob("blobs/*/*.tmp")):
            try:
                p.unlink()
                tmp += 1
            except OSError:
                pass
        if self._index_blobs is not None:
            for p in self.root.glob("blobs/*/*.dvb"):
                if p.stem not in self._index_blobs:
                    try:
                        p.unlink()
                        orphans += 1
                    except OSError:
                        pass
        return tmp, orphans

    def lock_now(self):
        with self.lock:
            try:  # seal the history length into the index so cutting entries off the end is detectable
                if self.master is not None and (self.hist_seq or 0) > self.index_h:
                    self.save_index()
            except OSError:
                pass
            self._clear()
            self.instance.release()

    def _read_meta(self):
        return json.loads(self.header_path.read_text("utf-8"))

    def _write_meta(self, meta):
        tmp = self.header_path.with_suffix(".json.tmp")
        _fsync_write(tmp, json.dumps(meta, indent=2).encode())
        shutil.copyfile(self.header_path, self.root / "vault.json.bak")
        os.replace(tmp, self.header_path)
        self.meta = meta

    def change_password(self, old, new):
        with self.lock:
            if len(new) < 8:
                raise VaultError("Use a password of at least 8 characters.")
            meta = self._read_meta()
            master = self._unwrap(meta, old)
            self._wrap(meta, master, new)
            self._write_meta(meta)

    def verify_password(self, password):
        self._unwrap(self._read_meta(), password)

    # ---------- recovery (extra key slots that wrap the same master key) ----------
    @staticmethod
    def _seal(secret, iterations, master, aad):
        salt, nonce = os.urandom(16), os.urandom(12)
        kek = _kek(secret, salt, iterations)
        return {"kdf": {"name": "pbkdf2-sha256", "iterations": iterations, "salt": _b64(salt)},
                "nonce": _b64(nonce), "ct": _b64(AESGCM(kek).encrypt(nonce, master, aad))}

    @staticmethod
    def _open_slot(slot, secret, aad):
        kek = _kek(secret, _unb64(slot["kdf"]["salt"]), int(slot["kdf"]["iterations"]))
        try:
            return AESGCM(kek).decrypt(_unb64(slot["nonce"]), _unb64(slot["ct"]), aad)
        except InvalidTag:
            return None

    def recovery_info(self):
        if not self.exists:
            return {"key": False, "questions": None, "device": None}
        rec = self._read_meta().get("recovery", {})
        q = rec.get("questions")
        dev = rec.get("device")
        return {"key": "key" in rec, "questions": q["q"] if q else None, "device": dev.get("name") if dev else None}

    def set_recovery_key(self, password):
        with self.lock:
            self._need()
            meta = self._read_meta()
            self._unwrap(meta, password)
            key = new_recovery_key()
            meta.setdefault("recovery", {})["key"] = self._seal(normalize_key(key), KEY_ITERATIONS, self.master, AAD_KEY)
            self._write_meta(meta)
            return key

    def set_questions(self, password, questions, answers):
        with self.lock:
            self._need()
            questions = [q.strip() for q in questions]
            answers = [normalize_answer(a) for a in answers]
            if len(questions) != 3 or len(answers) != 3 or not all(questions):
                raise VaultError("Choose three questions.")
            if len({q.lower() for q in questions}) != 3:
                raise VaultError("Pick three different questions.")
            if any(len(a) < 3 for a in answers):
                raise VaultError("Each answer needs at least 3 letters or numbers.")
            meta = self._read_meta()
            self._unwrap(meta, password)
            slot = self._seal("\x1f".join(answers), QUESTION_ITERATIONS, self.master, AAD_QUESTIONS)
            slot["q"] = questions
            meta.setdefault("recovery", {})["questions"] = slot
            self._write_meta(meta)

    def remove_recovery(self, password, kind):
        with self.lock:
            self._need()
            meta = self._read_meta()
            self._unwrap(meta, password)
            meta.get("recovery", {}).pop(kind, None)
            self._write_meta(meta)

    def recover(self, kind, secret, new_password):
        """Reset the password with the recovery key or the answers, then unlock."""
        with self.lock:
            if len(new_password) < 8:
                raise VaultError("Use a new password of at least 8 characters.")
            meta = self._read_meta()
            slot = meta.get("recovery", {}).get(kind)
            if not slot:
                raise VaultError("This recovery method is not set up.")
            if kind == "key":
                master = self._open_slot(slot, normalize_key(secret), AAD_KEY)
            else:
                master = self._open_slot(slot, "\x1f".join(normalize_answer(a) for a in secret), AAD_QUESTIONS)
            if master is None:
                raise WrongPassword("That recovery key is not right." if kind == "key"
                                    else "One or more answers are not right.")
            self.instance.acquire()
            try:
                self._wrap(meta, master, new_password)
                self._write_meta(meta)
            except BaseException:
                self.instance.release()
                raise
            self._open(master)

    # ---------- index ----------
    def _decrypt_index(self, path):
        data = path.read_bytes()
        obj = json.loads(AESGCM(self.index_key).decrypt(data[:12], data[12:], b"drivevault/v1/index"))
        return obj

    def _load_index(self):
        best, copies, damaged, main = None, [], False, None
        for p in (self.root / "index.dvx", self.root / "index.dvx.bak"):
            try:
                obj = self._decrypt_index(p)
                copies.append(obj)
                if p.name == "index.dvx":
                    main = obj
                if best is None or obj.get("seq", 0) > best.get("seq", 0):
                    best = obj
            except FileNotFoundError:
                pass
            except Exception:
                damaged = True
        if best is None:
            raise VaultError("Vault index is missing or damaged.")
        self.entries, self.seq = best["entries"], best.get("seq", 0)
        self.index_h = best.get("h", 0)
        # blobs referenced by any copy; None = not sure (damaged copy or fell back to .bak), so delete nothing
        self._index_blobs = None if damaged or best is not main else {
            e["b"] for c in copies for e in c["entries"].values() if e.get("t") == "f" and e.get("b")}

    def save_index(self):
        with self.lock:
            self.seq += 1
            if self.hist_seq is None and self.master is not None:
                self.hist_seq = self._hist_last_seq()
            self.index_h = max(self.index_h, self.hist_seq or 0)
            raw = json.dumps({"v": 1, "seq": self.seq, "h": self.index_h, "entries": self.entries},
                             separators=(",", ":")).encode()
            nonce = os.urandom(12)
            data = nonce + AESGCM(self.index_key).encrypt(nonce, raw, b"drivevault/v1/index")
            cur, tmp = self.root / "index.dvx", self.root / "index.dvx.tmp"
            _fsync_write(tmp, data)
            if cur.exists():
                shutil.copyfile(cur, self.root / "index.dvx.bak")
            os.replace(tmp, cur)

    # ---------- tree ----------
    def _need(self):
        if not self.unlocked:
            raise VaultError("Vault is locked.")

    def children(self, parent):
        self._need()
        return [dict(id=k, **v) for k, v in self.entries.items() if v["p"] == parent]

    def path_of(self, eid):
        out = []
        while eid not in (ROOT, TRASH):
            e = self.entries.get(eid)
            if not e:
                raise VaultError("Item not found.")
            out.append({"id": eid, "name": e["n"]})
            eid = e["p"]
        top = {"id": eid, "name": "Vault" if eid == ROOT else "Trash"}
        return [top] + out[::-1]

    def in_trash(self, eid):
        while eid not in (ROOT, TRASH):
            e = self.entries.get(eid)
            if not e:
                return False
            eid = e["p"]
        return eid == TRASH

    def get(self, eid):
        self._need()
        e = self.entries.get(eid)
        if not e:
            raise VaultError("Item not found.")
        return e

    def find_child(self, parent, name):
        low = name.lower()
        for k, v in self.entries.items():
            if v["p"] == parent and v["n"].lower() == low:
                return k
        return None

    def unique_name(self, parent, name):
        if not self.find_child(parent, name):
            return name
        stem, dot, ext = name.rpartition(".")
        if not dot or not stem:
            stem, ext = name, ""
        n = 2
        while True:
            cand = f"{stem} ({n}){'.' + ext if ext else ''}"
            if not self.find_child(parent, cand):
                return cand
            n += 1

    def _check_parent(self, parent):
        if parent == TRASH or (parent != ROOT and self.in_trash(parent)):
            raise VaultError("Restore the folder from Trash before adding to it.")
        if parent != ROOT and self.get(parent)["t"] != "d":
            raise VaultError("Not a folder.")

    def mkdir(self, parent, name, save=True):
        with self.lock:
            self._need()
            self._check_parent(parent)
            name = clean_name(name)
            existing = self.find_child(parent, name)
            if existing:
                if self.entries[existing]["t"] == "d":
                    return existing
                raise VaultError(f"A file named '{name}' already exists.")
            eid = uuid.uuid4().hex
            self.entries[eid] = {"p": parent, "n": name, "t": "d", "s": 0, "m": int(time.time() * 1000)}
            if save:
                self.save_index()
            return eid

    def mkdirs(self, parent, parts):
        for part in parts:
            parent = self.mkdir(parent, part, save=False)
        return parent

    def rename(self, eid, name):
        with self.lock:
            e = self.get(eid)
            name = clean_name(name)
            other = self.find_child(e["p"], name)
            if other and other != eid:
                raise VaultError("That name is already used here.")
            e["n"] = name
            self.save_index()

    def move(self, eid, new_parent):
        with self.lock:
            e = self.get(eid)
            self._check_parent(new_parent)
            p = new_parent
            while p != ROOT:
                if p == eid:
                    raise VaultError("Can't move a folder into itself.")
                p = self.entries[p]["p"]
            e["n"] = self.unique_name(new_parent, e["n"]) if self.find_child(new_parent, e["n"]) != eid else e["n"]
            e["p"] = new_parent
            e.pop("op", None), e.pop("x", None), e.pop("o", None)
            self.save_index()

    def delete(self, ids):
        with self.lock:
            self._need()
            doomed, blobs = set(), []
            stack = [i for i in ids if i in self.entries]
            while stack:
                i = stack.pop()
                if i in doomed:
                    continue
                doomed.add(i)
                if self.entries[i]["t"] == "f":
                    blobs.append(self.entries[i]["b"])
                stack += [k for k, v in self.entries.items() if v["p"] == i]
            for i in doomed:
                del self.entries[i]
            self.save_index()
            for b in blobs:
                try:
                    self._blob_path(b).unlink()
                except OSError:
                    pass
            return len(doomed)

    # ---------- trash ----------
    def trash(self, ids):
        with self.lock:
            self._need()
            now, n = int(time.time() * 1000), 0
            for i in ids:
                e = self.entries.get(i)
                if not e or self.in_trash(i):
                    continue
                e["op"], e["p"], e["x"] = e["p"], TRASH, now
                n += 1
            self.save_index()
            return n

    def restore(self, ids):
        with self.lock:
            self._need()
            n = 0
            for i in ids:
                e = self.entries.get(i)
                if not e or e["p"] != TRASH:
                    continue
                dest = e.get("op", ROOT)
                if dest != ROOT and (dest not in self.entries or self.in_trash(dest)):
                    dest = ROOT
                e["p"] = None
                e["n"] = self.unique_name(dest, e["n"])
                e["p"] = dest
                e.pop("op", None), e.pop("x", None), e.pop("o", None)
                n += 1
            self.save_index()
            return n

    def empty_trash(self):
        return self.delete([k for k, v in self.entries.items() if v["p"] == TRASH])

    def reorder(self, parent, ids):
        """Custom order: ids is the new order of (some of) the items in `parent`."""
        with self.lock:
            self._need()
            n = 0
            for pos, i in enumerate(ids):
                e = self.entries.get(i)
                if e and e["p"] == parent:
                    e["o"] = pos
                    n += 1
            self.save_index()
            return n

    def trash_count(self):
        return sum(1 for v in self.entries.values() if v["p"] == TRASH)

    def search(self, q, limit=300):
        self._need()
        q = q.strip().lower()
        out = []
        for k, v in self.entries.items():
            if q in v["n"].lower() and not self.in_trash(k):
                out.append(dict(id=k, **v))
                if len(out) >= limit:
                    break
        return out

    # ---------- blobs ----------
    def _blob_path(self, blob):
        return self.root / "blobs" / blob[:2] / f"{blob}.dvb"

    def _file_key(self, blob):
        return _hmac(self.content_key, bytes.fromhex(blob))

    def _write_blob(self, src, progress=None):
        """Encrypt a readable stream into a new blob. Returns (blob_id, size)."""
        self._need()
        blob = os.urandom(16).hex()
        path = self._blob_path(blob)
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp = path.with_suffix(".dvb.tmp")
        aes = AESGCM(self._file_key(blob))
        total, i = 0, 0
        try:
            with open(tmp, "wb") as f:
                f.write(MAGIC)
                cur = _read_full(src, CHUNK)
                while True:
                    nxt = _read_full(src, CHUNK) if len(cur) == CHUNK else b""
                    final = not nxt
                    f.write(aes.encrypt(_nonce(i), cur, _aad(i, final)))
                    total += len(cur)
                    i += 1
                    if progress:
                        progress(len(cur))
                    if final:
                        break
                    cur = nxt
                f.flush()
                os.fsync(f.fileno())
            os.replace(tmp, path)
        except BaseException:
            try:
                tmp.unlink()
            except OSError:
                pass
            raise
        return blob, total

    def add_file(self, parent, name, src, mtime_ms=None, replace_id=None, progress=None):
        """Store a stream as a file. replace_id overwrites an existing file entry in place."""
        name = clean_name(name)
        blob, size = self._write_blob(src, progress)
        with self.lock:
            old_blob = None
            m = int(mtime_ms or time.time() * 1000)
            if replace_id and replace_id in self.entries:
                e = self.entries[replace_id]
                old_blob = e["b"]
                e.update(s=size, m=m, b=blob)
                eid = replace_id
            else:
                self._check_parent(parent)
                name = self.unique_name(parent, name)
                eid = uuid.uuid4().hex
                self.entries[eid] = {"p": parent, "n": name, "t": "f", "s": size, "m": m, "b": blob}
            self.save_index()
        if old_blob:
            try:
                self._blob_path(old_blob).unlink()
            except OSError:
                pass
        return eid

    def read_range(self, eid, start=0, end=None):
        """Yield decrypted bytes [start, end] (inclusive) of a file entry."""
        e = self.get(eid)
        if e["t"] != "f":
            raise VaultError("Not a file.")
        size, blob = e["s"], e["b"]
        if size == 0:
            return
        end = size - 1 if end is None else min(end, size - 1)
        if start > end:
            return
        aes = AESGCM(self._file_key(blob))
        n = nchunks(size)
        with open(self._blob_path(blob), "rb") as f:
            if f.read(4) != MAGIC:
                raise VaultError("File is damaged.")
            for i in range(start // CHUNK, end // CHUNK + 1):
                plen = min(CHUNK, size - i * CHUNK)
                f.seek(4 + i * (CHUNK + TAG))
                ct = f.read(plen + TAG)
                try:
                    pt = aes.decrypt(_nonce(i), ct, _aad(i, i == n - 1))
                except InvalidTag:
                    raise VaultError(f"'{e['n']}' is damaged or was tampered with.")
                s = start - i * CHUNK if i == start // CHUNK else 0
                t = end - i * CHUNK + 1 if i == end // CHUNK else plen
                yield pt[s:t]

    def tree_size(self, eid):
        """(bytes, files) under an entry, including the entry itself."""
        e = self.get(eid)
        if e["t"] == "f":
            return e["s"], 1
        size = files = 0
        for c in self.children(eid):
            a, b = self.tree_size(c["id"])
            size, files = size + a, files + b
        return size, files

    def export(self, eid, dest_dir, progress=None, unique=False):
        """Decrypt a file or folder into dest_dir. Returns {local_path: entry_id} for every file."""
        e = self.get(eid)
        out = {}
        target = Path(dest_dir) / e["n"]
        if unique and target.exists():
            stem, ext = os.path.splitext(e["n"])
            n = 2
            while (Path(dest_dir) / f"{stem} ({n}){ext}").exists():
                n += 1
            target = Path(dest_dir) / f"{stem} ({n}){ext}"
        if e["t"] == "f":
            with open(target, "wb") as f:
                for part in self.read_range(eid):
                    f.write(part)
                    if progress:
                        progress(len(part))
            os.utime(target, (e["m"] / 1000, e["m"] / 1000))
            out[str(target)] = eid
        else:
            target.mkdir(parents=True, exist_ok=True)
            for c in self.children(eid):
                out.update(self.export(c["id"], target, progress))
        return out

    # ---------- history (encrypted, append-only, one event per line) ----------
    @property
    def history_path(self):
        return self.root / "history.dvl"

    def _hist_key(self):
        return _hmac(self.master, b"drivevault/v1/history-key")

    def _hist_last_seq(self):
        try:
            with open(self.history_path, "rb") as f:
                f.seek(0, 2)
                size = f.tell()
                f.seek(max(0, size - 65536))
                lines = [l for l in f.read().splitlines() if l.strip()]
        except FileNotFoundError:
            return 0
        aes = AESGCM(self._hist_key())
        for line in reversed(lines):
            try:
                raw = _unb64(line)
                seq = struct.unpack(">Q", raw[:8])[0]
                aes.decrypt(raw[8:20], raw[20:], b"drivevault/v1/history" + raw[:8])
                return seq
            except Exception:
                continue
        return 0

    def history_append(self, event):
        """event: dict. Each line = b64(seq(8) || nonce(12) || AES-GCM(json, aad=prefix||seq))."""
        with self.lock:
            self._need()
            if self.hist_seq is None:
                self.hist_seq = self._hist_last_seq()
            self.hist_seq += 1
            seq = struct.pack(">Q", self.hist_seq)
            nonce = os.urandom(12)
            ct = AESGCM(self._hist_key()).encrypt(nonce, json.dumps(event, separators=(",", ":")).encode(),
                                                  b"drivevault/v1/history" + seq)
            with open(self.history_path, "ab") as f:
                f.write(_b64(seq + nonce + ct).encode() + b"\n")
                f.flush()
                os.fsync(f.fileno())

    def history_read(self):
        """Return (events, problems). Verifies every line and that sequence numbers have no gaps."""
        self._need()
        events, problems, expect = [], [], 1
        aes = AESGCM(self._hist_key())
        try:
            data = self.history_path.read_bytes()
        except FileNotFoundError:
            return [], []
        for n, line in enumerate(data.splitlines(), 1):
            if not line.strip():
                continue
            try:
                raw = _unb64(line)
                seq = struct.unpack(">Q", raw[:8])[0]
                ev = json.loads(aes.decrypt(raw[8:20], raw[20:], b"drivevault/v1/history" + raw[:8]))
            except Exception:
                problems.append(f"Line {n} is damaged or was altered.")
                continue
            if seq != expect:
                if seq > expect:
                    problems.append(f"Event {expect} is missing." if seq - 1 == expect else f"Events {expect}–{seq - 1} are missing.")
                else:
                    problems.append(f"Event {seq} is out of order.")
            expect = seq + 1
            ev["seq"] = seq
            events.append(ev)
        if expect - 1 < self.index_h:
            problems.append(f"The newest {self.index_h - (expect - 1)} event(s) were removed from the end of the history.")
        return events, problems
