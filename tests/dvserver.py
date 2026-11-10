"""Test harness: runs the desktop server in-process on a temporary vault. Nothing is opened on the desktop:
"Open in app" / "Show in folder" are recorded instead, and working copies go to a temporary folder."""
import http.client, json, os, shutil, sys, tempfile, threading
from http.server import ThreadingHTTPServer
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "desktop", "app"))
import vaultcore as vc
import drivevault as dv

vc.ITERATIONS = vc.KEY_ITERATIONS = vc.QUESTION_ITERATIONS = 1000   # fast KDFs for tests only
vc.ARGON2 = {"m": 1024, "t": 1, "p": 1}
PASSWORD = "test password 1"


class Server:
    def __init__(self):
        self.dir = tempfile.mkdtemp(prefix="dvtest-")
        self.work = os.path.join(self.dir, "work")
        self.opened = []                                        # paths "opened in an app"
        self.clipboard_in, self.clipboard_out = [], []          # the OS clipboard, as file paths
        dv.work_root = lambda: (os.makedirs(self.work, exist_ok=True), dv.Path(self.work))[1]
        dv.open_with_default_app = self.opened.append
        dv.reveal_in_file_manager = self.opened.append
        dv.find_player = lambda: None
        dv.clipboard_get_files = lambda: list(self.clipboard_in)
        dv.clipboard_set_files = lambda paths: self.clipboard_out.extend(paths)
        self.home = os.path.join(self.dir, "home")              # "Copy to computer" writes to ~/Downloads
        os.makedirs(os.path.join(self.home, "Downloads"))
        os.environ["USERPROFILE"] = os.environ["HOME"] = self.home
        self.vault_dir = os.path.join(self.dir, "drive", "DriveVault", "vault")
        os.makedirs(self.vault_dir)
        self.app = dv.App(self.vault_dir)
        self.token = "test-token"
        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), None)
        self.port = self.httpd.server_address[1]
        self.httpd.RequestHandlerClass = dv.make_handler(self.app, self.token, self.port)
        self.httpd.daemon_threads = True
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()
        self.url = f"http://127.0.0.1:{self.port}"
        self.cookie = self.request("GET", f"/?t={self.token}")[1].get("Set-Cookie", "").split(";")[0]

    def request(self, method, path, body=None, raw=None, headers=None, cookie=True):
        """-> (status, headers, body bytes)"""
        c = http.client.HTTPConnection("127.0.0.1", self.port, timeout=30)
        data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
        h = {"Host": f"127.0.0.1:{self.port}", "Content-Length": str(len(data or b""))}
        if cookie and getattr(self, "cookie", None):
            h["Cookie"] = self.cookie
        h.update(headers or {})
        c.request(method, path, data, h)
        r = c.getresponse()
        out = r.status, dict(r.getheaders()), r.read()
        c.close()
        return out

    def api(self, path, body=None, method=None, expect=200):
        st, _, data = self.request(method or ("POST" if body is not None else "GET"), path, body)
        obj = json.loads(data) if data[:1] in (b"{", b"[") else data
        assert st == expect, f"{path}: HTTP {st} {obj!r}"
        return obj

    def upload(self, name, data, parent="root"):
        st, _, out = self.request("PUT", f"/api/upload?parent={parent}&name={dv.quote(name)}", raw=data)
        assert st == 200, out
        return json.loads(out)["id"]

    def close(self):
        try:
            self.app.lock(why="test end")
        except Exception:
            pass
        self.httpd.shutdown()
        self.httpd.server_close()
        shutil.rmtree(self.dir, ignore_errors=True)
