"""Build-failing 100% line-coverage gate for the cloud Terraform policy checker.

``coverage.py`` is not installed in this environment, so the gate uses the
standard-library ``trace`` module. It runs the unit suite under ``trace`` and
fails when ``trace`` marks any executable line of a production module as never
executed. Test utilities under ``tests/`` are not measured.

``--missing`` is safety-critical: ``trace --count`` alone never annotates
unexecuted lines, so without it the gate would be vacuous. A test exercises
this exact command path on a deliberately under-covered probe module, so
removing the flag fails a test.

Usage: ``python3 -I infra/cloud/tests/coverage_gate.py``
Exit codes: 0 = every production line executed, 1 = uncovered lines, 2 = the
traced run or its coverage files could not be produced.
"""
import subprocess
import sys
import tempfile
from pathlib import Path

MODULE_DIR = Path(__file__).resolve().parents[1]
TESTS_DIR = MODULE_DIR / "tests"
UNCOVERED_MARKER = ">>>>>> "
NO_COVER_PRAGMA = "#pragma NO COVER"
TRACE_TIMEOUT_SECONDS = 120


def find_uncovered_lines(cover_text):
    """Return source line numbers that ``trace`` marked as never executed."""
    uncovered = []
    for number, line in enumerate(cover_text.splitlines(), start=1):
        if line.startswith(UNCOVERED_MARKER):
            uncovered.append(number)
    return uncovered


def production_modules():
    """Return production Python modules in the cloud module root."""
    return sorted(
        path for path in MODULE_DIR.glob("*.py")
        if not path.name.startswith("test_")
    )


def find_cover_file(cover_dir, module_name):
    """Locate the single ``.cover`` file for ``module_name``."""
    matches = [
        path for path in Path(cover_dir).rglob("*.cover")
        if path.name.endswith(module_name + ".cover")
        and not path.name.startswith("test_")
    ]
    if len(matches) != 1:
        raise RuntimeError(
            f"expected exactly one {module_name} coverage file, "
            f"found {len(matches)}: {[path.name for path in matches]}")
    return matches[0]


def run_traced_suite(cover_dir, target=None):
    """Run ``target`` (default: the unit-suite runner) under trace."""
    script = Path(target) if target is not None else TESTS_DIR / "run_coverage.py"
    return subprocess.run(
        [sys.executable, "-I", "-m", "trace", "--count", "--missing",
         "--coverdir", str(cover_dir), str(script)],
        cwd=str(MODULE_DIR), capture_output=True, text=True,
        timeout=TRACE_TIMEOUT_SECONDS,
    )


def measure_coverage():
    """Return ``{module_name: (total_lines, uncovered_line_numbers)}``."""
    with tempfile.TemporaryDirectory(prefix="cloud_cov_") as directory:
        result = run_traced_suite(directory)
        if result.returncode != 0:
            tail = (result.stderr or result.stdout)[-1000:]
            raise RuntimeError("traced suite exited %d: %s" %
                               (result.returncode, tail))
        report = {}
        for module in production_modules():
            cover = find_cover_file(directory, module.stem)
            text = cover.read_text(encoding="utf-8")
            report[module.stem] = (len(text.splitlines()),
                                   find_uncovered_lines(text))
        return report


def main():
    """Run the gate and print a machine-readable result line."""
    try:
        report = measure_coverage()
    except (OSError, RuntimeError, UnicodeError, ValueError,
            subprocess.SubprocessError) as error:
        print("coverage gate: ERROR - " + str(error), file=sys.stderr)
        return 2
    failed = {name: uncovered for name, (_, uncovered) in report.items()
              if uncovered}
    if failed:
        print("coverage gate: FAIL - uncovered lines in " + str(failed),
              file=sys.stderr)
        return 1
    total = sum(count for count, _ in report.values())
    print("coverage gate: PASS - 100%% line coverage (%d lines across %d "
          "module(s), 0 uncovered)" % (total, len(report)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
