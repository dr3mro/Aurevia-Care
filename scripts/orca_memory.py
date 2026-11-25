"""Passive memory provenance and scoped row selection for the ORCA refactor.

This is a sibling analysis module, not a runtime memory service. It turns an
evidence reference into a provenance row and selects a deterministic subset of
rows under a row budget. All inputs and outputs are plain values; nothing is
read from disk, no repository command runs and no tracker is written. Callers
own persistence and promotion through the trusted pipeline.

The confidence values are an explicit local policy required by the memory
design: confidence is derived from the evidence kind and is never stored as an
unexplained number. ``Blackboard.publish`` accepts caller-supplied confidence
because it records live findings; provenance records historical evidence and
must be reproducible from the kind alone.
"""

# Authoritative tool and human evidence outranks a reviewer, which outranks a
# simulated walkthrough; an unavailable measurement carries no confidence.
KIND_CONFIDENCE = {
    "tool": 1.0,
    "reviewer": 0.9,
    "simulated-user": 0.6,
    "human": 1.0,
    "unavailable": 0.0,
}

# Authoritative kinds are never dropped by the budget, matching the rule that
# mandatory context sections are never truncated in ``compile_context``.
MANDATORY_KINDS = frozenset(("tool", "human"))
REQUIRED_FIELDS = frozenset(("path", "line", "kind", "revision", "fact"))


def provenance(path, line, kind, revision, fact):
    """Build one attributed memory row from an evidence reference.

    ``path`` and ``revision`` are non-empty strings, ``line`` is an integer
    line number, ``kind`` is one of :data:`KIND_CONFIDENCE` and ``fact`` is a
    strict boolean. Invalid inputs raise ValueError. The returned dict carries
    a ``location`` string plus the inputs and a confidence derived from the
    kind. No file is opened: the path is metadata only.
    """
    if not isinstance(path, str) or not path.strip():
        raise ValueError("provenance: path must be a non-empty string")
    if type(line) is not int or line < 1:
        raise ValueError("provenance: line must be an integer >= 1")
    if not isinstance(kind, str) or kind not in KIND_CONFIDENCE:
        raise ValueError("provenance: unknown evidence kind")
    if not isinstance(revision, str) or not revision.strip():
        raise ValueError("provenance: revision must be a non-empty string")
    if type(fact) is not bool:
        raise ValueError("provenance: fact must be a boolean")
    return {
        "location": f"{path}:{line}",
        "path": path,
        "line": line,
        "kind": kind,
        "confidence": KIND_CONFIDENCE[kind],
        "revision": revision,
        "fact": fact,
    }


def _checked_row(row, current_revision):
    """Return a canonical fresh row, or None when its revision is stale.

    Validation runs through :func:`provenance` so path, line, kind, revision
    and fact have exactly one rule. Invalid row shape and invalid field values
    raise ValueError; a stale row is skipped rather than reported as an error
    because superseded evidence is expected in a durable memory store.
    """
    if not isinstance(row, dict):
        raise ValueError("select_memory: row must be a provenance dict")
    if not REQUIRED_FIELDS.issubset(row):
        raise ValueError("select_memory: row is missing provenance fields")
    canonical = provenance(row["path"], row["line"], row["kind"],
                           row["revision"], row["fact"])
    # Validate every field before testing freshness: an invalid row is a caller
    # error even when its revision is stale, and must not be silently dropped.
    if canonical["revision"] != current_revision:
        return None
    return canonical


def select_memory(rows, budget, current_revision):
    """Select fresh, authoritative memory rows under a row-count budget.

    ``rows`` is an iterable of provenance dictionaries, ``budget`` is a
    non-negative integer and ``current_revision`` is a non-empty revision
    string. Invalid inputs raise ValueError. Stale revisions are dropped;
    fresh rows are ordered by confidence, fact before assumption, then path,
    line and kind. Mandatory kinds are always kept and optional kinds fill the
    remaining slots. Unlike ``compile_context``'s character budget, a zero
    count here keeps mandatory rows and drops every optional row: scoped
    retrieval must never dump the whole store when its allowance is zero.
    The returned tuple contains new dictionaries; inputs are not mutated.
    """
    if isinstance(rows, (str, bytes)) or not hasattr(rows, "__iter__"):
        raise ValueError("select_memory: rows must be an iterable of provenance rows")
    if type(budget) is not int or budget < 0:
        raise ValueError("select_memory: budget must be a non-negative integer")
    if not isinstance(current_revision, str) or not current_revision.strip():
        raise ValueError("select_memory: current_revision must be a non-empty string")
    fresh = []
    for row in rows:
        checked = _checked_row(row, current_revision)
        if checked is not None:
            fresh.append(checked)
    fresh.sort(key=lambda item: (-item["confidence"], -int(item["fact"]),
                                 item["path"], item["line"], item["kind"]))
    mandatory = [item for item in fresh if item["kind"] in MANDATORY_KINDS]
    optional = [item for item in fresh if item["kind"] not in MANDATORY_KINDS]
    return tuple(mandatory + optional[:max(0, budget - len(mandatory))])
