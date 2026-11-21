"""Build-failing 100% line-coverage gate for the synthetic load generator.

``coverage.py`` is not installed in this environment, so the gate uses the
standard-library ``trace`` module, following the repository's other Python
infrastructure modules. It runs the unit suite under ``trace`` and fails when
``trace`` marks any executable line of a production module as never executed.
Test utilities under ``tests/`` are not measured.

The ``--missing`` flag is safety critical: ``trace --count`` alone never
annotates unexecuted lines, which would make the gate vacuous. The integration
test below drives this exact command against a deliberately under-covered
probe module, so removing the flag fails a test.

Usage: ``python3 -I infra/loadtest/tests/coverage_gate.py``
Exit codes: 0 = every production line executed, 1 = uncovered lines,
2 = the traced run or its coverage files could not be produced.
"""
import subprocess
import sys
import tempfile
from pathlib import Path

MODULE_DIR = Path(__file__).resolve().parents[1]
TESTS_DIR = MODULE_DIR / "tests"
UNCOVERED_MARKER = ">>>>>> "
TRACE_TIMEOUT_SECONDS = 120


def find_uncovered_lines(cover_text):
    """Return line numbers that ``trace --count --missing`` marked unexecuted.

    Only a line whose rendered text starts with the marker is uncovered; the
    marker inside counted source text is not a signal.
    """
    uncovered = []
    for number, line in enumerate(cover_text.splitlines(), start=1):
        if line.startswith(UNCOVERED_MARKER):
            uncovered.append(number)
    return uncovered


def production_modules():
    """Return production Python modules in the loadtest root.

    Anything added to the module root is measured automatically; test files
    live under ``tests/`` and are excluded by the directory.
    """
    return sorted(
        path for path in MODULE_DIR.glob("*.py")
        if not path.name.startswith("test_")
    )


def find_cover_file(cover_dir, module_name):
    """Locate the single ``.cover`` file for a production module."""
    matches = [
        path for path in Path(cover_dir).rglob("*.cover")
        if path.name.endswith(module_name + ".cover")
        and not path.name.startswith("test_")
    ]
    if len(matches) != 1:
        raise RuntimeError(
            f"expected exactly one {module_name} coverage file, "
            f"found {len(matches)}: {[path.name for path in matches]}"
        )
    return matches[0]


def run_traced_suite(cover_dir, target=None):
    """Run ``target`` (default: the unit suite) under ``trace --missing``."""
    script = Path(target) if target is not None else TESTS_DIR / "run_coverage.py"
    return subprocess.run(
        [sys.executable, "-I", "-m", "trace", "--count", "--missing",
         "--coverdir", str(cover_dir), str(script)],
        cwd=str(MODULE_DIR), capture_output=True, text=True,
        timeout=TRACE_TIMEOUT_SECONDS,
    )


def measure_coverage():
    """Return ``{module_name: (total_lines, uncovered_line_numbers)}``."""
    with tempfile.TemporaryDirectory(prefix="loadtest_cov_") as directory:
        result = run_traced_suite(directory)
        if result.returncode != 0:
            tail = (result.stderr or result.stdout)[-1000:]
            raise RuntimeError(f"traced suite exited {result.returncode}: {tail}")
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
        print(f"coverage gate: ERROR - {error}", file=sys.stderr)
        return 2
    failed = {name: lines for name, (_, lines) in report.items() if lines}
    if failed:
        print(f"coverage gate: FAIL - uncovered lines in {failed}", file=sys.stderr)
        return 1
    total = sum(count for count, _ in report.values())
    print(f"coverage gate: PASS - 100% line coverage "
          f"({total} lines across {len(report)} module(s), 0 uncovered)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
