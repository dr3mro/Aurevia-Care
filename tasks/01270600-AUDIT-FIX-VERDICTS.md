- [-] AUDIT-FIX-VERDICTS · Verdict parser treats "not resolved" as resolved
  - agent: orca
  - requirements: OPS-06
  - scope: scripts/check-verdicts.py, scripts/tests/test_check_verdicts.py, tasks/01270600-AUDIT-FIX-VERDICTS.md
  - details:
    The regex (?<!un)\bresolved\b ignores only "unresolved" but matches "not
    resolved", so a finding line can be silently dropped. Fail closed on any
    negated resolved token and cover both sides with unit tests.

  - agent: orca