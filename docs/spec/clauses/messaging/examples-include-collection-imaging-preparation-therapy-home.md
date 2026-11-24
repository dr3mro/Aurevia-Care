# MSG-04 clause 3 — examples include collection, imaging preparation, therapy home instructions, visit follow-up and pharmacy pickup

## Clause

Examples include collection, imaging preparation, therapy home instructions, visit follow-up and pharmacy pickup.

## Status

Proven by tests

## Evidence

Fast, database-free unit proof (synthetic fixtures only):

- Test class: `com.aurevia.messaging.clauses.ExamplesIncludeCollectionImagingPreparationTherapyHomeTest`
  (path: `apps/api/src/test/java/com/aurevia/messaging/clauses/ExamplesIncludeCollectionImagingPreparationTherapyHomeTest.java`).
- `sendsEveryNamedExampleInstructionKind` — positive proof. A clinician sends one free-text instruction for
  each of the five named kinds (`COLLECTION`, `IMAGING_PREPARATION`, `THERAPY_HOME`, `VISIT_FOLLOW_UP`,
  `PHARMACY_PICKUP`) through `InstructionService.send`. Each returned `Revision` keeps its `kind()` and is
  `SENT`, and `listForPatient` returns exactly those five kinds.
- `refusesAnUnsupportedExampleInstructionKind` — negative proof. `Kind.from("unsupported_example")` throws,
  and each named kind's stable wire key round-trips through `Kind.from`, so the five named examples are members
  of a bounded, typed `Kind` enum and arbitrary wire strings are refused rather than accepted as free text.

Production classes exercised:

- `com.aurevia.messaging.instructions.InstructionContract.Kind` — the typed example vocabulary
  with stable wire keys and `wire()` / `from(String)`.
- `com.aurevia.messaging.instructions.InstructionService` — `send` carries the kind into a
  published `Revision`; `listForPatient` surfaces the revisions over an active patient-unit connection.
  The test calls `listForPatient` as a unit-assigned clinician, so it exercises the staff branch of that
  read (active connection plus unit assignment), not patient-chart binding; patient-authenticated access
  belongs to an adjacent clause.
- `com.aurevia.messaging.instructions.InstructionStore.InMemory` and
  `com.aurevia.messaging.instructions.InstructionActions` — synthetic store and bounded role
  matrix used by the fast test.

Adjacent production found in the survey but not part of this clause's proof: the MSG-04 instruction routes
are absent from `contracts/openapi.yaml` and there is no `JdbcInstructionStore` or migration for instruction
revisions. These are tracked elsewhere and are not a gap in this clause.

## Gap

None
