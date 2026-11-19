# Cloud workers task list

The swarm orchestrator reads this file. Each wave it picks `todo` tasks whose dependencies are `done` and whose scopes do not overlap with each other or with open swarm pull requests, and gives one to each free worker slot (at most 10). Workers follow AGENTS.md, CODING_STYLE.md, SMARTCLINIC_V1_SPEC.md and the `.opencode` specialists, commit to their own `swarm/<ID>-<run>` branch and never edit shared trackers. The integrator merges a branch only after the full check passes on the merge result, then marks the task `done` here and adds a PROGRESS.md row. See [docs/SWARM.md](docs/SWARM.md).

Columns: `Scope` lists comma-separated path prefixes the worker may change. `Reserved` is the one database migration version the task may add, so parallel tasks never collide. `Status` is `todo`, `done` or `blocked`. The integrator fills `PR`.

## Tasks

| ID | Task | Requirements | Depends | Scope | Reserved | Status | PR |
|---|---|---|---|---|---|---|---|
| CW-001 | Staff lifecycle and immutable membership history | IAM-02, IAM-01 | - | apps/api/src/main/java/com/aurevia/smartclinic/staff, apps/api/src/test/java/com/aurevia/smartclinic/staff, db/migrations | V4 | todo | |
| CW-002 | Tenant configuration, catalog and broader audit | ARC-02, OPS-02 | - | apps/api/src/main/java/com/aurevia/smartclinic/config, apps/api/src/test/java/com/aurevia/smartclinic/config | V5 | todo | |
| CW-003 | Concurrent-connection tenant isolation tests | ARC-02 | - | apps/api/src/test/java/com/aurevia/smartclinic/it | - | todo | |

### CW-001

Story S-G0-02 from TODO.md. Invite and deactivate staff with effective dates. Keep an immutable grantor history. A permission change must apply on the next protected request. Write failing tests first through the staff lifecycle entry point and keep JaCoCo at 100%.

### CW-002

Story S-G0-03 from TODO.md. Tenant-scoped configuration and catalog records with audit events, behind the existing tenant context and RLS policies. Tests first, 100% coverage.

### CW-003

Story S-G0-04 from TODO.md, test part only. Add concurrent-connection tests proving no tenant context leaks between pooled connections. Do not change production code unless a test exposes a defect, and then record it in the notes.
