"""Canonical boolean repository-variable tokens shared by the swarm kill switches.

Repository variables and GitHub expression values arrive as strings. Live values are
written in lowercase by convention, but an operator may type them in another case or
with padding; normalising here keeps that behaviour identical to the case-insensitive
string comparisons in GitHub Actions. Only the four enable and four disable tokens
override a caller's default; anything else is treated as unset. These helpers are
pure: no environment reads, no Git or GitHub calls, so every consumer shares one rule.
"""

ENABLE_TOKENS = ("1", "true", "yes", "on")
DISABLE_TOKENS = ("0", "false", "no", "off")
ENABLE_JSON = '["1","true","yes","on"]'
DISABLE_JSON = '["0","false","no","off"]'


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
