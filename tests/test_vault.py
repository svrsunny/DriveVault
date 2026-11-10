"""Desktop vault core tests. Run:  python tests/test_vault.py   (needs: pip install cryptography)"""
import io, os, random, shutil, sys, tempfile
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "desktop", "app"))
import vaultcore as vc
from vaultcore import Vault, CHUNK, WrongPassword, VaultError

vc.ITERATIONS = vc.KEY_ITERATIONS = vc.QUESTION_ITERATIONS = 1000   # fast KDF for tests only
vc.ARGON2 = {"m": 1024, "t": 1, "p": 1}


def test_roundtrip_ranges_tamper():
    d = tempfile.mkdtemp(); root = os.path.join(d, "vault")
    v = Vault(root); v.create("correct horse")
    data = {}
    for sz in [0, 1, 100, CHUNK - 1, CHUNK, CHUNK + 1, 3 * CHUNK + 7]:
        b = os.urandom(sz); eid = v.add_file("root", f"f{sz}.bin", io.BytesIO(b)); data[eid] = b
        assert b"".join(v.read_range(eid)) == b
        for _ in range(20 if sz else 0):
            s = random.randrange(sz); e = random.randrange(s, sz)
            assert b"".join(v.read_range(eid, s, e)) == b[s:e + 1]
        assert os.path.getsize(v._blob_path(v.entries[eid]["b"])) == 4 + vc.nchunks(sz) * 16 + sz
    v.lock_now(); v2 = Vault(root)
    try: v2.unlock("wrong"); raise AssertionError("wrong password accepted")
    except WrongPassword: pass
    v2.unlock("correct horse")
    for eid, b in data.items(): assert b"".join(v2.read_range(eid)) == b
    eid = next(k for k, b in data.items() if len(b) == 3 * CHUNK + 7)
    p = v2._blob_path(v2.entries[eid]["b"]); raw = bytearray(open(p, "rb").read()); raw[4 + CHUNK + 16 + 5] ^= 1; open(p, "wb").write(raw)
    try: b"".join(v2.read_range(eid)); raise AssertionError("tampering not detected")
    except VaultError: pass
    eid2 = next(k for k, b in data.items() if len(b) == CHUNK + 1)
    p = v2._blob_path(v2.entries[eid2]["b"]); open(p, "wb").write(open(p, "rb").read()[:4 + CHUNK + 16]); v2.entries[eid2]["s"] = CHUNK
    try: b"".join(v2.read_range(eid2)); raise AssertionError("truncation not detected")
    except VaultError: pass
    v2.lock_now(); shutil.rmtree(d)


def test_tree_trash_order_history():
    d = tempfile.mkdtemp(); v = Vault(os.path.join(d, "vault")); v.create("password123")
    docs = v.mkdir("root", "Docs"); f1 = v.add_file(docs, "a.jpg", io.BytesIO(b"A")); f2 = v.add_file("root", "b.txt", io.BytesIO(b"B"))
    assert v.trash([docs, f2]) == 2 and v.in_trash(f1)
    v.mkdir("root", "Docs")
    assert v.restore([docs]) == 1 and v.entries[docs]["n"] == "Docs (2)"
    blob = v._blob_path(v.entries[f2]["b"]); assert v.empty_trash() == 1 and not os.path.exists(blob)
    a = v.add_file("root", "x.txt", io.BytesIO(b"x")); b = v.add_file("root", "y.txt", io.BytesIO(b"y"))
    v.reorder("root", [b, a]); assert v.entries[b]["o"] == 0 and v.entries[a]["o"] == 1
    v.history_append({"t": 1, "ev": "created"}); v.history_append({"t": 2, "ev": "encrypted"})
    ev, problems = v.history_read(); assert len(ev) == 2 and not problems
    lines = open(v.history_path).read().splitlines(); open(v.history_path, "w").write(lines[1] + "\n")
    assert v.history_read()[1], "removed history entry not detected"
    v.lock_now(); shutil.rmtree(d)


