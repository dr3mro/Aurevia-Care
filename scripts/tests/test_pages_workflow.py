"""Regression guard for the Pages artifact name used across workflow reruns.

A rerun keeps artifacts from earlier attempts in the same workflow run. If the
upload and the deploy step both use the default ``github-pages`` name, the deploy
step finds more than one artifact and fails. Both steps must derive the same
name from ``github.run_attempt``.
"""
import unittest

from helpers import ROOT


class PagesWorkflowTests(unittest.TestCase):
    def test_pages_artifact_name_is_unique_per_attempt(self):
        text = (ROOT / ".github/workflows/pages.yml").read_text(encoding="utf-8")
        expected = "github-pages-${{ github.run_attempt }}"
        upload = text.split("uses: actions/upload-pages-artifact@v3", 1)[1]
        upload = upload.split("uses: actions/deploy-pages@v4", 1)[0]
        deploy = text.split("uses: actions/deploy-pages@v4", 1)[1]
        self.assertIn(f"name: {expected}", upload)
        self.assertIn(f"artifact_name: {expected}", deploy)
        self.assertNotIn("name: github-pages\n", upload)
        self.assertNotIn("artifact_name: github-pages\n", deploy)


if __name__ == "__main__":
    unittest.main()
