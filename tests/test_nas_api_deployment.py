# -*- coding: utf-8 -*-
"""Deployment-level smoke tests for the NAS Flask API.

Run against a deployed server with:
    NAS_API_BASE_URL=http://100.90.135.102:5050 \
        python -m unittest tests.test_nas_api_deployment -v
"""

import os
import json
import unittest
import urllib.error
import urllib.request


class TestNasApiDeployment(unittest.TestCase):
    """Verify public routes are registered in the running Flask process."""

    @classmethod
    def setUpClass(cls):
        base_url = os.environ.get("NAS_API_BASE_URL", "").rstrip("/")
        if not base_url:
            raise unittest.SkipTest("NAS_API_BASE_URL is not configured")
        cls.base_url = base_url

    def request(self, path, method="GET", body=None):
        payload = None
        headers = {}
        if body is not None:
            payload = json.dumps(body).encode("utf-8")
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(
            self.base_url + path,
            data=payload,
            headers=headers,
            method=method,
        )
        try:
            with urllib.request.urlopen(request, timeout=5) as response:
                return response.status, response.headers, response.read()
        except urllib.error.HTTPError as error:
            try:
                return error.code, error.headers, error.read()
            finally:
                error.close()

    def test_social_download_route_is_registered(self):
        status, headers, _body = self.request("/api/social/download", method="OPTIONS")
        allow = headers.get("Allow", "")

        self.assertEqual(
            status,
            200,
            "Flask did not register POST /api/social/download in the running process",
        )
        self.assertIn("POST", allow)
        self.assertIn("waitress", headers.get("Server", "").lower())

    def test_private_url_is_rejected_without_starting_a_job(self):
        status, _headers, body = self.request(
            "/api/social/download",
            method="POST",
            body={"url": "http://127.0.0.1/private", "folder": "Downloads/social"},
        )
        self.assertEqual(status, 400)
        self.assertIn("URL", json.loads(body.decode("utf-8"))["error"])

    def test_unknown_social_job_returns_not_found(self):
        status, _headers, body = self.request("/api/social/status/does-not-exist")
        self.assertEqual(status, 404)
        self.assertIn("not found", json.loads(body.decode("utf-8"))["error"].lower())


if __name__ == "__main__":
    unittest.main()
