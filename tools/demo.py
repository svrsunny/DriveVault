"""DriveVault demo: a vault filled with fictional files, served on 127.0.0.1, for trying the app and taking screenshots.

    python tools/demo.py                 # create (first run) and open the demo in the browser
    python tools/demo.py --reset         # delete the demo vault and start again
    python tools/demo.py --dir D:\\demo   # keep the demo somewhere else

Password: "demo password". The demo lives in its own folder (default: <temp>/drivevault-demo) and never touches a
real vault. The computer name, user and drive shown in the app are fictional ("demo-pc", "demo", "DEMO USB (E:)").
Decrypted working copies go into the demo folder too.
"""
import argparse, http.client, io, json, math, os, secrets, shutil, struct, sys, tempfile, threading, time, webbrowser, zipfile, zlib
from http.server import ThreadingHTTPServer
from pathlib import Path
from urllib.parse import quote

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "desktop" / "app"))
import drivevault as dv  # noqa: E402

PASSWORD = "demo password"
MARKER = "DRIVEVAULT-DEMO.txt"


# ---------------------------------------------------------------- fictional machine and drive
def use_fictional_machine(folder):
    dv.HOST, dv.OS_NAME, dv.USER = "demo-pc", "Windows", "demo"

    def drive_info(base=None):
        has = (Path(folder) / "DriveVault" / "vault" / "vault.json").exists()
        return {"base": "E:\\", "root": "E:\\", "label": "DEMO USB", "fs": "exfat", "kind": "removable",
                "total": 64_023_253_000, "free": 51_381_000_000, "phoneOk": True, "hasVault": has, "isAppDrive": True}
    dv.drive_info = drive_info
    dv.list_drives = lambda: [drive_info()]
    dv.find_vaults = lambda *a, **k: []
    work = Path(folder) / "work"
    dv.work_root = lambda: (work.mkdir(parents=True, exist_ok=True), work)[1]


# ---------------------------------------------------------------- generated content
def png(w, h, pixel):
    raw = bytearray()
    for y in range(h):
        raw.append(0)
        for x in range(w):
            raw += bytes(pixel(x / w, y / h))
    chunk = lambda t, d: struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b"")


def mix(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * max(0.0, min(1.0, t))) for i in range(3))


def sunset(x, y):
    if y > 0.62 + 0.06 * math.sin(x * 9):                                 # hills
        return mix((70, 40, 70), (25, 15, 35), (y - 0.6) * 2.5)
    if (x - 0.62) ** 2 + ((y - 0.55) * 1.5) ** 2 < 0.012:                  # sun
        return (255, 214, 120)
    return mix((255, 140, 80), (60, 40, 120), (0.65 - y) * 1.6)


def lake(x, y):
    ridge = 0.38 + 0.12 * abs(math.sin(x * 5.5)) + 0.04 * math.sin(x * 23)
    if y < ridge:
        return (240, 245, 250) if y > ridge - 0.04 and ridge < 0.47 else mix((150, 200, 240), (220, 235, 250), y / ridge)
    if y < 0.62:
        return mix((70, 100, 90), (40, 70, 60), (y - ridge) * 4)
    return mix((60, 130, 160), (20, 60, 90), (y - 0.62) * 2.6)


def dunes(x, y):
    d = 0.55 + 0.1 * math.sin(x * 6 + 1) + 0.05 * math.sin(x * 15)
    if y < d:
        return mix((120, 180, 235), (250, 220, 170), y / d)
    shade = 0.5 + 0.5 * math.sin((x * 8 + y * 12))
    return mix((205, 140, 70), (240, 190, 110), shade)


def forest(x, y):
    tree = abs(((x * 14) % 1) - 0.5) * 2 < (y - 0.25) * 1.3 and y > 0.25
    if tree:
        return mix((30, 90, 50), (10, 50, 25), y)
    if y > 0.85:
        return (90, 70, 45)
    return mix((200, 230, 210), (120, 170, 140), y)


