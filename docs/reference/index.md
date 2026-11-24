# Developer reference

Start with [Maintainer guide](maintainer.md). The class, package, file, and search navigation fills in as Java code is added. Open a documented symbol to find its usage contract and source.

- [Staff web TypeScript reference](staff-web/index.html)
- [Patient mobile TypeScript reference](patient-mobile/index.html)

## Guides

- [Role task guides](../guides/index.md) — plain-language guides for staff and patients that name each screen and the exact control label to use.

## Reference

- [Data dictionary](data-dictionary.md) — every table created by the Flyway migrations, with tenant ownership, isolation and write policy.
- [Bilingual glossary](glossary.md) — clinical and product terms mapped to the Arabic interface strings, plain meanings and owning code names.
- [Partner integration guide](partner-api-guide.md) — connecting a laboratory or imaging partner to receive exchange orders and return results.
- [Synthetic data catalogue](synthetic-data.md) — the invented fixtures the tests actually use, so new workers reuse them instead of inventing lookalikes.
- [Testing guide](testing-guide.md) — every verification check, what it proves and where it runs.

## Operations

- [Operator runbooks](../operations/runbooks/index.md) — short procedures for the operational events this repository can describe, with who acts and what to record.
- [Service level objectives](../operations/slo.md) — the target-to-signal trace from the specification's operational targets to fields, logs and runbook entries.
- [Operational exercise sheets](../operations/exercises/index.md) — synthetic recovery and failure rehearsal scripts; they are not evidence that a drill ran.

## Security

- [Security documentation](../security/README.md) — index of the security pages, separating controls present in code from gaps that still need work.
- [Personal data inventory](../security/data-inventory.md) — engineering review draft of what the code and migrations store, for a privacy officer to review.

## On-demand checklists

- [Git workflow and conflict policy](git-workflow.md) — branch discipline, refresh before the final gate, conflict resolution and merge bookkeeping.
- [Deprecation and removal checklist](deprecation-checklist.md) — remove a route, flag, field or module without leaving stale consumers.
- [Runtime UI verification](browser-verification.md) — what to observe in a browser, or assert deterministically when none is available.
- [Observability checklist](observability-checklist.md) — the signals a new critical path must emit, and the on-call questions they answer.
- [Performance checklist](performance-checklist.md) — web Core Web Vitals targets, bundle budget and backend query expectations.

## Design and decisions

- [Arabic interface style guide](../design/arabic-style-guide.md) — working rules for writing and reviewing Arabic strings, each tied to repository evidence.
- [Responsive layout patterns](../design/responsive-layout.md) — the staff-web layout contract from 320 px upward: breakpoints, navigation, tables, touch targets, right-to-left and its verification matrix.
- [Decision 0004: Responsive staff web](../decisions/0004-responsive-staff-web.md) — the proposed decision to make the staff web fully usable from 320 px, superseding the 768 px "use a larger screen" floor.
- [Decision 0005: Patient app support for tablets and landscape](../decisions/0005-patient-app-tablets.md) — the proposed decision to size patient-app layout from window width and support tablets, landscape and 200% text scaling.

These sites are built from the repository. Module references marked pending will fill in when the module is implemented. Use the browser's search and source links to follow code. Keep this documentation within the repository audience since it can expose source and implementation details.
