"""Desktop server API tests. Run:  python tests/test_api.py"""
import json, os, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from dvserver import Server, PASSWORD, dv, vc


def history(s):
    return [e["ev"] for e in s.api("/api/history")["events"]]


def test_auth_and_host_check():
    s = Server()
    try:
        assert s.request("GET", "/api/state", cookie=False)[0] == 403, "API reachable without the session cookie"
        assert s.request("GET", "/api/state", headers={"Host": "evil.example:80"})[0] == 403, "Host header not checked (DNS rebinding)"
        assert s.request("POST", "/api/unlock", {"password": "x"}, cookie=False)[0] == 403
        assert s.request("GET", "/?t=wrong-token", cookie=False)[0] == 403
        st, h, _ = s.request("GET", f"/?t={s.token}", cookie=False)
        assert st == 303 and "HttpOnly" in h["Set-Cookie"] and "SameSite=Strict" in h["Set-Cookie"]
        st, h, _ = s.request("GET", "/")
        assert st == 200 and "default-src 'self'" in h["Content-Security-Policy"]
        state = s.api("/api/state")
        assert state["version"] == dv.APP_VERSION and not state["exists"]
    finally:
        s.close()


def test_create_unlock_wrong_password():
    s = Server()
    try:
        s.api("/api/create", {"password": PASSWORD})
        assert s.api("/api/state")["kdf"] == "argon2id"
        s.api("/api/lock", {})
        t = time.time()
        assert "Wrong password" in s.api("/api/unlock", {"password": "nope"}, expect=401)["error"]
        assert time.time() - t >= 0.9, "no delay after a wrong password"
        s.api("/api/unlock", {"password": PASSWORD})
        # failed attempts go to the plain log; the encrypted history notes them at the next unlock (FORMAT.md)
        assert any("UNLOCK FAILED" in l for l in s.api("/api/plainlog")["lines"])
        last = s.api("/api/history")["events"][0]
        assert last["ev"] == "unlocked" and "1 failed attempt" in last["note"], last
    finally:
        s.close()


def test_upload_and_range_requests():
    s = Server()
    try:
        s.api("/api/create", {"password": PASSWORD})
        data = os.urandom(vc.CHUNK * 2 + 1234)
        fid = s.upload("big.bin", data)
        st, h, body = s.request("GET", f"/api/file?id={fid}")
        assert st == 200 and body == data and h["Cache-Control"] == "no-store"
        st, h, body = s.request("GET", f"/api/file?id={fid}", headers={"Range": f"bytes={vc.CHUNK - 10}-{vc.CHUNK + 9}"})
        assert st == 206 and body == data[vc.CHUNK - 10:vc.CHUNK + 10] and h["Content-Range"] == f"bytes {vc.CHUNK - 10}-{vc.CHUNK + 9}/{len(data)}"
        st, _, body = s.request("GET", f"/api/file?id={fid}", headers={"Range": "bytes=-100"})
        assert st == 206 and body == data[-100:]
        assert s.request("GET", f"/api/file?id={fid}", headers={"Range": f"bytes={len(data) + 5}-"})[0] == 416
        st, h, body = s.request("HEAD", f"/api/file?id={fid}")
        assert st == 200 and h["Content-Length"] == str(len(data)) and body == b""
        st, h, _ = s.request("GET", f"/api/file?id={fid}&dl=1")
        assert h["Content-Disposition"].startswith("attachment")
        # thumbnails (grid view) are not logged one by one; viewing and downloading are
        s.request("GET", f"/api/file?id={fid}&thumb=1")
        ev = history(s)
        assert "downloaded" in ev and ev.count("viewed") == 1, ev
        # a name with non-ASCII characters
        tid = s.upload("తెలుగు ఫైల్.txt", "నమస్తే".encode())
        assert s.request("GET", f"/api/file?id={tid}")[2] == "నమస్తే".encode()
    finally:
        s.close()


def test_tree_trash_restore_delete():
    s = Server()
    try:
        s.api("/api/create", {"password": PASSWORD})
        docs = s.api("/api/mkdir", {"parent": "root", "name": "Docs"})["id"]
        a = s.upload("a.txt", b"A", docs); b = s.upload("b.txt", b"B")
        s.api("/api/rename", {"id": b, "name": "b2.txt"})
        s.api("/api/move", {"ids": [b], "parent": docs})
        names = lambda fid: sorted(i["n"] for i in s.api(f"/api/list?id={fid}")["items"])
        assert names(docs) == ["a.txt", "b2.txt"]
        assert [i["n"] for i in s.api("/api/search?q=b2")["items"]] == ["b2.txt"]
        s.api("/api/reorder", {"parent": docs, "ids": [b, a]})
        assert {i["n"]: i["o"] for i in s.api(f"/api/list?id={docs}")["items"]} == {"b2.txt": 0, "a.txt": 1}
        assert s.api("/api/trash", {"ids": [a]})["trash"] == 1
        assert names("trash") == ["a.txt"] and names(docs) == ["b2.txt"]
        s.api("/api/restore", {"ids": [a]})
        assert names(docs) == ["a.txt", "b2.txt"] and s.api("/api/state")["trash"] == 0
        s.api("/api/trash", {"ids": [docs]})
        st, _, out = s.request("PUT", f"/api/upload?parent={docs}&name=x.txt", raw=b"x")
        assert st == 400 and b"Restore the folder" in out, "upload into a trashed folder was accepted"
        assert s.api("/api/empty-trash", {})["deleted"] == 3 and names("root") == []
        assert "trash_emptied" in history(s)
    finally:
        s.close()


