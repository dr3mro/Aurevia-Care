# Developer reference

Start with [Maintainer guide](maintainer.md). The class, package, file, and search navigation fills in as Java code is added. Open a documented symbol to find its usage contract and source.

- [Staff web TypeScript reference](staff-web/index.html)
- [Patient mobile TypeScript reference](patient-mobile/index.html)

## On-demand checklists

- [Git workflow and conflict policy](git-workflow.md) — branch discipline, refresh before the final gate, conflict resolution and merge bookkeeping.
- [Deprecation and removal checklist](deprecation-checklist.md) — remove a route, flag, field or module without leaving stale consumers.
- [Runtime UI verification](browser-verification.md) — what to observe in a browser, or assert deterministically when none is available.
- [Observability checklist](observability-checklist.md) — the signals a new critical path must emit, and the on-call questions they answer.
- [Performance checklist](performance-checklist.md) — web Core Web Vitals targets, bundle budget and backend query expectations.

## Design and decisions

- [Responsive layout patterns](../design/responsive-layout.md) — the staff-web layout contract from 320 px upward: breakpoints, navigation, tables, touch targets, right-to-left and its verification matrix.
- [Decision 0004: Responsive staff web](../decisions/0004-responsive-staff-web.md) — the proposed decision to make the staff web fully usable from 320 px, superseding the 768 px "use a larger screen" floor.
- [Decision 0005: Patient app support for tablets and landscape](../decisions/0005-patient-app-tablets.md) — the proposed decision to size patient-app layout from window width and support tablets, landscape and 200% text scaling.

These sites are built from the repository. Module references marked pending will fill in when the module is implemented. Use the browser's search and source links to follow code. Keep this documentation within the repository audience since it can expose source and implementation details.
