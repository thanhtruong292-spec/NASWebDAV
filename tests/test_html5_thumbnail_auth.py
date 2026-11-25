from pathlib import Path


MAIN = Path(__file__).resolve().parents[1] / "app" / "src" / "main"
APP_JS = (MAIN / "assets/html5/app.js").read_text(encoding="utf-8")
DASHBOARD = (MAIN / "java/com/nas/naswebdav/ui/html5/NasHtmlDashboard.kt").read_text(encoding="utf-8")


def test_media_urls_and_dashboard_state_do_not_expose_credentials():
    assert "&auth=" not in APP_JS
    assert "authKey" not in APP_JS
    assert '"authKey"' not in DASHBOARD
