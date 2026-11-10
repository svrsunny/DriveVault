"""Takes the README screenshots from the real desktop app, running on the fictional demo vault (tools/demo.py).
    python tools/screenshots.py            -> assets/screenshots/*.png
Needs:  pip install playwright  &&  python -m playwright install chromium"""
import shutil, sys, tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import demo  # noqa: E402
from playwright.sync_api import sync_playwright, expect  # noqa: E402

OUT = Path(__file__).resolve().parent.parent / "assets" / "screenshots"


def main():
    folder = Path(tempfile.mkdtemp(prefix="drivevault-shots-"))
    OUT.mkdir(parents=True, exist_ok=True)
    app, httpd, port, token = demo.start(folder)
    try:
        with sync_playwright() as pw:
            browser = pw.chromium.launch()
            page = browser.new_page(viewport={"width": 1200, "height": 760}, color_scheme="light", device_scale_factor=1)
            row = lambda name: page.locator("#list .row.item", has=page.locator(".nm", has_text=name))

            def shot(name):
                page.evaluate("document.querySelector('#toast').classList.add('hidden')")
                page.mouse.move(1, 1)
                page.wait_for_timeout(300)
                page.screenshot(path=str(OUT / f"{name}.png"))
                print("saved", name)

            page.goto(f"http://127.0.0.1:{port}/?t={token}")
            expect(page.locator("#lockTitle")).to_have_text("Unlock vault")
            shot("unlock")

            page.fill("#pw1", demo.PASSWORD); page.click("#lockBtn")
            expect(page.locator("#vMain")).to_be_visible()
            row("budget-example.csv").locator(".mod").click()
            row("welcome.txt").locator(".mod").click(modifiers=["Control"])
            shot("browser")

            page.keyboard.press("Escape")
            row("Travel").locator(".nm").click(); expect(row("itinerary.txt")).to_be_visible()
            page.select_option("#sortSel", "o")
            shot("custom-order")

            page.click("#bView")
            page.wait_for_function("() => [...document.querySelectorAll('#list img.thumb')].filter(i => i.complete && i.src).length === 4")
            shot("grid")

            page.locator("#list .row.item .th").first.click()
            expect(page.locator("#viewer")).to_be_visible(); page.wait_for_timeout(400)
            shot("viewer")
            page.keyboard.press("Escape")
            page.click("#bView"); page.select_option("#sortSel", "n")

            page.click("#bHist"); expect(page.locator("#hList")).to_contain_text("coast-sunset.png")
            shot("history")

            page.click("#nHome"); page.click("#bSec"); expect(page.locator("#secDlg")).to_be_visible()
            shot("security")
            browser.close()
    finally:
        app.lock(why="screenshots done")
        httpd.shutdown(); httpd.server_close()
        shutil.rmtree(folder, ignore_errors=True)


if __name__ == "__main__":
    main()
