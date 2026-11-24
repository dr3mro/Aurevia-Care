- [x] RENAME-MIGRATION-FILENAMES · Rename seven task-ID-named Flyway migrations to business-domain filenames
  - agent: orca
  - requirements: OPS-06
  - pr: direct commit 28cfb5b4 on main (interactive, human-reviewed)
  - scope: db/migrations, docs/reference/data-dictionary.md, docs/spec/clauses, docs/swarm, scripts/tests/test_check_migrations.py
  - delivery: interactive, human-reviewed scoped commit on main; not a swarm auto-merge
  - attempts: 0
  - details:
    Owner decision 2026-11-23: rename V70__build_01.sql, V71__build_02.sql,
    V74__build_05.sql, V75__build_06.sql, V76__build_09.sql, V77__build_12.sql
    and V78__build_16.sql to business-domain names even though they may already
    be applied. Renaming a migration changes its Flyway description; every
    environment that applied them must run `flyway repair` once before the next
    validate. Record the operator step in db/migrations/README.md and in
    MEMORY.md with the owner decision and date. Update every in-repo reference
    to the old filenames, keep the SQL contents byte-identical, keep version
    numbers and the migration gate green, and add a regression test that the
    checked-in migration filenames do not contain task IDs.
  - evidence:
    Tool (red): `python3 -I -m unittest discover -s scripts/tests -p 'test_check_migrations.py' -v`
    before the rename → `FAILED (failures=1)`; new
    `NamingTest.test_checked_in_migration_descriptions_have_no_task_ids` listed
    `build_05, build_12, build_09, build_01, build_16, build_02, build_06`.
    Tool (green, after rename): same command → `Ran 36 tests ... OK`, exit 0.
    Tool: `git diff --cached --stat -- db/migrations` → seven renames,
    `0 insertions(+), 0 deletions(-)`: SQL bytes are identical and only Flyway
    descriptions changed. New names: V70 staff_invitation, V71 role_change_history,
    V74 follow_up_suggestions, V75 document_verified_content,
    V76 medication_catalog_version, V77 therapy_episode, V78 export_provenance.
    Tool: `python3 -I scripts/check-migrations.py --require-baseline` exit 0,
    no output; `python3 -I scripts/check-names.py` exit 0, no output;
    `python3 -I scripts/check-wiring.py` exit 0, no output.
    Tool: active references updated in `docs/reference/data-dictionary.md` and
    `docs/spec/clauses/**`; `git grep ... build_(01|...|16)` over active dirs
    returns nothing. Historical records (tasks/, docs/swarm/, .agents/notes/)
    left untouched by owner choice.
    Documented: `flyway repair` operator step in `db/migrations/README.md`; owner
    decision and date in `.opencode/config/MEMORY.md`.
    Tool (full suite): `python3 -I -m unittest discover -s scripts/tests -q` →
    `Ran 1552 tests in 434.489s`, `FAILED (failures=1)`; the sole failure is
    `test_check_docs.RepositoryTests.test_repository_docs_are_consistent`, the
    same concurrent doc-churn issue below. No migration, naming or swarm test
    failed.
    Blocker (environment, not this change): `python3 -I scripts/check-docs.py`
    exit 1 with 151 lines from concurrent sessions moving `docs/pr-notes/` into
    `.agents/reports/` and deleting `docs/archive/`; zero errors reference any
    file changed here and the same command on a clean `git archive HEAD` copy
    exits 0. A later run crashed in `scripts/check-docs.py` with
    `NameError: name 'MOVED_DOCS' is not defined` while another session edited
    that script; left untouched. Evidence sources: executed tool commands; no
    reviewer, simulated user or human participant evidence.
    Resolution: the concurrent doc-path migration finished and
    `python3 -I scripts/check-docs.py` now exits 0 with no output; the migration,
    naming and wiring gates are green. Delivery: committed on main in `28cfb5b4`
    and merged with origin in `4932b50c`; no push or tag was made by this session.
