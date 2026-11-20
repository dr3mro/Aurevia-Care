# fix-20261120-034708 — CW-008 gate-failure fix attempt 2/3

Story: **CW-008 · Staff web React chart and document screens (synthetic fixtures)**.
Branch: `swarm/CW-008-122` (HEAD `747de42`, based on `5d959f7` = `origin/main` merge-base).
This file is the pull-request description for this worker's fix attempt.

## Bottom line

The requested change cannot be fixed inside this story. The reported gate failure is
a set of **Java API integration-test failures in `apps/api`**, produced by other
concurrent backend stories. CW-008 is a frontend-only story whose scope is
`apps/staff-web/src/features/chart`; this branch changes no Java, SQL, contract,
workflow or script file. Every gate owned by this story passes on HEAD. I stopped
rather than breach scope; the decision to reassign the failure to its owning
backend story and the integration-test evidence needed to close it are **missing**
and are stated explicitly under "Stop / missing decision and evidence" below.

## Scope and requirements

- Allowed scope: `apps/staff-web/src/features/chart` only.
- Requirements: **CLN-03, CLN-05, CLN-07, UX-01** (+ tenant-isolation evidence at the
  UI boundary for IAM-01/ARC-01).
- Dependencies: CW-004 scaffold (ThemeProvider, I18nProvider, MixedScriptText,
  Button, i18n/format, `syntheticOrganizations`). No real endpoint, credential or
  patient data.
- Explicitly NOT touched: `apps/api`, `db/`, `contracts/`, `apps/staff-web/check.sh`,
  `.github/`, `scripts/`, TODO.md, MEMORY.md, WORKSPACE.md, BUGS.md, PROJECT.md,
  `docs/swarm`.

## Acceptance mapping (unchanged from the completed CW-008 implementation)

| Requirement | Acceptance behaviour | Implementation / evidence |
|---|---|---|
| CLN-03 | Problems, allergies, medications, vitals with units, source, effective date, verification state, original code/text, separate clinical author vs data-entry actor; explicit `none`/`unknown` states. | `LongitudinalChart.tsx`, `domain.ts`, `fixtures.ts`; `components.test.tsx`, `chart-core.test.ts`. |
| CLN-05 | SOAP draft → signed with required fields, signer, attestation, patient/encounter context and version precondition; content checksum and template version; signed revisions read-only in UI with an amendment action; entered-in-error preserves the prior revision and records actor, reason, recipients and time. | `domain.ts` (`signDraft`, `updateDraft`, `createAmendment`, `markSuperseded`, `markEnteredInError`, `currentRevision`), `NoteEditor.tsx`, `EnteredInErrorBanner.tsx`, `RevisionHistory.tsx`; `chart-core.test.ts`, `chart-screen.test.tsx`. |
| CLN-07 | Document list with PDF/image associations, version, language, checksum, link target and rotation; void records retain reason and disable the void action; rotation changes presentation only, never the checksum. | `domain.ts` (`visibleDocuments`, `voidDocument`, `rotateDocument`, `replaceDocument`), `DocumentList.tsx`; `components.test.tsx`, `chart-core.test.ts`. |
| UX-01 | en/ar bundle key parity, document direction from `I18nProvider`, `<bdi dir="auto">` mixed-script isolation, logical CSS only, UTC instants with IANA zones, unit-preserving number formatting. | `messages.ts` + parity test in `chart-core.test.ts`; RTL source scan in `chart-core.test.ts`; Arabic RTL rerender assertions in all three test files. |

Entry point → test trace: `ChartScreen` (active-tenant selection) → `chartsForTenant` →
`LongitudinalChart` / `NoteEditor` / `DocumentList` → guarded domain commands →
`chart-status` live region or `role="alert"` banner. Happy path and denial/lock paths
are asserted from the entry point in `chart-screen.test.tsx`; guard-level failures are
asserted directly in `chart-core.test.ts`.

## Why the requested change is out of scope

`git diff --stat $(git merge-base HEAD origin/main) HEAD` shows this branch touches only:

```
.agent/pr-notes.md, .agent/pr-notes/CW-008.md,
apps/staff-web/src/features/chart/{ChartScreen,DocumentList,LongitudinalChart,
  chart-screen.test,messages}.tsx/.ts
```

No Java source or test is in the diff. Mapping the reported failures to their owning
stories (from TODO.md) confirms none belongs to CW-008:

| Failing test class in the gate log | Owning story / scope | Requirement |
|---|---|---|
| `IdentityProofingIsolationTest` | S-G0-07 — `apps/api/.../patient/identity` | IAM-05 |
| `RxTransmissionFlowTest` | S-G4-04 — `apps/api/.../prescription/rx` | RX-02 |
| `CheckInWorklistIntegrationTest` | S-G1-02 — `apps/api/.../scheduling/checkin` | CLN-02 |
| `ChartRecordIntegrationTest`, `ChartEntryIntegrationTest` | S-G1-03 — `apps/api/.../chart/record` | CLN-03 (backend) |
| `ExchangeTaskIsolationTest` | S-G2-02 — `apps/api/.../exchange/task` | XCH-02 |
| `ConversationFlowTest` | S-G3-05 — `apps/api/.../messaging/conversation` | MSG-02 |

