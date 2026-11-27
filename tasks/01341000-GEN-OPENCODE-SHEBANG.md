- [x] GEN-OPENCODE-SHEBANG · Add the standard shebang to scripts/gen-opencode.py
  - scope: scripts/gen-opencode.py, scripts/tests/test_gen_opencode_coverage.py, tasks/01341000-GEN-OPENCODE-SHEBANG.md
  - requirements: OPS-06
  - details:
    Owner directive: add the shebang, it is harmless. Use the repository's standard `#!/usr/bin/env python3` form already used by other Python tooling. This is a comment-only change: the script stays mode `100644` and is still invoked as `python3 -I scripts/gen-opencode.py`; generation output and the generated profiles must be byte-identical. A regression test locks the shebang so a future mode change cannot reintroduce the ownership violation from issue #1784. No threshold, gate, token, workflow or generated artifact changes.
  - evidence:
    Input (authorized human/owner directive, 2026-11-27): "add the shabang its hramless". Skills loaded: aurevia-delivery (delivery loop), aurevia-senior-python (scripts/*.py and tests).

    Red first (tool): new `test_generator_has_the_standard_python_shebang` in scripts/tests/test_gen_opencode_coverage.py -> `Ran 1 test ... FAILED (failures=1)` before the edit.

    Fix (tool): one comment line `#!/usr/bin/env python3` added at scripts/gen-opencode.py:1; file mode unchanged at `100644`; generated profiles untouched.

    Green (tool): targeted test `Ran 1 test ... OK`; `python3 -I -m unittest discover -s scripts/tests -p test_gen_opencode_coverage.py -q` -> `Ran 24 tests ... OK`; `python3 -I -m unittest discover -s scripts/tests -p test_check_ownership.py -q` -> `Ran 34 tests ... OK`; `python3 -I scripts/gen-opencode.py --check` exit 0; check-docs, check-secrets, check-names, check-wiring, check-pack, check-constraints --base origin/main --require-base and `git diff --check` all exit 0. Coverage: the added line is a shebang comment, not an executable statement, so no production line or branch needs a coverage assertion; the named test locks the text.

    Final gates (tool, on stable tree `732e4fa72a8d37b50fc34507627e571db534964c`): `PATH=/opt/homebrew/bin:$PATH python3 -I -m unittest discover -s scripts/tests -q` -> `Ran 2425 tests in 318.872s ... OK`; `PATH=/opt/homebrew/bin:$PATH bash scripts/python-coverage.sh` -> `Ran 2425 tests in 255.802s ... OK`, `python coverage: every changed script line is covered`, both exit 0. Evidence source: owner directive (human), tool commands; no simulated-user feedback.
  - pr: direct commit `d44b4fe74931e98422971268d6d9cf9b160dcfac` on `main`; no pull request was opened for this story.
