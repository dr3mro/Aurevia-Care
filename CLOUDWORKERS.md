# Cloud workers task list

The swarm orchestrator reads this file. Each wave it picks `todo` tasks whose dependencies are `done` and whose scopes do not overlap with each other or with open swarm pull requests, and gives one to each free worker slot (at most 10). Workers follow AGENTS.md, CODING_STYLE.md, SMARTCLINIC_V1_SPEC.md and the `.opencode` specialists, commit to their own `swarm/<ID>-<run>` branch and never edit shared trackers. The integrator merges a branch only after the full check passes on the merge result, then marks the task `done` here and adds a PROGRESS.md row. See [docs/SWARM.md](docs/SWARM.md).

Columns: `Scope` lists comma-separated path prefixes the worker may change. `Reserved` is the one database migration version the task may add, so parallel tasks never collide. `Status` is `todo`, `done` or `blocked`. The integrator fills `PR`.

## Tasks

| ID | Task | Requirements | Depends | Scope | Reserved | Status | PR |
|---|---|---|---|---|---|---|---|
| CW-001 | Staff lifecycle and immutable membership history | IAM-02, IAM-01 | - | apps/api/src/main/java/com/aurevia/smartclinic/staff, apps/api/src/test/java/com/aurevia/smartclinic/staff, db/migrations | V4 | done | #7 |
| CW-002 | Tenant configuration, catalog and broader audit | ARC-02, OPS-02 | - | apps/api/src/main/java/com/aurevia/smartclinic/config, apps/api/src/test/java/com/aurevia/smartclinic/config | V5 | done | e8911e9 |
| CW-003 | Concurrent-connection tenant isolation tests | ARC-02 | - | apps/api/src/test/java/com/aurevia/smartclinic/it | - | todo | |
| CW-004 | Staff web foundation: Vite/React/TS scaffold, design system and generated client | PRD-06, UX-01, ARC-03 | - | apps/staff-web | - | todo | |
| CW-005 | Patient mobile foundation: Expo/React Native/TS scaffold and accessible shell | PAT-01, PAT-02, PAT-03, UX-01 | - | apps/patient-mobile | - | todo | |
| CW-006 | Bilingual clinical design language and token contract documentation | UX-01, PRD-05 | - | docs/design | - | todo | |

### Reconciliation (2026-11-19)

CW-001 and CW-002 were delivered directly on `main` rather than by a swarm worker, so the integrator never saw a PR to mark them done. They are recorded here with the delivering PR/commit as evidence: CW-001 → PR #7 (`8d015b5`, 81 tests, JaCoCo 100%, green CI); CW-002 → commit `e8911e9` (119 tests, JaCoCo 100%, CI run 44455300465 green). CW-003 remains the only outstanding task. This prevents the swarm from re-attempting finished work.

### CW-001

Story S-G0-02 from TODO.md. Invite and deactivate staff with effective dates. Keep an immutable grantor history. A permission change must apply on the next protected request. Write failing tests first through the staff lifecycle entry point and keep JaCoCo at 100%.

### CW-002

Story S-G0-03 from TODO.md. Tenant-scoped configuration and catalog records with audit events, behind the existing tenant context and RLS policies. Tests first, 100% coverage.

### CW-003

Story S-G0-04 from TODO.md, test part only. Add concurrent-connection tests proving no tenant context leaks between pooled connections. Do not change production code unless a test exposes a defect, and then record it in the notes.

### CW-004

Frontend foundation for the staff web, in `apps/staff-web` only. Story S-G1 work but scoped to the empty module so it cannot collide with backend work. Set up React + TypeScript + Vite with strict TypeScript, a version-pinned toolchain and a lockfile. Add the module's own `check.sh` (typecheck, lint, Vitest with fail-under 100% coverage over all source files) and a `docs.sh` hook that writes `index.html` with TypeDoc and fails on warnings. Build the visual foundation only: app shell, tenant/organization switcher placeholder, sign-in and protected route placeholders, a small accessible component set (buttons, tables, forms, dialogs), light/dark theming, and English + Arabic (RTL) resource bundles with mixed-script and local-name display. Use generated OpenAPI client types from `contracts/openapi.yaml` via a checked-in generation script if practical; never hand-copy the contract. Do NOT call real endpoints, add authentication logic, touch `apps/api`, `db/`, `contracts/`, root-level `check.sh`, `.github/`, `scripts/`, or any shared tracker. Synthetic fixtures only. Write the requirement IDs, commands run, results and decisions to `.agent/pr-notes.md`. Keep JaCoCo-equivalent coverage at 100%.

### CW-005

Frontend foundation for the patient mobile app, in `apps/patient-mobile` only. Story S-G3 work but scoped to the empty module so it cannot collide with backend work. Set up Expo + React Native + TypeScript with strict TypeScript, a version-pinned toolchain and a lockfile. Add the module's own `check.sh` (typecheck, lint, Jest with React Native Testing Library and fail-under 100% coverage over all source files) and a `docs.sh` hook that writes `index.html` with TypeDoc and fails on warnings. Build the visual foundation only: app shell, bottom-tab navigation with appointment/report/message/profile placeholders, an OS-backed secure-credential storage interface (no real secrets), generic push-notification placeholder with generic text, and English + Arabic (RTL) resource bundles with local-name display. Include native screen-reader labels and accessibility IDs on every interactive element. Do NOT call real endpoints, add clinical data, touch `apps/api`, `db/`, `contracts/`, root-level `check.sh`, `.github/`, `scripts/`, or any shared tracker. Synthetic fixtures only. Write the requirement IDs, commands run, results and decisions to `.agent/pr-notes.md`. Keep coverage at 100%.

### CW-006

Bilingual clinical design-language documentation, in `docs/design` only. No application code. Produce a maintainer-facing design system specification for the staff web and patient app: color and typography tokens, spacing scale, light/dark theming, RTL mirroring rules, local-name and mixed-script display guidance, accessible focus states and WCAG 2.2 AA patterns for forms, tables, dialogs and critical-result banners. Include a token naming contract that CW-004 and CW-005 can adopt later, with a short mapping to requirement IDs (UX-01, PRD-05). Keep every Markdown link valid and every requirement ID defined in the specification. Do NOT touch `apps/`, `db/`, `contracts/`, `.github/`, `scripts/`, root-level `check.sh`, or any shared tracker. Write the requirement IDs and decisions to `.agent/pr-notes.md`.
