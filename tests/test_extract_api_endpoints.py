import tempfile
import unittest
from pathlib import Path

from extract_api_endpoints import extract_endpoints_from_kotlin, extract_endpoints_from_python


class TestExtractApiEndpoints(unittest.TestCase):
    def test_kotlin_ignores_comments_and_normalizes_dynamic_paths(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            kotlin_dir = Path(temp_dir)
            (kotlin_dir / "Routes.kt").write_text(
                '''
                // GET /api/not-a-route
                val status = Request.Builder().url("$base/api/status").build()
                val job = Request.Builder().url("$base/api/social/status/$jobId").build()
                val reportUrl = "$base/api/report/daily$dateParam"
                val statusText = "Server missing /api/backup/*"
                /* .url("$base/api/also-not-a-route") */
                ''',
                encoding="utf-8",
            )

            self.assertEqual(
                extract_endpoints_from_kotlin(kotlin_dir),
                {
                    "/api/status",
                    "/api/social/status/<var>",
                    "/api/report/daily",
                },
            )

    def test_python_reads_single_and_double_quoted_flask_routes(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            server = Path(temp_dir) / "server.py"
            server.write_text(
                '''
@app.route('/api/status')
def status(): pass

@app.route("/api/social/status/<job_id>", methods=["GET"])
def social_status(job_id): pass
''',
                encoding="utf-8",
            )

            self.assertEqual(
                extract_endpoints_from_python(server),
                {"/api/status", "/api/social/status/<var>"},
            )


if __name__ == "__main__":
    unittest.main()
