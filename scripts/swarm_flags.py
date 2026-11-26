"""Canonical repository-variable resolution shared by the swarm kill switches.

Repository variables and GitHub expression values arrive as strings. Live values are
written in lowercase by convention, but an operator may type them in another case or
with padding; normalising here keeps that behaviour identical for the Python CLI and
for workflows, which cannot trim or case-fold in an expression. Boolean flags use the
four enable and four disable tokens; numeric limits accept padded or leading-zero
digits. These helpers are pure: no environment reads, no Git or GitHub calls, so every
consumer shares one rule.

``python3 -I scripts/swarm_flags.py`` writes canonical values for every flag and limit
to the file named by GITHUB_OUTPUT. The reusable ``swarm-flags.yml`` workflow runs it
once and every gate consumes its outputs rather than a raw variable.
"""

import os
import re

ENABLE_TOKENS = ("1", "true", "yes", "on")
DISABLE_TOKENS = ("0", "false", "no", "off")
DIGITS = re.compile(r"[0-9]+")
WAVE_WORKERS_CAP = 20
MAX_ACTIVE_WORKERS_CAP = 20

# Output name, repository variable and the meaning of an unset value. The defaults
# preserve the behaviour each switch had before this helper existed: a kill switch
# is on unless told otherwise, while opt-in modes stay off. max_task=false restores
# the serialized single-wave, single-worker lifecycle.
FLAG_SPECS = (
    ("enabled", "SWARM_ENABLED", True),
    ("max_task", "SWARM_MAX_TASK", False),
    ("llm_selection", "SWARM_LLM_SELECTION", False),
    ("issues", "SWARM_ISSUES", True),
    ("notes", "SWARM_NOTES", True),
)

# Output name, repository variable, unset default, invalid default, lower and upper
# bound. An unset value means the operator wants the documented capacity; a value that
# is not a plain integer fails closed to the smallest safe wave instead of widening
# it. Both numeric values stay inside the existing hard worker limits.
NUMBER_SPECS = (
    ("max_workers_per_wave", "SWARM_MAX_WORKERS_PER_WAVE", 20, 1, 1, WAVE_WORKERS_CAP),
    ("max_wave_count", "SWARM_MAX_WAVE_COUNT", 3, 1, 1, MAX_ACTIVE_WORKERS_CAP),
)


def _token(value):
    """Normalise a raw variable value for membership checks.

    Args: value is the raw string from a repository variable or workflow env.
    Returns: the trimmed lowercase token. Errors: none; every caller supplies a
    string, so no defensive type branch can hide an uncovered path.
    """
    return value.strip().lower()


def enabled(value, default):
    """Return the opt-in meaning of a raw repository-variable string.

    Args: value is the raw string; default is the boolean returned when the value
    is neither an enable nor a disable token. Returns True for an enable token,
    False for a disable token, otherwise the default. The invariant is that only
    the shared canonical tokens can override the default.
    """
    token = _token(value)
    if token in ENABLE_TOKENS:
        return True
    if token in DISABLE_TOKENS:
        return False
    return default


def disabled(value, default):
    """Return the opt-out meaning of a raw repository-variable string.

    Mirror of enabled(): True for a disable token, False for an enable token and
    the given default for anything else. Default-on switches must pass False so
    an unset variable means "not disabled".
    """
    token = _token(value)
    if token in DISABLE_TOKENS:
        return True
    if token in ENABLE_TOKENS:
        return False
    return default


def number(value, unset_default, invalid_default, minimum, maximum):
    """Resolve a bounded integer capacity from a raw repository-variable string.

    Args: value is the raw string; unset_default applies to an empty value,
    invalid_default to a non-integer value; minimum and maximum bound the result.
    Returns: an integer inside those bounds. Errors: none; unparseable input is
    deliberately mapped to invalid_default so an operator typo cannot launch more
    workers than the configured safety limit.
    """
    token = _token(value)
    if not token:
        return unset_default
    if not DIGITS.fullmatch(token):
        return invalid_default
    return max(minimum, min(int(token), maximum))


def github_outputs(environ, stream):
    """Write one canonical ``name=value`` line per flag and limit to a text stream.

    Args: environ maps repository-variable names to raw strings; stream is a
    text sink such as the file named by GITHUB_OUTPUT. Returns: none. A missing
    or unrecognised value uses that setting's documented default, so a switch
    never fails open when an operator sets a value this contract does not accept.
    """
    for name, variable, default in FLAG_SPECS:
        value = enabled(environ.get(variable, ""), default)
        stream.write(f"{name}={str(value).lower()}\n")
    for name, variable, unset, invalid, minimum, maximum in NUMBER_SPECS:
        value = number(environ.get(variable, ""), unset, invalid, minimum, maximum)
        stream.write(f"{name}={value}\n")


def main():
    """Emit canonical flags to GITHUB_OUTPUT for the reusable flags workflow.

    The output file is required: if the runner did not supply one, the caller
    fails closed instead of silently falling back to raw workflow expressions.
    """
    path = os.environ.get("GITHUB_OUTPUT")
    if not path:
        raise SystemExit("GITHUB_OUTPUT is required to resolve swarm flags")
    with open(path, "a", encoding="utf-8") as stream:
        github_outputs(os.environ, stream)


if __name__ == "__main__":
    main()
