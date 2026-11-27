- [-] DATE-OFFSET-PARSE · Parse PostgreSQL hour-only timezone offsets in instant assertions
  - agent: orca
  - attempts: 0
  - requirements: OPS-06
  - scope: apps/api/src/test/java/com/aurevia/lab/clauses/RecordSpecimenTypeSiteCollectorCollectionReceiptTest.java, tasks/01352000-DATE-OFFSET-PARSE.md
  - evidence:
    Test evidence (tool): targeted `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=RecordSpecimenTypeSiteCollectorCollectionReceiptTest test` exit 0; full `bash apps/api/check.sh` exit 0. Tested revision: 32fffb6e987e70f6353f266bf1839c81576373cb.
    Coverage: no production lines changed; the two previously failing tests now pass.
    Review verdicts: code-reviewer GO, coverage-auditor GO.
  - details:
    Baseline red: `RecordSpecimenTypeSiteCollectorCollectionReceiptTest.collectionRecordsSpecimenTypeSiteCollectorAndCollectionTime` and `receiptRecordsQualityAndReceiptTime` fail with `DateTimeParse Text '2026-11-19T05:15:30-05' could not be parsed at index 19`. The assertion helper normalizes `+00` to `Z` but not PostgreSQL's hour-only `-05` offset. Fix the test helper to expand `[+-]HH` to `[+-]HH:MM` before `Instant.parse`. No production code or gate threshold changed.
