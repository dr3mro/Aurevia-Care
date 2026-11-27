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


    def test_pages_deploy_retries_once_then_fails_closed(self):
        text = (ROOT / ".github/workflows/pages.yml").read_text(encoding="utf-8")
        first = self.step_block(text, "Deploy to GitHub Pages (first attempt)")
        delay = self.step_block(text, "Wait before Pages deploy retry")
        retry = self.step_block(text, "Retry Pages deployment")
        guard = self.step_block(text, "Verify Pages deployment")
        self.assertIn("id: deploy_first", first)
        self.assertIn("continue-on-error: true", first)
        self.assertIn("uses: actions/deploy-pages@v4", first)
        self.assertIn("steps.deploy_first.outcome == 'failure'", delay)
        self.assertIn("sleep 20", delay)
        self.assertIn("steps.deploy_first.outcome == 'failure'", retry)
        self.assertIn("id: deploy_retry", retry)
        self.assertNotIn("continue-on-error", retry)
        self.assertIn("github-pages-${{ github.run_attempt }}", retry)
        self.assertIn("id: deployment", guard)
        self.assertIn("if: always()", guard)
        self.assertIn("FIRST_OUTCOME", guard)
        self.assertIn("RETRY_OUTCOME", guard)
        self.assertIn('if [[ "$FIRST_OUTCOME" == success || "$RETRY_OUTCOME" == success ]]', guard)
        self.assertIn("exit 0", guard)
        self.assertIn("did not succeed (first=$FIRST_OUTCOME retry=$RETRY_OUTCOME)", guard)
        self.assertIn("exit 1", guard)
        self.assertLess(text.index("Deploy to GitHub Pages (first attempt)"),
                        text.index("Wait before Pages deploy retry"))
        self.assertLess(text.index("Wait before Pages deploy retry"),
                        text.index("Retry Pages deployment"))
        self.assertLess(text.index("Retry Pages deployment"),
                        text.index("Verify Pages deployment"))

    def test_pages_environment_url_uses_the_coalesced_output(self):
        text = (ROOT / ".github/workflows/pages.yml").read_text(encoding="utf-8")
        guard = self.step_block(text, "Verify Pages deployment")
        self.assertIn("environment:\n      name: github-pages\n"
                      "      url: ${{ steps.deployment.outputs.page_url }}", text)
        self.assertIn("FIRST_URL", guard)
        self.assertIn("RETRY_URL", guard)
        self.assertIn('url="${FIRST_URL:-$RETRY_URL}"', guard)
        self.assertIn('echo "page_url<<EOF"', guard)
        self.assertIn('>> "$GITHUB_OUTPUT"', guard)


if __name__ == "__main__":
    unittest.main()