def docx(paragraphs):
    b = io.BytesIO()
    body = "".join(f"<w:p><w:r><w:t xml:space=\"preserve\">{p}</w:t></w:r></w:p>" for p in paragraphs)
    with zipfile.ZipFile(b, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("[Content_Types].xml", '<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
                   '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
                   '<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>')
        z.writestr("_rels/.rels", '<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
                   '<Relationship Id="r1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>')
        z.writestr("word/document.xml", '<?xml version="1.0"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">'
                   f"<w:body>{body}</w:body></w:document>")
    return b.getvalue()


FILES = {   # folder -> [(name, bytes)]; every name and number here is made up
    "Travel": [("alpine-lake.png", lambda: png(480, 320, lake)), ("coast-sunset.png", lambda: png(480, 320, sunset)),
               ("desert-dunes.png", lambda: png(480, 320, dunes)), ("forest-trail.png", lambda: png(480, 320, forest)),
               ("itinerary.txt", lambda: b"Day 1  Train to the coast, check in at the guesthouse\nDay 2  Cliff walk, harbour market\n"
                                         b"Day 3  Lake day: boat at 10:00, picnic\nDay 4  Back home\n")],
    "Recipes": [("tomato-sauce.txt", lambda: b"Tomato sauce\n\n2 tins of tomatoes\n1 onion, 2 cloves of garlic\nOlive oil, salt, basil\n\n"
                                             b"Fry the onion slowly, add garlic, then tomatoes. Simmer 30 minutes.\n"),
                ("shopping-list.csv", lambda: b"item,quantity,shop\ntomatoes,2 tins,market\nbasil,1 bunch,market\nolive oil,1 bottle,grocer\n")],
    "Work": [("project-plan.docx", lambda: docx(["Project plan (example)", "Goal: tidy up the shared photo archive.",
                                                 "Week 1: collect albums. Week 2: remove duplicates. Week 3: back up to the USB drive."])),
             ("meeting-notes.txt", lambda: b"Notes - planning call\n- Decide on folder names\n- Ask everyone for their photos by Friday\n")],
    "": [("budget-example.csv", lambda: b"month,income,rent,food,savings\nJan,2500,900,420,300\nFeb,2500,900,390,350\nMar,2600,900,410,400\n"),
         ("welcome.txt", lambda: b"This is a DriveVault demo vault. Every file here is made up.\n")],
}


class Client:
    def __init__(self, port, token):
        self.port = port
        c = self._conn(); c.request("GET", f"/?t={token}", headers={"Host": f"127.0.0.1:{port}"})
        r = c.getresponse(); r.read()
        self.cookie = r.getheader("Set-Cookie").split(";")[0]

    def _conn(self):
        return http.client.HTTPConnection("127.0.0.1", self.port, timeout=60)

    def call(self, method, path, body=None, raw=None):
        c = self._conn()
        data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
        c.request(method, path, data, {"Host": f"127.0.0.1:{self.port}", "Cookie": self.cookie, "Content-Length": str(len(data or b""))})
        r = c.getresponse(); out = r.read()
        if r.status != 200:
            raise RuntimeError(f"{path}: {r.status} {out[:200]!r}")
        return json.loads(out) if out[:1] == b"{" else out


def fill(c):
    """Create the demo vault through the app's own API, so history and the plain log look like real use."""
    c.call("POST", "/api/create", {"password": PASSWORD})
    c.call("POST", "/api/security/key", {"password": PASSWORD})
    ids = {}
    for folder, files in FILES.items():
        parent = "root" if not folder else c.call("POST", "/api/mkdir", {"parent": "root", "name": folder})["id"]
        for name, make in files:
            ids[name] = c.call("PUT", f"/api/upload?parent={parent}&name={quote(name)}", raw=make())["id"]
        ids[folder] = parent
    c.call("POST", "/api/mkdir", {"parent": "root", "name": "Archive"})
    old = c.call("PUT", "/api/upload?parent=root&name=old-draft.txt", raw=b"An old draft.\n")["id"]
    c.call("POST", "/api/trash", {"ids": [old]})
    order = ["coast-sunset.png", "alpine-lake.png", "forest-trail.png", "desert-dunes.png", "itinerary.txt"]
    c.call("POST", "/api/reorder", {"parent": ids["Travel"], "ids": [ids[n] for n in order]})
    c.call("GET", f"/api/file?id={ids['coast-sunset.png']}")              # shows up as "viewed" in the history
    c.call("POST", "/api/rename", {"id": ids["meeting-notes.txt"], "name": "planning-notes.txt"})
    return ids


def start(folder, port=0):
    """Serve the demo vault in this process. Returns (app, server, port, token)."""
    folder = Path(folder)
    use_fictional_machine(folder)
    vault_dir = folder / "DriveVault" / "vault"
    vault_dir.mkdir(parents=True, exist_ok=True)
    (folder / MARKER).write_text("Fictional DriveVault demo data. Safe to delete (python tools/demo.py --reset).\n")
    app = dv.App(str(vault_dir))
    token = secrets.token_urlsafe(24)
    httpd = ThreadingHTTPServer(("127.0.0.1", port), None)
    port = httpd.server_address[1]
    httpd.RequestHandlerClass = dv.make_handler(app, token, port)
    httpd.daemon_threads = True
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    if not app.vault.exists:
        fill(Client(port, token))
        app.lock(why="demo ready")
    return app, httpd, port, token


def default_dir():
    return Path(tempfile.gettempdir()) / "drivevault-demo"


def main():
    ap = argparse.ArgumentParser(description="DriveVault demo with fictional data")
    ap.add_argument("--dir", default=str(default_dir()), help="demo folder (default: %(default)s)")
    ap.add_argument("--port", type=int, default=8799)
    ap.add_argument("--reset", action="store_true", help="delete the demo vault first")
    ap.add_argument("--no-browser", action="store_true")
    a = ap.parse_args()
    folder = Path(a.dir)
    if folder.exists() and any(folder.iterdir()) and not (folder / MARKER).exists():
        sys.exit(f"{folder} is not empty and is not a DriveVault demo folder. Choose another --dir.")
    if a.reset and folder.exists():
        shutil.rmtree(folder)
    app, httpd, port, token = start(folder, a.port)
    url = f"http://127.0.0.1:{port}/?t={token}"
    print(f"DriveVault demo: {url}\nPassword: {PASSWORD}\nFolder:   {folder}\nCtrl+C to stop.")
    if not a.no_browser:
        webbrowser.open(url)
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        app.lock(why="demo stopped")


if __name__ == "__main__":
    main()
