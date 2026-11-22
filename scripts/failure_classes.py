"""Classify a failed swarm pull request and name the layer that should catch it.

``classify`` combines a failure log, the task text and the changed file paths
into one decision: a failure class and the layer where the regression test that
would have prevented the next occurrence belongs. It complements ``diagnose``
in ``scripts/swarm.py``: that function explains *what* broke, this one decides
*who should have caught it*.

The classifier is deterministic, offline and side-effect free. It reads only
the three values supplied by the caller, matches plain keyword patterns and
imports no network or process client. It returns fixed labels rather than
echoing any part of the input, so a secret in a log cannot be returned in the
result. Callers still avoid putting secrets or patient data into logs at all.
"""
import re

# The first matching rule wins. Task and infrastructure failures are checked
# before code failures because they explain a red build that would otherwise be
# misread as a defect; parity is checked before coverage because a check that is
# green locally but red in Delivery is a gate gap, not a missing test.
CLASSES = ("task-policy", "missing-tests", "check-parity", "implementation", "infrastructure")

CATCH_LAYERS = {
    "task-policy": "task template",
    "missing-tests": "worker-check",
    "check-parity": "integrator-gate",
    "implementation": "unit test",
    "infrastructure": "contract test",
}
DEFAULT_CLASS = "implementation"

_RULES = (
    ("task-policy", re.compile(
        r"task[- ]policy|protected file|needs-human|needs-review|unknown requirement|"
        r"no such task|not in scope|out of scope|TODO\.md|shared tracker|unknown field", re.I)),
    ("check-parity", re.compile(
        r"check parity|passes? locally|green locally|fails? in delivery|only in delivery|"
        r"\bcontract\b|openapi|mismatch|not in the contract|missing from|drift|route registry", re.I)),
    ("infrastructure", re.compile(
        r"quota|rate[- ]?limit|bad credentials|permission denied|connection refused|"
        r"service unavailable|\btimed? out\b|\btimeout\b|out of memory|killed|"
        r"\bHTTP 5\d\d\b|no space left|ECONNRESET|runner lost|network unreachable", re.I)),
    ("missing-tests", re.compile(
        r"coverage|uncovered|missing tests?|no tests|tests? missing|threshold|"
        r"branch not taken|line not run", re.I)),
    ("implementation", re.compile(
        r"BUILD FAILURE|AssertionError|Error:|error TS\d+|\bFAIL(?:ED)?\b|"
        r"[✗×●]|exception|BugInstance|Total bugs|spotbugs|unexpected", re.I)),
)


def classify(log=None, task_text=None, changed_files=None):
    """Return the failure class and the layer that should have caught it.

    ``log`` is a failed CI log, ``task_text`` is the task description and
    ``changed_files`` is a sequence of repository-relative paths. Any of them
    may be empty or omitted. The result is a dictionary with ``class`` (one of
    :data:`CLASSES`) and ``catch_layer`` (one of :data:`CATCH_LAYERS`). A log
    with no recognised signal is treated as an implementation defect, the
    catch-all class: the five classes have no ``unknown`` member because every
    failed pull request needs an owner for its regression. The function never
    raises for missing inputs and never performs I/O.
    """
    parts = []
    if log:
        parts.append(log)
    if task_text:
        parts.append(task_text)
    if changed_files:
        parts.append(" ".join(changed_files))
    text = "\n".join(parts)
    for name, pattern in _RULES:
        if pattern.search(text):
            return {"class": name, "catch_layer": CATCH_LAYERS[name]}
    return {"class": DEFAULT_CLASS, "catch_layer": CATCH_LAYERS[DEFAULT_CLASS]}
