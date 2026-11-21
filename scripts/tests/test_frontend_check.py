"""Tests for the npm production-dependency audit in the frontend gate.

Fixtures are synthetic: a stub ``npm`` on PATH and a minimal copy of the gate
with two fake frontend modules. No network, package install or real lock file is
used. WEB-12 / OPS-06 require npm packages to be scanned like Java dependencies.
"""
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
FRONTEND_CHECK = ROOT / "scripts" / "frontend-check.sh"
MODULES = ("apps/staff-web", "apps/patient-mobile")

# The stub reports the audit mode selected by the test. `prod` is 1 only when
# --omit=dev is present, so dev-only findings are distinguishable from prod ones.
STUB_NPM = """#!/usr/bin/env bash
printf '%s\\n' "$*" >> "$STUB_NPM_LOG"
if [[ "${1:-}" != "audit" ]]; then
  exit 0
fi
prod=0
for arg in "$@"; do
  if [[ "$arg" == "--omit=dev" ]]; then
    prod=1
  fi
done
case "${STUB_NPM_MODE:-clean}" in
  high)
    if [[ $prod -eq 1 ]]; then
      printf '# npm audit report\\n\\nSeverity: high\\n1 high severity vulnerability\\n'
      exit 1
    fi
    printf 'found 0 vulnerabilities\\n'
    exit 0
    ;;
  critical)
    if [[ $prod -eq 1 ]]; then
      printf '# npm audit report\\n\\nSeverity: critical\\n1 critical severity vulnerability\\n'
      exit 1
    fi
    printf 'found 0 vulnerabilities\\n'
    exit 0
    ;;
  moderate)
    printf '2 moderate severity vulnerabilities\\n'
    exit 0
    ;;
  devonly)
    if [[ $prod -eq 1 ]]; then
      printf 'found 0 vulnerabilities\\n'
      exit 0
    fi
    printf '# npm audit report\\n\\nSeverity: high\\n1 high severity vulnerability (dev)\\n'
    exit 1
    ;;
  registry)
    printf 'npm ERR! code ENOTFOUND\\nnpm ERR! getaddrinfo ENOTFOUND registry.npmjs.org\\n' >&2
    exit 1
    ;;
  devregistry)
    if [[ $prod -eq 1 ]]; then
      printf 'found 0 vulnerabilities\\n'
      exit 0
    fi
    printf 'npm ERR! code ECONNRESET\\nnpm ERR! socket hang up\\n' >&2
    exit 1
    ;;
  missinglock)
    printf 'npm ERR! code ENOLOCK\\nnpm ERR! audit requires a lock file\\n' >&2
    exit 1
    ;;
  unusablenpm)
    printf 'bash: npm: Permission denied\\n' >&2
    exit 126
    ;;
  clean|*)
    printf 'found 0 vulnerabilities\\n'
    exit 0
    ;;
esac
"""

TYPESCRIPT = "export const fixture = 1;\n"
TSCONFIG = '{"compilerOptions": {"strict": true, "noUncheckedIndexedAccess": true}}\n'
PACKAGE = '{"name": "fixture", "version": "0.0.0", "dependencies": {}}\n'


class FrontendAuditTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)

        scripts = self.root / "scripts"
        scripts.mkdir()
        shutil.copy(FRONTEND_CHECK, scripts / "frontend-check.sh")
        for module in MODULES:
            folder = self.root / module / "src"
            folder.mkdir(parents=True)
            (folder / "index.ts").write_text(TYPESCRIPT, encoding="utf-8")
            (self.root / module / "tsconfig.json").write_text(TSCONFIG, encoding="utf-8")
            (self.root / module / "package.json").write_text(PACKAGE, encoding="utf-8")

        stubbin = self.root / "stubbin"
        stubbin.mkdir()
        self.npm = stubbin / "npm"
        self.npm.write_text(STUB_NPM, encoding="utf-8")
        self.npm.chmod(0o755)
        self.log = self.root / "npm-invocations.log"
        self.log.touch()

    def run_gate(self, mode="clean", skip=None):
        """Run the copied gate; return (exit code, combined output, npm log)."""
        env = os.environ.copy()
        env.pop("FRONTEND_AUDIT", None)
        if skip is not None:
            env["FRONTEND_AUDIT"] = skip
        env["STUB_NPM_MODE"] = mode
        env["STUB_NPM_LOG"] = str(self.log)
        env["PATH"] = f"{self.root / 'stubbin'}{os.pathsep}{env.get('PATH', '')}"
        env["HOME"] = str(self.root)
        env["LC_ALL"] = "C"
        result = subprocess.run(
            ["bash", str(self.root / "scripts" / "frontend-check.sh")],
            cwd=str(self.root), env=env, capture_output=True, text=True, timeout=60,
        )
        output = result.stdout + result.stderr
        return result.returncode, output, self.log.read_text(encoding="utf-8")

    def test_clean_audit_passes_and_audits_both_modules(self):
        code, output, invocations = self.run_gate(mode="clean")
        self.assertEqual(code, 0, output)
        prod_calls = [line for line in invocations.splitlines()
                      if line == "audit --omit=dev --audit-level=high"]
        full_calls = [line for line in invocations.splitlines()
                      if line == "audit --audit-level=high"]
        self.assertEqual(len(prod_calls), 2, invocations)
        self.assertEqual(len(full_calls), 2, invocations)

    def test_high_finding_fails(self):
        code, output, _ = self.run_gate(mode="high")
        self.assertNotEqual(code, 0)
        self.assertIn("FAIL", output)
        self.assertIn("production dependencies", output)

    def test_critical_finding_fails(self):
        code, output, _ = self.run_gate(mode="critical")
        self.assertNotEqual(code, 0)
        self.assertIn("FAIL", output)
        self.assertIn("production dependencies", output)

    def test_moderate_finding_passes(self):
        code, output, _ = self.run_gate(mode="moderate")
        self.assertEqual(code, 0, output)
        self.assertNotIn("FAIL", output)

    def test_dev_only_finding_warns(self):
        code, output, _ = self.run_gate(mode="devonly")
        self.assertEqual(code, 0, output)
        self.assertIn("WARN", output)
        self.assertIn("development dependencies", output)

    def test_registry_error_is_infrastructure(self):
        code, output, _ = self.run_gate(mode="registry")
        self.assertNotEqual(code, 0)
        self.assertIn("INFRASTRUCTURE", output)

    def test_dev_audit_registry_error_is_infrastructure(self):
        code, output, _ = self.run_gate(mode="devregistry")
        self.assertNotEqual(code, 0)
        self.assertIn("INFRASTRUCTURE", output)

    def test_missing_lockfile_is_infrastructure(self):
        code, output, _ = self.run_gate(mode="missinglock")
        self.assertNotEqual(code, 0)
        self.assertIn("INFRASTRUCTURE", output)

    def test_unusable_npm_binary_is_infrastructure(self):
        code, output, _ = self.run_gate(mode="unusablenpm")
        self.assertNotEqual(code, 0)
        self.assertIn("INFRASTRUCTURE", output)

    def test_skip_prints_notice_and_skips_audit(self):
        code, output, invocations = self.run_gate(mode="high", skip="skip")
        self.assertEqual(code, 0, output)
        self.assertIn("FRONTEND_AUDIT=skip", output)
        self.assertEqual([line for line in invocations.splitlines()
                          if line.startswith("audit ")], [], invocations)

    def test_other_skip_value_does_not_bypass(self):
        code, output, invocations = self.run_gate(mode="high", skip="true")
        self.assertNotEqual(code, 0)
        self.assertIn("FAIL", output)
        self.assertTrue(any(line.startswith("audit ")
                            for line in invocations.splitlines()), invocations)


if __name__ == "__main__":
    unittest.main()
