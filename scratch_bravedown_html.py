from playwright.sync_api import sync_playwright

def run():
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        page = browser.new_page()
        page.goto("https://bravedown.com/vi/facebook-story-downloader", wait_until="networkidle")
        html = page.content()
        with open("scratch/bravedown_html.txt", "w", encoding="utf-8") as f:
            f.write(html)
        browser.close()

if __name__ == "__main__":
    run()
