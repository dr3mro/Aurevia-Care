"""Tests for the OPS-06 migration change-safety gate.

Fixtures are synthetic. The gate enforces expand/contract discipline: destructive
DDL must be a declared contract with a forward fix and a valid prior expand.
"""
import io
import re
import tempfile
import unittest
from contextlib import redirect_stderr
from pathlib import Path

from helpers import ROOT, load

check_migrations = load("check-migrations")


def write(root, name, text):
    """Write a synthetic migration into a scratch repository."""
    directory = root / "db" / "migrations"
    directory.mkdir(parents=True, exist_ok=True)
    (directory / name).write_text(text, encoding="utf-8")


class MigrationFixture(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.addCleanup(self.tmp.cleanup)

    def errors(self, require_baseline=False):
        return check_migrations.check(self.root, require_baseline=require_baseline)


class ParseTest(unittest.TestCase):
    def test_parse_version(self):
        self.assertEqual(check_migrations.parse_version("V12__add_column.sql"), 12)
        self.assertIsNone(check_migrations.parse_version("migration.sql"))
        self.assertIsNone(check_migrations.parse_version("V1__bad-name.sql"))

    def test_header_parsing(self):
        text = ("-- Change: contract\n-- Forward-fix: V9__repair.sql\n"
                "-- Requires-expand: V3\n\nSELECT 1;\n")
        self.assertEqual(check_migrations.header(text), {
            "Change": "contract",
            "Forward-fix": "V9__repair.sql",
            "Requires-expand": "V3",
        })
        self.assertEqual(check_migrations.header("SELECT 1;\n"), {})
        self.assertEqual(check_migrations.header("  -- Change: expand\n"),
                         {"Change": "expand"})
        self.assertEqual(check_migrations.header("-- just a note\n-- Change: expand\n"),
                         {"just a note": "", "Change": "expand"})


class DestructiveStatementTest(unittest.TestCase):
    def test_each_destructive_token_is_detected(self):
        statements = [
            "DROP TABLE public.foo;",
            "DROP VIEW legacy_view;",
            "DROP MATERIALIZED VIEW report;",
            "DROP INDEX idx;",
            "DROP TYPE mood;",
            "DROP SEQUENCE seq;",
            "DROP COLUMN legacy;",
            "DROP CONSTRAINT foo_pkey;",
            "DROP SCHEMA old;",
            "DROP FUNCTION f();",
            "DROP POLICY p;",
            "DROP TRIGGER t;",
            "DELETE FROM patient_record;",
            "TRUNCATE TABLE audit_event;",
            "TRUNCATE audit_event;",
            "ALTER TABLE facility DROP COLUMN code;",
        ]
        for statement in statements:
            with self.subTest(statement=statement):
                self.assertEqual(len(check_migrations.destructive_statements(statement)), 1)

    def test_comments_revokes_and_literals_are_ignored(self):
        text = "\n".join([
            "BEFORE TRUNCATE ON public.%I",
            "REVOKE UPDATE, DELETE, TRUNCATE ON public.t FROM app;",
            "IF TG_OP = 'TRUNCATE' THEN",
            "-- DROP TABLE old;",
            "/* DELETE FROM audit_event; */",
            "CREATE TABLE example (id integer);",
        ])
        self.assertEqual(check_migrations.destructive_statements(text), [])

    def test_dash_dash_inside_literal_does_not_hide_later_statement(self):
        text = ("INSERT INTO t(body) VALUES ('a -- b');\n"
                "DROP TABLE doomed;\n"
                "INSERT INTO t(body) VALUES ('c');\n")
        findings = check_migrations.destructive_statements(text)
        self.assertEqual([line for line, _ in findings], [2])

    def test_escaped_quote_inside_literal_is_ignored(self):
        text = "INSERT INTO t(body) VALUES (E'a \\' DROP TABLE hidden; b');\nSELECT 1;\n"
        self.assertEqual(check_migrations.destructive_statements(text), [])

    def test_quoted_identifier_is_ignored(self):
        text = 'SELECT "DROP TABLE fake" FROM t;\nSELECT 1;\n'
        self.assertEqual(check_migrations.destructive_statements(text), [])

    def test_dollar_quoted_body_is_ignored(self):
        text = ("CREATE FUNCTION cleanup() RETURNS void AS $$\n"
                "BEGIN\n  DELETE FROM example;\nEND\n$$ LANGUAGE plpgsql;\n")
        self.assertEqual(check_migrations.destructive_statements(text), [])

    def test_nested_block_comment_is_ignored(self):
        text = "/* outer /* inner DROP TABLE x; */ still */ SELECT 1;\n"
        self.assertEqual(check_migrations.destructive_statements(text), [])

    def test_same_line_and_split_statements_are_detected(self):
        cases = [
            "CREATE TABLE t(id int); DROP TABLE old;",
            "DROP\nTABLE old;",
            "DELETE\nFROM audit_event;",
            "ALTER TABLE t DROP\n COLUMN c;",
        ]
        for text in cases:
            with self.subTest(text=text):
                self.assertEqual(len(check_migrations.destructive_statements(text)), 1)


class SequenceTest(MigrationFixture):
    def test_repository_migrations_are_valid(self):
        self.assertEqual(check_migrations.check(ROOT), [])

    def test_no_migrations_directory_is_valid_without_baseline_requirement(self):
        self.assertEqual(self.errors(), [])

    def test_require_baseline_rejects_missing_migrations(self):
        self.assertTrue(any("baseline" in e for e in self.errors(require_baseline=True)))
        write(self.root, "V1__expand.sql", "CREATE TABLE t (id int);\n")
        self.assertEqual(self.errors(require_baseline=True), [])

    def test_invalid_filename_is_reported(self):
        write(self.root, "migration.sql", "SELECT 1;\n")
        self.assertTrue(any("filename" in e for e in self.errors(require_baseline=True)))

    def test_duplicate_version_is_reported(self):
        write(self.root, "V1__a.sql", "SELECT 1;\n")
        write(self.root, "V1__b.sql", "SELECT 2;\n")
        self.assertTrue(any("duplicate" in e for e in self.errors()))

    def test_duplicate_version_still_validates_each_file(self):
        write(self.root, "V1__a.sql", "DROP TABLE old;\n")
        write(self.root, "V1__b.sql", "SELECT 2;\n")
        errors = self.errors()
        self.assertTrue(any("duplicate" in e for e in errors))
        self.assertTrue(any("V1__a.sql" in e and "contract" in e for e in errors))

    def test_gapped_versions_are_allowed(self):
        # Parallel workers own reserved versions (V9..V57), so a branch or a
        # merge of several workers legitimately has gaps. Flyway applies by
        # version order; only duplicate versions are unsafe.
        write(self.root, "V1__a.sql", "SELECT 1;\n")
        write(self.root, "V3__b.sql", "SELECT 2;\n")
        write(self.root, "V18__c.sql", "SELECT 3;\n")
        self.assertEqual(self.errors(), [])

    def test_duplicate_version_is_an_error_even_with_gaps(self):
        write(self.root, "V1__a.sql", "SELECT 1;\n")
        write(self.root, "V18__a.sql", "SELECT 2;\n")
        write(self.root, "V18__b.sql", "SELECT 3;\n")
        self.assertTrue(any("duplicate" in e for e in self.errors()))

    def test_clean_expand_migration_passes(self):
        write(self.root, "V1__expand.sql", "-- Change: expand\nCREATE TABLE t (id int);\n")
        self.assertEqual(self.errors(), [])


class ContractTest(MigrationFixture):
    def destructive(self, name="V2__contract.sql", **headers):
        lines = [f"-- {key}: {value}" for key, value in headers.items()]
        text = "\n".join(lines) + "\nDELETE FROM old_table;\n"
        write(self.root, name, text)

    def test_destructive_without_contract_header_is_reported(self):
        write(self.root, "V1__bad.sql", "DROP TABLE public.foo;\n")
        self.assertTrue(any("Change: contract" in e for e in self.errors()))

    def test_invalid_change_value_is_reported(self):
        write(self.root, "V1__bad.sql", "-- Change: expnad\nSELECT 1;\n")
        self.assertTrue(any("'Change'" in e for e in self.errors()))

    def test_contract_without_forward_fix_is_reported(self):
        self.destructive(Change="contract", **{"Requires-expand": "V1"})
        write(self.root, "V1__expand.sql", "-- Change: expand\nCREATE TABLE t (id int);\n")
        self.assertTrue(any("Forward-fix" in e for e in self.errors()))

    def test_invalid_forward_fix_is_reported(self):
        self.destructive(Change="contract", **{"Forward-fix": "V9",
                                               "Requires-expand": "V1"})
        write(self.root, "V1__expand.sql", "-- Change: expand\nCREATE TABLE t (id int);\n")
        self.assertTrue(any("Forward-fix" in e for e in self.errors()))

    def test_forward_fix_without_contract_is_reported(self):
        write(self.root, "V1__bad.sql", "-- Forward-fix: V9__repair.sql\nSELECT 1;\n")
        self.assertTrue(any("Forward-fix" in e for e in self.errors()))

    def test_contract_without_requires_expand_is_reported(self):
        self.destructive(Change="contract", **{"Forward-fix": "V9__repair.sql"})
        self.assertTrue(any("Requires-expand" in e for e in self.errors()))

    def test_invalid_requires_expand_value_is_reported(self):
        self.destructive(Change="contract", **{"Forward-fix": "V9__repair.sql",
                                               "Requires-expand": "three"})
        write(self.root, "V1__expand.sql", "-- Change: expand\nCREATE TABLE t (id int);\n")
        self.assertTrue(any("invalid 'Requires-expand'" in e for e in self.errors()))

    def test_self_reference_is_reported(self):
        self.destructive(Change="contract", **{"Forward-fix": "V9__repair.sql",
                                               "Requires-expand": "V2"})
        self.assertTrue(any("earlier" in e for e in self.errors()))

    def test_future_reference_is_reported(self):
        write(self.root, "V2__later.sql", "CREATE TABLE later (id int);\n")
        self.destructive(name="V1__contract.sql", Change="contract",
                         **{"Forward-fix": "V9__repair.sql", "Requires-expand": "V2"})
        self.assertTrue(any("earlier" in e for e in self.errors()))

    def test_missing_reference_is_reported(self):
        self.destructive(Change="contract", **{"Forward-fix": "V9__repair.sql",
                                               "Requires-expand": "V7"})
        write(self.root, "V1__expand.sql", "-- Change: expand\nCREATE TABLE t (id int);\n")
        self.assertTrue(any("missing" in e for e in self.errors()))

    def test_reference_to_non_expand_is_reported(self):
        self.destructive(Change="contract", **{"Forward-fix": "V9__repair.sql",
                                               "Requires-expand": "V1"})
        write(self.root, "V1__plain.sql", "CREATE TABLE t (id int);\n")
        self.assertTrue(any("expand migration" in e for e in self.errors()))

    def test_valid_contract_passes(self):
        write(self.root, "V1__expand.sql", "-- Change: expand\nCREATE TABLE t (id int);\n")
        self.destructive(Change="contract", **{"Forward-fix": "V9__repair.sql",
                                               "Requires-expand": "V1"})
        self.assertEqual(self.errors(), [])

    def test_documented_contract_example_passes(self):
        write(self.root, "V1__add_replacement.sql", "-- Change: expand\n"
              "CREATE VIEW replacement AS SELECT 1;\n")
        write(self.root, "V2__contract.sql",
              "-- Change: contract\n-- Forward-fix: V9__restore_legacy_view.sql\n"
              "-- Requires-expand: V1\n\nDROP VIEW legacy_view;\n")
        self.assertEqual(self.errors(), [])


class NamingTest(unittest.TestCase):
    """Flyway descriptions name the business domain, never the originating task."""

    def test_checked_in_migration_descriptions_have_no_task_ids(self):
        task_id = re.compile(
            r"(?:^|_)(?:build|s_g\d+|x|dlv|cw|aud|fix|imp|story|task)[_-]?\d+(?:_|$)",
            re.IGNORECASE)
        descriptions = [
            path.name.split("__", 1)[1].removesuffix(".sql")
            for path in (ROOT / "db" / "migrations").glob("V*__*.sql")
        ]
        self.assertTrue(descriptions)
        self.assertEqual([name for name in descriptions if task_id.search(name)], [])


class EntryPointTest(MigrationFixture):
    def test_main_reports_errors_and_fails(self):
        write(self.root, "V1__bad.sql", "DROP TABLE public.foo;\n")
        stream = io.StringIO()
        with redirect_stderr(stream):
            code = check_migrations.main(self.root, argv=[])
        self.assertEqual(code, 1)
        self.assertIn("check-migrations:", stream.getvalue())

    def test_main_succeeds_for_clean_repository(self):
        stream = io.StringIO()
        with redirect_stderr(stream):
            code = check_migrations.main(self.root, argv=[])
        self.assertEqual(code, 0)
        self.assertEqual(stream.getvalue(), "")

    def test_main_honours_baseline_flag(self):
        stream = io.StringIO()
        with redirect_stderr(stream):
            code = check_migrations.main(self.root, argv=["--require-baseline"])
        self.assertEqual(code, 1)
        self.assertIn("baseline", stream.getvalue())


if __name__ == "__main__":
    unittest.main()