def test_recovery_key_and_password_change():
    s = Server()
    try:
        s.api("/api/create", {"password": PASSWORD})
        key = s.api("/api/security/key", {"password": PASSWORD})["key"]
        s.api("/api/passwd", {"old": PASSWORD, "new": "second password 2"})
        s.api("/api/lock", {})
        assert "not right" in s.api("/api/recover", {"kind": "key", "key": "AAAAA-AAAAA-AAAAA-AAAAA-AAAAA", "password": "third pass 33"}, expect=401)["error"]
        s.api("/api/recover", {"kind": "key", "key": key.lower(), "password": "third pass 33"})
        assert s.api("/api/state")["unlocked"]
        s.api("/api/lock", {})
        s.api("/api/unlock", {"password": "third pass 33"})
        ev = history(s)
        assert "password_changed" in ev and "password_reset" in ev
        assert any("RECOVERY FAILED" in l for l in s.api("/api/plainlog")["lines"])
    finally:
        s.close()


def test_open_in_app_writes_back_on_lock():
    s = Server()
    try:
        s.api("/api/create", {"password": PASSWORD})
        fid = s.upload("note.txt", b"v1")
        s.api("/api/open", {"id": fid})
        local = s.opened[-1]
        time.sleep(1.1)
        open(local, "wb").write(b"v2 edited")
        assert s.api("/api/lock", {})["saved"] == ["note.txt"]
        assert not os.path.exists(local), "working copy not wiped on lock"
        s.api("/api/unlock", {"password": PASSWORD})
        assert s.request("GET", f"/api/file?id={fid}")[2] == b"v2 edited"
    finally:
        s.close()


def test_stale_session_recovery():
    s = Server()
    try:
        s.api("/api/create", {"password": PASSWORD})
        fid = s.upload("report.txt", b"draft")
        s.api("/api/open", {"id": fid})
        local = s.opened[-1]
        time.sleep(1.1)
        open(local, "wb").write(b"final")
        # the app dies: no sync, no wipe (simulated by dropping the session and locking only the vault)
        s.app.vault.lock_now(); s.app.session = dv.Session(s.app.vault)
        assert s.api("/api/unlock", {"password": PASSWORD})["stale"] == 1
        assert s.api("/api/stale", {"action": "save"})["saved"] == ["report.txt"]
        assert s.request("GET", f"/api/file?id={fid}")[2] == b"final" and not os.path.exists(local)
        assert "saved_back" in history(s)
    finally:
        s.close()


def test_second_window_cannot_open_same_vault():
    s = Server()
    try:
        s.api("/api/create", {"password": PASSWORD})
        other = dv.App(s.vault_dir)
        try:
            other.vault.unlock(PASSWORD); raise AssertionError("second instance unlocked the same vault")
        except vc.AlreadyOpen as e:
            assert "already open" in str(e)
        s.api("/api/lock", {})
        other.vault.unlock(PASSWORD); other.vault.lock_now()     # free again after lock
    finally:
        s.close()


def test_history_export_and_settings():
    s = Server()
    try:
        s.api("/api/create", {"password": PASSWORD})
        s.upload("secret-name.txt", b"x")
        st, h, csv = s.request("GET", "/api/history.csv")
        assert st == 200 and b"secret-name.txt" in csv
        assert not any("secret-name" in l for l in s.api("/api/plainlog")["lines"]), "names in plain log by default"
        s.api("/api/settings", {"plainLogNames": True})
        s.upload("visible.txt", b"x")
        assert any("visible.txt" in l for l in s.api("/api/plainlog")["lines"])
    finally:
        s.close()


def test_errors_are_plain_json():
    s = Server()
    try:
        s.api("/api/create", {"password": PASSWORD})
        r = s.api("/api/rename", {"name": "x"}, expect=500)               # missing id: a bug-style error
        assert "Something went wrong" in r["error"]
        assert "Item not found" in s.api("/api/rename", {"id": "nope", "name": "x"}, expect=400)["error"]
        assert s.api("/api/state")["unlocked"], "server still works after an error"
    finally:
        s.close()


if __name__ == "__main__":
    failed = 0
    for name, fn in list(globals().items()):
        if name.startswith("test_"):
            try:
                fn(); print("ok  ", name)
            except Exception as e:
                failed += 1; print("FAIL", name, "-", type(e).__name__, e)
    print("ALL API TESTS PASSED" if not failed else f"{failed} API TEST(S) FAILED")
    sys.exit(1 if failed else 0)
