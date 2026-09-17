from playwright.sync_api import sync_playwright
import json

def run():
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        page = browser.new_page()
        
        # Intercept network requests
        def handle_request(request):
            if "api" in request.url or "ajax" in request.url:
                print("API Request:", request.url)
                print("Method:", request.method)
                print("PostData:", request.post_data)
                print("Headers:", request.headers)
        
        def handle_response(response):
            if "api" in response.url or "ajax" in response.url:
                print("API Response URL:", response.url)
                try:
                    print("API Response Body:", response.json())
                except Exception:
                    pass

        page.on("request", handle_request)
        page.on("response", handle_response)
        
        print("Navigating to bravedown.com...")
        page.goto("https://bravedown.com/vi/facebook-video-downloader", wait_until="networkidle")
        
        print("Filling form...")
        page.fill('input#url', 'https://www.facebook.com/watch/?v=10153231379946729')
        
        print("Clicking download...")
        page.click('button#send')
        
        print("Waiting for response...")
        page.wait_for_timeout(10000)
        
        browser.close()

if __name__ == "__main__":
    run()
