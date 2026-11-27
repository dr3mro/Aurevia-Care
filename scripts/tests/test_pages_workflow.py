"""Regression guard for the Pages artifact name used across workflow reruns.

A rerun keeps artifacts from earlier attempts in the same workflow run. If the
upload and the deploy step both use the default ``github-pages`` name, the deploy
step finds more than one artifact and fails. Both steps must derive the same
name from ``github.run_attempt``.
"""
import unittest

from helpers import ROOT


class PagesWorkflowTests(unittest.TestCase):
    def step_block(self, text, name):
        """Return the YAML text of one named step in the Pages deploy job."""
        marker = f"- name: {name}\n"
        self.assertIn(marker, text)
        return text.split(marker, 1)[1].split("\n      - ", 1)[0]

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

    def test_pages_waits_for_artifact_metadata_before_deploy(self):
        text = (ROOT / ".github/workflows/pages.yml").read_text(encoding="utf-8")
        upload = text.index("uses: actions/upload-pages-artifact@v3")
        wait = text.index("- name: Wait for Pages artifact metadata")
        deploy = text.index("uses: actions/deploy-pages@v4")
        self.assertLess(upload, wait)
        self.assertLess(wait, deploy)
        block = self.step_block(text, "Wait for Pages artifact metadata")
        self.assertIn("GH_TOKEN: ${{ github.token }}", block)
        self.assertIn("actions/runs/${GITHUB_RUN_ID}/artifacts?per_page=100", block)
        self.assertIn("github-pages-${GITHUB_RUN_ATTEMPT}", block)
        self.assertIn("for attempt in 1 2 3 4 5", block)
        self.assertIn("exit 1", block)
        permissions = text.split("permissions:", 1)[1].split("concurrency:", 1)[0]
        self.assertIn("actions: read", permissions)


if __name__ == "__main__":
    unittest.main()