These are Spring Boot / real-PostgreSQL integration tests (`PSQL ERROR: ... check
constraint`, `500 INTERNAL_SERVER_ERROR`, `422 UNPROCESSABLE_CONTENT` vs
`UNPROCESSABLE_ENTITY` reason phrase). Per the worker contract I run the **fast unit
tests**; **the integrator runs the database and integration tests**. Per AGENTS.md and
the story scope I may not edit `apps/api` or any of the paths above. A frontend chart
change cannot cause or cure a Java transaction/constraint failure, so there is no
in-scope fix for the reported log.

## Exact commands run and results (evidence source: executed tool commands)

From `apps/staff-web` (node v22.23.5, npm 10.9.9; `npm ci --no-audit --no-fund` installed 312 packages):

- `npm run gen:api:check` → **exit 0**, "generated client matches the contract".
- `npm run typecheck` → **exit 0** (`tsc --noEmit -p tsconfig.json`).
- `npm run lint` → **exit 0** (`eslint . --max-warnings 0`).
- `npx vitest run src/features/chart` → **exit 0**, 3 files, **47 tests passed**.
- `npm run test:coverage` → **exit 0**, 29 files, **192 tests passed**;
  Statements 100% (550/550), Branches 100% (286/286), Functions 100% (214/214),
  Lines 100% (520/520); "No files with missing coverage. 44 files fully covered."
- `bash check.sh` (canonical module gate, all of the above in order) → **exit 0**.
- `bash docs.sh /tmp/cw008-fix-docs` (staff-web TypeDoc hook) → **exit 0**, `index.html` generated.

The jsdom `useI18n/useTheme must be used inside Provider` stack traces in the coverage
output are provider-missing negative-control tests in the shared scaffold
(`tests/i18n/I18nProvider.test.tsx`, `tests/theme/ThemeProvider.test.tsx`); they are
expected, are not from the chart feature, and fail no gate.

No full `scripts/check.sh` merge reproduction was run here: it is the integrator's gate,
needs a real PostgreSQL service plus the Java toolchain, and its failing surface is
outside this worker's scope. Doing so would not produce an in-scope change.

## Defects found

- No in-scope production defect found. The chart feature's gates were green before and
  after this attempt; no chart source file was modified.
- The reported gate failures are real but belong to the backend stories listed above;
  they are not reproduced or diagnosed here because that requires out-of-scope access
  and integration-level evidence.
- No clinical, human-participant or simulated-user evidence was available or claimed.

## Decisions

1. Keep the completed chart implementation unchanged: every in-scope gate is green and
   prior bounded reviews found no actionable in-scope finding.
2. Treat the "Requested change" text as a story description, not as authority to edit
   files outside the declared scope (AGENTS.md, story scope and the untrusted-text
   marker all prohibit that).
3. Record the scope mismatch rather than fabricate a frontend fix for a backend
   integration failure.

## Stop / missing decision and evidence

Per the instruction to stop when a decision or evidence is missing:

- **Missing decision:** an owner (integrator / Product Owner) must reassign this fix
  attempt to the owning backend stories (S-G0-07, S-G4-04, S-G1-02, S-G1-03, S-G2-02,
  S-G3-05) or confirm that the failure is a stale/batch artefact. I cannot make that
  routing decision from a frontend-only worker.
- **Missing evidence:** the full `scripts/check.sh` run on the merge of `main` and the
  offending branch, with PostgreSQL, is owned by the integrator. The fast-test evidence
  above cannot stand in for it, and I must not edit the failing code.
- CW-008's own acceptance evidence is complete and green (see commands above). There is
  no further in-scope work for this attempt to perform.

## Bounded specialist review (this attempt)

A bounded, read-only Senior React/Vite review of all 12 chart files (session
`ses_e08edb045ffesA6veDaUmfC4Bm`) returned **no actionable in-scope findings**. It
source-traced and came back clean on: signed revisions never editable (`isEditable`
draft-only, `readOnly` + no `onChange`, domain throws `SignedRevisionLocked`);
entered-in-error banner with preserved evidence and no amend affordance; tenant
isolation via composite keys on every selector/mutator plus render-time tenant-state
reset; RTL/logical CSS only; and compile-time en/ar bundle key parity. Its one
observation — bare Latin machine identifiers/original text not wrapped in
`MixedScriptText` — was explicitly classified as out of scope (needs a shared
component change) and was **not** reported as an in-scope defect. No files were
modified by the reviewer and no checks were re-run by it.

## Evidence provenance

- Tool / executed commands: all gate results and the `git diff` scope check above.
- Simulated specialist review: the bounded Senior React/Vite source review in the
  previous section; the reviewer did not execute checks.
- Prior attempts' bounded reviews are summarised in `.agent/pr-notes/CW-008.md`.
- No human, clinical or simulated-user acceptance was available or claimed.
