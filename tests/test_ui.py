"""Browser tests for the desktop UI (regression checklist). Run:  python tests/test_ui.py [--headed]
Needs:  pip install playwright  &&  python -m playwright install chromium"""
import io, json, os, re, struct, sys, time, wave, zipfile, zlib
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from dvserver import Server, PASSWORD, dv
from playwright.sync_api import sync_playwright, expect

expect.set_options(timeout=10_000)


def png(w, h, rgb):
    raw = b"".join(b"\0" + bytes(rgb) * w for _ in range(h))
    chunk = lambda t, d: struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b"")


def wav():
    b = io.BytesIO()
    with wave.open(b, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(8000); w.writeframes(b"\0\0" * 8000)
    return b.getvalue()


def docx(text):
    b = io.BytesIO()
    with zipfile.ZipFile(b, "w") as z:
        z.writestr("[Content_Types].xml", '<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
                   '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
                   '<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>')
        z.writestr("_rels/.rels", '<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
                   '<Relationship Id="r1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>')
        z.writestr("word/document.xml", '<?xml version="1.0"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">'
                   f'<w:body><w:p><w:r><w:t>{text}</w:t></w:r></w:p></w:body></w:document>')
    return b.getvalue()


def pdf():
    objs = ["<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] >>"]
    out, offs = b"%PDF-1.4\n", []
    for i, o in enumerate(objs, 1):
        offs.append(len(out)); out += f"{i} 0 obj {o} endobj\n".encode()
    x = len(out)
    out += f"xref\n0 {len(objs) + 1}\n0000000000 65535 f \n".encode() + b"".join(f"{o:010d} 00000 n \n".encode() for o in offs)
    return out + f"trailer << /Size {len(objs) + 1} /Root 1 0 R >>\nstartxref\n{x}\n%%EOF\n".encode()


class UI:
    def __init__(self, page, s):
        self.p, self.s = page, s

    def names(self):
        return self.p.locator("#list .row.item .nm").all_inner_texts()

    def row(self, name):
        return self.p.locator("#list .row.item", has=self.p.locator(".nm", has_text=re.compile(f"^{re.escape(name)}$")))

    def pick(self, name, modifiers=None):
        """Select a row the way a user does: click it outside the name (clicking the name opens it)."""
        self.row(name).locator(".mod").click(modifiers=modifiers or [])

    def wait_names(self, *want, exact=False):
        self.p.wait_for_function("w => { const n = [...document.querySelectorAll('#list .row.item .nm')].map(x => x.textContent);"
                                 " return w.e ? JSON.stringify(n) === JSON.stringify(w.n) : w.n.every(x => n.includes(x)); }",
                                 arg={"n": list(want), "e": exact})

    def dialog(self, *values, ok=True):
        """Fill the generic dialog's inputs and press OK."""
        d = self.p.locator("#dlg")
        expect(d).to_be_visible()
        for i, v in enumerate(values):
            self.p.fill(f"#df{i}", v)
        if ok:
            self.p.click("#dlgOk")
            expect(d).to_be_hidden()

    def sel_text(self):
        return self.p.inner_text("#selText") if self.p.locator("#selBar").is_visible() else "0 selected"


def run(headed=False):
    s = Server()
    files = os.path.join(s.dir, "upload"); os.makedirs(os.path.join(files, "Album", "Inner"))
    samples = {"notes.txt": b"hello from the ui test\nline 2", "table.csv": b"a,b\n1,\"x, y\"\n", "red.png": png(64, 48, (220, 30, 30)),
               "blue.png": png(48, 64, (30, 30, 220)), "tone.wav": wav(), "letter.docx": docx("Docx text renders"), "doc.pdf": pdf()}
    for n, b in samples.items():
        open(os.path.join(files, n), "wb").write(b)
    open(os.path.join(files, "Album", "a1.txt"), "w").write("album 1")
    open(os.path.join(files, "Album", "Inner", "deep.txt"), "w").write("deep")
    passed = []

    def step(name):
        print("  ...", name, flush=True)
        passed.append(name)

    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=not headed)
        ctx = browser.new_context(accept_downloads=True, viewport={"width": 1280, "height": 900})
        page = ctx.new_page()
        errors = []
        page.on("pageerror", lambda e: errors.append(str(e)))
        u = UI(page, s)
        try:
            page.goto(f"{s.url}/?t={s.token}")

            step("create vault, recovery key shown, version in UI")
            expect(page.locator("#lockTitle")).to_contain_text("Protect")
            page.fill("#pw1", PASSWORD); page.fill("#pw2", PASSWORD); page.click("#lockBtn")
            expect(page.locator("#keyDlg")).to_be_visible()
            assert re.fullmatch(r"[A-Z2-9]{5}(-[A-Z2-9]{5}){4}", page.inner_text("#keyText").strip())
            page.click("#keyDone")
            expect(page.locator("#vMain")).to_be_visible()
            expect(page.locator("#verTag")).to_contain_text(dv.APP_VERSION)

            step("upload files and a folder; progress panel")
            page.set_input_files("#fFiles", [os.path.join(files, n) for n in samples])
            u.wait_names(*samples)
            page.set_input_files("#fDir", os.path.join(files, "Album"))
            u.wait_names("Album")
            page.evaluate("""() => { window.__xfers = []; new MutationObserver(() => { const t = document.querySelector('#xfers').innerText;
                                     if (t) window.__xfers.push(t); }).observe(document.querySelector('#xfers'), {childList: true, subtree: true}); }""")
            big = os.path.join(s.dir, "big.bin"); open(big, "wb").write(os.urandom(48 << 20))
            page.set_input_files("#fFiles", big)
            u.wait_names("big.bin")
            shown = page.evaluate("window.__xfers")
            assert any(re.search(r"Encrypting .*big\.bin.*\d+(\.\d)?%.*of .*(left|estimating|finishing)", t, re.S) for t in shown), shown[:3]

            step("folders on top; sort by name / size / date")
            assert u.names()[0] == "Album"
            page.select_option("#sortSel", "s"); page.wait_for_timeout(200)
            n = u.names(); assert n[0] == "Album" and n[-1] == "big.bin", n
            page.select_option("#sortSel", "m"); page.wait_for_timeout(200)
            assert u.names()[0] == "Album"
            page.select_option("#sortSel", "n"); page.wait_for_timeout(200)
            assert u.names()[1:] == sorted(u.names()[1:], key=str.lower)

            step("browse: open folder, breadcrumbs, back / forward / up / home keys")
            u.row("Album").locator(".nm").click(); u.wait_names("a1.txt", "Inner")
            u.row("Inner").locator(".nm").click(); u.wait_names("deep.txt")
            expect(page.locator("#crumbs")).to_contain_text("Album")
            page.click("#nBack"); u.wait_names("a1.txt")
            page.click("#nFwd"); u.wait_names("deep.txt")
            page.click("#nUp"); u.wait_names("a1.txt")
            page.keyboard.press("Alt+Space"); u.wait_names("notes.txt")
            u.row("Album").locator(".nm").click(); u.wait_names("a1.txt")
            page.keyboard.press("Control+Home"); u.wait_names("notes.txt")
            page.locator("#crumbs a").first.click(); u.wait_names("notes.txt")

            step("search the whole vault")
            page.fill("#search", "deep"); u.wait_names("deep.txt", exact=True)
            page.press("#search", "Escape"); u.wait_names("notes.txt")

            step("new folder, rename with F2, Move to…")
            page.click("#bNew"); u.dialog("Trips"); u.wait_names("Trips")
            u.pick("notes.txt"); page.keyboard.press("F2"); u.dialog("notes2.txt"); u.wait_names("notes2.txt")
            u.pick("notes2.txt"); page.keyboard.press("F2"); u.dialog("notes.txt"); u.wait_names("notes.txt")
            u.pick("big.bin"); page.click("#selActs [data-s=moveto]")
            page.locator("#mvList .mvrow", has_text="Trips").click(); page.click("#mvHere")
            expect(page.locator("#mvDlg")).to_be_hidden()
            page.wait_for_function("() => ![...document.querySelectorAll('#list .nm')].some(x => x.textContent === 'big.bin')")
            u.row("Trips").locator(".nm").click(); u.wait_names("big.bin", exact=True); page.keyboard.press("Control+Home")

            step("multi-select: checkbox, Ctrl+click, Shift+click, Shift+Down, Ctrl+A")
            u.wait_names("blue.png")
            u.row("blue.png").locator(".ck").click(); assert u.sel_text() == "1 selected"
            u.pick("red.png", ["Control"]); assert u.sel_text() == "2 selected"
            u.pick("Album"); u.pick("blue.png", ["Shift"])
            assert u.sel_text() == "3 selected", u.sel_text()                   # Album, Trips, blue.png
            page.keyboard.press("Shift+ArrowDown"); assert u.sel_text() == "4 selected"
            page.keyboard.press("Control+a"); assert u.sel_text() == f"{len(u.names())} selected"
            page.keyboard.press("Escape"); assert u.sel_text() == "0 selected"

            step("trash: Delete key, restore, delete forever, empty (no auto-expiry)")
            u.pick("tone.wav"); page.keyboard.press("Delete")
            page.wait_for_function("() => ![...document.querySelectorAll('#list .nm')].some(x => x.textContent === 'tone.wav')")
            expect(page.locator("#trashCount")).to_have_text("1")
            page.click("#bTrash"); u.wait_names("tone.wav", exact=True)
            u.row("tone.wav").locator("[data-a=restore]").click(); page.wait_for_function("() => !document.querySelector('#list .row.item')")
            page.keyboard.press("Control+Home"); u.wait_names("tone.wav")
            u.pick("doc.pdf"); page.keyboard.press("Delete"); page.click("#bTrash"); u.wait_names("doc.pdf")
            u.row("doc.pdf").locator("[data-a=purge]").click(); u.dialog()
            page.wait_for_function("() => !document.querySelector('#list .row.item')")
            page.keyboard.press("Control+Home"); u.pick("Trips"); page.keyboard.press("Delete")
            page.click("#bTrash"); u.wait_names("Trips"); page.click("#bEmpty"); u.dialog()
            page.wait_for_function("() => !document.querySelector('#list .row.item')")
            page.keyboard.press("Control+Home"); u.wait_names("notes.txt", "red.png")

            step("custom order: drag files, folders stay on top, dropping on a folder never moves into it")
            page.select_option("#sortSel", "o"); expect(page.locator("#customHint")).to_be_visible()
            files_before = [n for n in u.names() if n != "Album"]
            u.row(files_before[-1]).drag_to(u.row(files_before[0]), target_position={"x": 40, "y": 3})
            page.wait_for_function("f => document.querySelectorAll('#list .nm')[1].textContent === f", arg=files_before[-1])
            u.row("table.csv").drag_to(u.row("Album"))
            page.wait_for_timeout(400)
            assert "table.csv" in u.names() and u.names()[0] == "Album", u.names()
            page.reload(); u.wait_names("Album"); assert u.names()[1] == files_before[-1], "custom order not saved"
            page.select_option("#sortSel", "n")

            step("viewer: image ←/→, zoom, full screen, text, csv, docx, audio")
            u.row("blue.png").locator(".nm").click()
            expect(page.locator("#viewer")).to_be_visible(); expect(page.locator("#vName")).to_have_text("blue.png")
            expect(page.locator("#vContent img")).to_be_visible()
            page.keyboard.press("+"); expect(page.locator("#zLevel")).not_to_have_text("Fit")
            page.keyboard.press("0"); expect(page.locator("#zLevel")).to_have_text("Fit")
            page.keyboard.press("ArrowRight"); expect(page.locator("#vName")).not_to_have_text("blue.png")
            page.keyboard.press("ArrowLeft"); expect(page.locator("#vName")).to_have_text("blue.png")
            page.keyboard.press("f"); page.wait_for_timeout(300); page.keyboard.press("f")
            def view(name):
                for _ in range(12):
                    if page.inner_text("#vName") == name:
                        return
                    page.keyboard.press("ArrowRight"); page.wait_for_timeout(100)
                raise AssertionError(f"{name} not reachable with →")
            view("notes.txt"); expect(page.locator("#vContent")).to_contain_text("hello from the ui test")
            view("table.csv"); expect(page.locator("#vContent")).to_contain_text("x, y")
            view("letter.docx"); expect(page.frame_locator("#vContent iframe").locator("body")).to_contain_text("Docx text renders")
            view("tone.wav"); expect(page.locator("#vContent audio")).to_have_count(1)
            page.keyboard.press("Escape"); expect(page.locator("#viewer")).to_be_hidden()

            step("download and Copy to computer")
            with page.expect_download() as d:
                u.row("notes.txt").locator("[data-a=dl]").click()
            assert open(d.value.path(), "rb").read() == samples["notes.txt"]
            u.pick("red.png"); page.click("#selActs [data-s=export]")
            expect(page.locator("#toastText")).to_contain_text("Copied 1 file(s)")
            out = [os.path.join(r, f) for r, _, fs in os.walk(os.path.join(s.home, "Downloads")) for f in fs]
            assert len(out) == 1 and open(out[0], "rb").read() == samples["red.png"], out

            step("Open in app / Show in folder; edits written back on lock")
            u.row("notes.txt").locator("[data-a=open]").click()
            expect(page.locator("#openBanner")).to_be_visible()
            local = s.opened[-1]; time.sleep(1.1); open(local, "wb").write(b"edited in an app")
            u.row("table.csv").locator("[data-a=reveal]").click(); page.wait_for_timeout(500)
            assert s.opened[-1].endswith("table.csv")

            step("Ctrl+C to the file manager, Ctrl+V from it")
            u.pick("blue.png"); page.keyboard.press("Control+c")
            page.wait_for_function("() => true"); page.wait_for_timeout(700)
            assert s.clipboard_out and open(s.clipboard_out[-1], "rb").read() == samples["blue.png"], s.clipboard_out
            ext = os.path.join(s.dir, "from-explorer.txt"); open(ext, "w").write("pasted"); s.clipboard_in[:] = [ext]
            page.keyboard.press("Control+v"); u.wait_names("from-explorer.txt")

            step("drag and drop files from the OS")
            page.evaluate("""() => { const dt = new DataTransfer(); dt.items.add(new File(['dropped'], 'dropped.txt'));
                                     window.dispatchEvent(new DragEvent('drop', {dataTransfer: dt, bubbles: true, cancelable: true})); }""")
            u.wait_names("dropped.txt")

            step("grid view with photo thumbnails")
            page.click("#bView"); expect(page.locator("#list")).to_have_class(re.compile("grid"))
            expect(page.locator("#list img.thumb").first).to_have_attribute("src", re.compile("^blob:"))
            page.click("#bView"); expect(page.locator("#list")).not_to_have_class(re.compile("grid"))

            step("history with filter + CSV export; plain log has no names by default")
            page.click("#bHist"); expect(page.locator("#hList")).to_contain_text("notes.txt")
            page.click("#histView .chip[data-h=out]"); expect(page.locator("#hList")).to_contain_text("Decrypted")
            expect(page.locator("#hList")).not_to_contain_text("Encrypted (added)")
            page.click("#hCsv")
            with page.expect_download() as d:
                u.dialog()
            assert b"red.png" in open(d.value.path(), "rb").read()
            assert not any("red.png" in l for l in s.api("/api/plainlog")["lines"])
            page.click("#nHome")

            step("security: questions, recovery key, change password")
            page.click("#bSec"); expect(page.locator("#secDlg")).to_be_visible()
            page.click("#sQ")
            for i, a in enumerate(["Blue Bell", "Delhi", "Rex"]):
                page.fill(f"#qa{i}", a)
            page.fill("#qPw", PASSWORD); page.locator("#qForm button[type=submit]").click()
            expect(page.locator("#qDlg")).to_be_hidden(); expect(page.locator("#sQState")).to_contain_text("On")
            page.click("#sKey"); u.dialog(PASSWORD); expect(page.locator("#keyDlg")).to_be_visible(); page.click("#keyDone")
            if not page.locator("#secDlg").is_visible(): page.click("#bSec")
            page.click("#sPw"); u.dialog(PASSWORD, "new password 22", "new password 22")
            expect(page.locator("#toastText")).to_contain_text("Password changed")
            if page.locator("#secDlg").is_visible(): page.click("#secClose")

            step("lock (saves edits), wrong password, unlock, auto-lock notice")
            page.click("#bLock"); expect(page.locator("#vLock")).to_be_visible()
            assert not os.path.exists(local)
            page.fill("#pw1", PASSWORD); page.click("#lockBtn"); expect(page.locator("#lockErr")).to_contain_text("Wrong password")
            page.fill("#pw1", "new password 22"); page.click("#lockBtn"); expect(page.locator("#vMain")).to_be_visible()
            nid = next(i["id"] for i in s.api("/api/list?id=root")["items"] if i["n"] == "notes.txt")
            assert s.request("GET", f"/api/file?id={nid}")[2] == b"edited in an app"
            s.app.lock("Locked automatically after 15 minutes without activity.", why="auto-lock")
            page.evaluate("refresh()"); expect(page.locator("#vLock")).to_be_visible()
            expect(page.locator("#toastText")).to_contain_text("Locked automatically")

            step("forgot password: reset with the security questions")
            page.click("#forgot"); page.locator("#recTabs button", has_text="Question").click()
            for i, a in enumerate(["blue-bell", "DELHI", "rex."]):
                page.locator("#recQ input").nth(i).fill(a)
            page.fill("#np1", "reset password 3"); page.fill("#np2", "reset password 3"); page.click("#lockBtn")
            expect(page.locator("#vMain")).to_be_visible()
            assert not errors, errors
        except Exception:
            shot = os.path.join(os.path.dirname(os.path.abspath(__file__)), "ui-failure.png")
            page.screenshot(path=shot, full_page=True)
            print(f"  FAILED at step: {passed[-1] if passed else '?'} (screenshot: {shot})")
            raise
        finally:
            browser.close()
            s.close()
    print(f"ALL UI TESTS PASSED ({len(passed)} steps)")


if __name__ == "__main__":
    run(headed="--headed" in sys.argv)
