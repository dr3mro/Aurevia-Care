- [ ] RENAME-MIGRATION-FILENAMES · Rename seven task-ID-named Flyway migrations to business-domain filenames
  - requirements: OPS-06
  - scope: db/migrations, db/migrations/README.md, scripts/tests/test_check_migrations.py
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
