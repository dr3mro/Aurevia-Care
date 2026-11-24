- [-] AUDIT-FIX-REMAINING · Repair the remaining naming, link-integrity and gate findings from the repository audit
  - agent: orca
  - requirements: OPS-06
  - scope: scripts/check-docs.py, scripts/tests/test_check_docs.py, docs/spec/clauses, docs/operations, apps/patient-mobile/app.json, apps/patient-mobile/src/app/appConfig.test.ts, apps/patient-mobile/package.json, apps/patient-mobile/package-lock.json, scripts/*.sh
  - delivery: interactive, human-reviewed scoped commit on main
  - details:
    The 2026-11-24 naming and path audit left these open findings after the
    concurrent path/migration cleanup (commit 28cfb5b4):
    P-11 (P1): scripts/check-docs.py skips every link whose target basename is
    in MOVED_DOCS, so active broken links pass the gate; it also does not skip
    the historical records moved to .agents/reports/, so the gate now fails on
    148 historical false positives. Fix the checker with tests that prove both
    sides, then repair the active links it reveals.
    N-01: com.example.aureviacare bundle identifier and Android package are
    placeholders that violate the canonical aurevia technical identifier.
    N-02: apps/patient-mobile/package.json name lacks the aurevia- prefix.
    P-12: three shell scripts lack the executable bit in the Git index.
    Compatibility-sensitive N-03 (xch03_/rx02_ table and function names in
    applied migrations) and N-04 (X-04 public contract label) are tracked
    separately because renaming persistent identifiers needs owner approval.
    Do not weaken a gate, skip a test or edit an applied migration.
