# Performance checklist

Quick reference for staff-web, patient-mobile and API performance work. Use alongside
`smartclinic-senior-react`, `smartclinic-senior-mobile` and `smartclinic-senior-backend`.
The repo already enforces the WEB-08 bundle budget; this checklist covers the behaviors
that a build-time gate cannot see.

## Targets

| Metric | Good | Needs work |
|---|---|---|
| Staff-web JS bundle (gzip) | under the WEB-08 budget in `scripts/check-bundle-size.mjs` | over budget (fails CI) |
| Largest Contentful Paint (web) | ≤ 2.5 s | > 4.0 s |
| Interaction to Next Paint (web) | ≤ 200 ms | > 500 ms |
| Cumulative Layout Shift (web) | ≤ 0.1 | > 0.25 |
| API p95 for a protected read | under the agreed SLO in `docs/operations/slo.md` | above SLO |

## Frontend

- [ ] Route screens are code-split; no feature is eagerly imported into the main chunk.
- [ ] Long lists are paginated with `cursor`/`limit` and never loaded unbounded.
- [ ] Images have explicit dimensions so layout does not shift.
- [ ] Abortable fetches use the `AbortSignal` threaded by `useApi`.
- [ ] Arabic and English layouts were checked at compact and expanded widths (UX-01, WEB-07).

## Backend

- [ ] Queries used by a new route have an index aligned to their tenant predicate and sort order.
- [ ] No N+1 query in a list or detail projection; verify with a query-count assertion.
- [ ] RLS-scoped tables are queried with the tenant GUC set; no cross-tenant scan.
- [ ] Bounded page sizes and timeouts on all external calls and retries.

## Verification

- [ ] `bash apps/staff-web/check.sh` (bundle-size gate included) passes.
- [ ] A performance assertion exists for any new budget-sensitive behavior.
- [ ] The change does not weaken a threshold; `scripts/check-constraints.py` passes.

## Red flags

- "It feels fast enough" with no measurement.
- A new unbounded `findAll` or `map` over a tenant's full table.
- A bundle-size exclusion added to make the gate green.
