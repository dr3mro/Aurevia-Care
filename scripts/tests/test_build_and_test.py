"""Exercise build-and-test policy checks through the shell entry point."""
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from helpers import ROOT


class BuildAndTestTests(unittest.TestCase):
    def test_policy_failure_is_preserved_in_the_step_log_and_exit_status(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for subdir in ("scripts", "infra/bootstrap", "apps/api", "db", "bin"):
                (root / subdir).mkdir(parents=True)
            for script in ("infra/bootstrap/start.sh", "apps/api/check.sh", "db/check.sh",
                           "scripts/python-coverage.sh"):
                (root / script).touch()
            shutil.copy(ROOT / "scripts/build-and-test.sh", root / "scripts")
            stubs = {
                "shellcheck": "exit 0",
                "actionlint": 'echo "workflow lint ran"',
                "git": '[[ "$1" != rev-parse || "${HAS_BASE:-0}" == 1 ]]',
                "python3": """if [[ "$*" == *scripts/check-pack.py* && "${FAIL_PACK:-0}" == 1 ]]; then
  echo 'pack check failed'
  exit 7
fi
echo "$*"
""",
            }
            for name, content in stubs.items():
                command = root / "bin" / name
                command.write_text(f"#!/bin/bash\n{content}\n")
                command.chmod(0o755)
            env = {**os.environ, "PATH": f"{root / 'bin'}:{os.defpath}"}
            command = ["bash", "scripts/build-and-test.sh", "--fail-fast", "--skip-docker",
                       "--skip-frontend", "--skip-java", "--skip-docs"]
            for has_base, fail_pack in (("1", "1"), ("1", "0"), ("0", "0")):
                with self.subTest(has_base=has_base, fail_pack=fail_pack):
                    result = subprocess.run(
                        command, cwd=root,
                        env={**env, "HAS_BASE": has_base, "FAIL_PACK": fail_pack},
                        capture_output=True, text=True,
                    )
                    self.assertEqual(result.returncode, int(fail_pack), result.stdout + result.stderr)
                    log = (root / "build/logs/policy-pack-docs-secrets-names-wiring.log").read_text()
                    self.assertIn("workflow lint ran", result.stdout)
                    if fail_pack == "1":
                        self.assertIn("pack check failed", log)
                        self.assertNotIn("scripts/check-migrations.py", log)
                        report = (root / "build/build-and-test-failures.txt").read_text()
                        self.assertIn("policy: pack, docs, secrets, names, wiring", report)
                        self.assertIn("exit_code: 7", report)
                    else:
                        expected = "-I scripts/check-constraints.py"
                        if has_base == "1":
                            expected += " --require-base"
                        self.assertIn(expected, log.splitlines())
                        self.assertIn("scripts/check-migrations.py --require-baseline", log)