def test_recovery():
    d = tempfile.mkdtemp(); v = Vault(os.path.join(d, "vault")); v.create("password123")
    key = v.set_recovery_key("password123")
    v.set_questions("password123", ["Q1?", "Q2?", "Q3?"], ["Blue Bell School!", "Delhi", "Rex"])
    v.lock_now()
    try: v.recover("key", "AAAAA-AAAAA-AAAAA-AAAAA-AAAAA", "newpass123"); raise AssertionError
    except WrongPassword: pass
    v.recover("key", key.lower().replace("-", " "), "newpass123"); v.lock_now()
    v.recover("questions", ["blue-bell school", " DELHI ", "rex."], "third pass 1"); v.lock_now()
    v.unlock("third pass 1")
    v.lock_now(); shutil.rmtree(d)


def test_kdf_upgrade_and_key_wipe():
    d = tempfile.mkdtemp(); root = os.path.join(d, "vault")
    vc.NEW_KDF = "pbkdf2-sha256"                       # a vault made before 1.8
    try:
        v = Vault(root); v.create("old password 1")
    finally:
        vc.NEW_KDF = "argon2id"
    f = v.add_file("root", "a.txt", io.BytesIO(b"data")); v.lock_now()
    assert v.kdf_name() == "pbkdf2-sha256"
    v.unlock("old password 1"); v.change_password("old password 1", "new password 1"); v.lock_now()
    assert v.kdf_name() == "argon2id" and set(v._read_meta()["kdf"]) == {"name", "m", "t", "p", "salt"}
    try: v.unlock("old password 1"); raise AssertionError("old password still works")
    except WrongPassword: pass
    v.unlock("new password 1"); assert b"".join(v.read_range(f)) == b"data"
    keys = (v.master, v.index_key, v.content_key)
    v.lock_now()
    assert all(k == bytearray(len(k)) for k in keys), "keys not wiped on lock"
    # the argon2-cffi fallback must give the same key as cryptography's Argon2id
    try:
        from argon2.low_level import hash_secret_raw, Type
        from cryptography.hazmat.primitives.kdf.argon2 import Argon2id
        assert Argon2id(salt=b"s" * 16, length=32, iterations=1, lanes=1, memory_cost=1024).derive(b"pw") == \
            hash_secret_raw(b"pw", b"s" * 16, 1, 1024, 1, 32, Type.ID)
    except ImportError:
        print("  (skipped argon2-cffi cross-check: not installed)")
    shutil.rmtree(d)


def test_single_instance_and_leftovers():
    d = tempfile.mkdtemp(); root = os.path.join(d, "vault")
    v = Vault(root); v.create("password123")
    keep = v.add_file("root", "keep.txt", io.BytesIO(b"keep"))
    other = Vault(root)
    try: other.unlock("password123"); raise AssertionError("second instance opened the same vault")
    except vc.AlreadyOpen: pass
    assert not other.unlocked
    v.lock_now()
    # leftovers of an interrupted write: tmp files and a blob no index refers to
    orphan = os.path.join(root, "blobs", "ab", "ab" + "0" * 30 + ".dvb"); os.makedirs(os.path.dirname(orphan), exist_ok=True)
    for p in (orphan, orphan + ".tmp", os.path.join(root, "index.dvx.tmp")): open(p, "wb").write(b"x")
    other.unlock("password123")
    assert other.cleaned == (2, 1) and not os.path.exists(orphan)
    assert b"".join(other.read_range(keep)) == b"keep"
    other.lock_now()
    # with a damaged index copy nothing but tmp files may be deleted
    open(orphan, "wb").write(b"x"); open(os.path.join(root, "index.dvx.bak"), "wb").write(b"garbage")
    other.unlock("password123")
    assert other.cleaned == (0, 0) and os.path.exists(orphan)
    other.lock_now()
    try: Vault(root).create("password123"); raise AssertionError
    except VaultError: pass
    os.remove(os.path.join(root, "vault.json"))
    try: Vault(root).create("password123"); raise AssertionError("created a vault over existing vault data")
    except VaultError: pass
    shutil.rmtree(d)


if __name__ == "__main__":
    for name, fn in list(globals().items()):
        if name.startswith("test_"):
            fn(); print("ok  ", name)
    print("ALL TESTS PASSED")
