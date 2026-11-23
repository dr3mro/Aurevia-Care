# Deprecation and removal checklist

Use this when removing a route, a flag, a field, a synthetic fixture path, a schema
object or a whole module. Schema changes additionally follow the expand/contract
policy in `docs/decisions/0002-ops06-migration-change-safety.md`; this checklist is
about the *removal* half, which is easy to forget once the replacement works.

## Before removing anything

- [ ] A replacement exists and is reachable through a real entry point. Do not
      deprecate without an alternative; build the alternative first.
- [ ] Every consumer is identified. Search the API routes, the generated staff-web
      client, the patient app, workers, contracts and fixtures — not just the source
      folder being edited.
- [ ] Consumers have migrated. Advisory removal gives them time; compulsory removal
      needs a reason (security risk, unsustainable maintenance) and migration tooling.
- [ ] The removal is scoped to one task. If it needs another task's files, request
      that task instead of widening the change.

## In the change

- [ ] Remove the implementation and its tests together with the call sites, not in
      separate later commits that can be lost.
- [ ] Update `contracts/openapi.yaml` and regenerate `apps/staff-web/src/api/generated/openapi.d.ts`
      in the same change; run `gen:api:check`.
- [ ] Remove the route from any "missing routes" baseline rather than leaving an
      entry that no longer describes reality.
- [ ] Delete dead feature flags, dead configuration keys and their seeds; leaving a
      flag that always takes one branch creates an uncoverable branch and a false
      sense of configurability.
- [ ] For a schema column or table, follow expand/contract: add and backfill first,
      switch all readers, then drop in a later migration with the required change
      headers. Never rename in place in one release.
- [ ] Update the developer reference, decision records and trackers that name the
      removed thing. A stale pointer is a defect for the next agent.

## Verification

- [ ] The removed symbol, route or flag no longer appears in source, contracts,
      generated clients, fixtures or docs (a targeted search is evidence).
- [ ] `python3 -I scripts/check-wiring.py` and `python3 -I scripts/check-docs.py` pass.
- [ ] `python3 -I scripts/check-migrations.py --require-baseline` passes when a
      migration is involved.
- [ ] The affected module's canonical check passes and the task records what was
      removed, why, and the evidence that nothing still calls it.

## Red flags

- "Keep it for backward compatibility" with no consumer, no test and no owner.
- A deprecation notice added without a date or a removal task.
- A dead branch kept only to preserve a coverage number; delete the branch instead.
