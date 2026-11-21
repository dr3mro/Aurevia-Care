"""Executable acceptance tests for the offline cloud Terraform policy gate.

Every fixture is synthetic. A conforming module passes; each OPS-02 control
(public bucket, encrypted bucket, encrypted and backed-up database, least
privilege IAM) must fail closed when omitted, weakened or relabelled. Tests
enumerate both outcomes of every branch so the contract is specified without
creating any cloud resource or reading any real account.
"""
import contextlib
import importlib.util
import io
import re
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

MODULE_PATH = Path(__file__).resolve().parents[1] / "terraform_policy_check.py"
SPEC = importlib.util.spec_from_file_location("terraform_policy_check", MODULE_PATH)
tcp = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(tcp)

BUCKET = '''
resource "aws_s3_bucket" "docs" {
  bucket = "synthetic-documents"
}
'''
GUARD = '''
resource "aws_s3_bucket_public_access_block" "docs" {
  bucket                  = aws_s3_bucket.docs.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}
'''
SSE = '''
resource "aws_s3_bucket_server_side_encryption_configuration" "docs" {
  bucket = aws_s3_bucket.docs.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "aws:kms"
    }
  }
}
'''
DATABASE = '''
variable "retention" {
  default = 14
}
resource "aws_db_instance" "main" {
  storage_encrypted       = true
  backup_retention_period = var.retention
}
'''
ROLE = '''
resource "aws_iam_role" "task" {
  assume_role_policy = jsonencode({
    Statement = [{
      Effect    = "Allow"
      Action    = "sts:AssumeRole"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
    }]
  })
}
'''
ECR_ROLE = '''
resource "aws_iam_role_policy" "execution" {
  policy = jsonencode({
    Statement = [{
      Effect   = "Allow"
      Action   = ["ecr:GetAuthorizationToken"]
      Resource = ["*"]
    }]
  })
}
'''
CONFORMING = "\n".join((BUCKET, GUARD, SSE, DATABASE, ROLE))


@contextlib.contextmanager
def terraform_dir(**files):
    """Yield a temporary directory populated with synthetic ``.tf`` files."""
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        for name, body in files.items():
            (root / name).write_text(body, encoding="utf-8")
        yield root


def evaluate_text(text):
    """Evaluate a single synthetic Terraform source string."""
    with terraform_dir(**{"main.tf": text}) as root:
        return tcp.evaluate(root)


def names(report):
    """Return the set of failed check names in a report."""
    return set(report["failed"])


class CommentAndDelimiterTest(unittest.TestCase):
    def test_strip_comments_removes_hash_and_slash_comments(self):
        text = 'a = 1 # note\nb = 2 // note\nc = 3\n'
        self.assertEqual(tcp.strip_comments(text), 'a = 1 \nb = 2 \nc = 3\n')

    def test_strip_comments_preserves_line_count_in_block_comments(self):
        self.assertEqual(tcp.strip_comments('a = 1 /* one\ntwo */\nb = 2\n'),
                         'a = 1 \n\nb = 2\n')

    def test_strip_comments_keeps_markers_inside_strings(self):
        text = 'a = "# not a // comment /* still text"\n'
        self.assertEqual(tcp.strip_comments(text), text)

    def test_strip_comments_handles_escaped_quotes(self):
        text = r'a = "a\"#b" # real comment' + "\n"
        self.assertEqual(tcp.strip_comments(text), r'a = "a\"#b" ' + "\n")

    def test_strip_comments_drops_trailing_line_comment(self):
        self.assertEqual(tcp.strip_comments('a = 1 # tail'), 'a = 1 ')

    def test_strip_comments_drops_unterminated_block_comment(self):
        self.assertEqual(tcp.strip_comments('a = 1 /* never closed'), 'a = 1 ')

    def test_matching_delimiter_handles_nesting_and_strings(self):
        text = 'a { b = "[}]" { c = 1 } }'
        self.assertEqual(tcp.matching_delimiter(text, 2, "{", "}"),
                         text.rindex("}"))

    def test_matching_delimiter_skips_escaped_quotes(self):
        text = r'{ x = "a\"}" }'
        self.assertEqual(tcp.matching_delimiter(text, 0, "{", "}"),
                         text.rindex("}"))

    def test_matching_delimiter_supports_square_brackets(self):
        self.assertEqual(tcp.matching_delimiter("[[]]", 0, "[", "]"), 3)

    def test_matching_delimiter_rejects_unbalanced_input(self):
        with self.assertRaises(ValueError):
            tcp.matching_delimiter("{ unclosed", 0, "{", "}")

    def test_split_objects_returns_top_level_objects(self):
        self.assertEqual(tcp.split_objects('{ a = 1 }, { b = 2 }'),
                         [' a = 1 ', ' b = 2 '])

    def test_split_objects_ignores_braces_inside_strings(self):
        self.assertEqual(tcp.split_objects('{ a = "}" }, { b = 1 }'),
                         [' a = "}" ', ' b = 1 '])

    def test_split_objects_of_empty_array(self):
        self.assertEqual(tcp.split_objects(""), [])


class ParseTest(unittest.TestCase):
    def test_blocks_extracts_labelled_top_level_blocks(self):
        text = CONFORMING + '\nresource "aws_iam_role" "other" {\n  statement {\n  }\n}\n'
        parsed = tcp.blocks(tcp.strip_comments(text))
        kinds = [(b.kind, b.labels) for b in parsed]
        self.assertIn(("resource", ["aws_s3_bucket", "docs"]), kinds)
        self.assertIn(("variable", ["retention"]), kinds)
        self.assertIn(("resource", ["aws_iam_role", "other"]), kinds)

    def test_blocks_accepts_brace_on_the_next_line(self):
        parsed = tcp.blocks('resource "aws_s3_bucket" "late"\n{\n  acl = "private"\n}\n')
        self.assertEqual([(b.kind, b.labels) for b in parsed],
                         [("resource", ["aws_s3_bucket", "late"])])

    def test_blocks_propagates_unbalanced_block(self):
        with self.assertRaises(ValueError):
            tcp.blocks('resource "aws_s3_bucket" "x" { unclosed')

    def test_field_reads_hcl_and_json_forms(self):
        body = 'acl = "public-read"\n"Effect" : "Allow"\nn = [1, 2]\n' \
               'obj = { a = "b" }\nword = var.foo\n'
        self.assertEqual(tcp.field(body, "acl"), '"public-read"')
        self.assertEqual(tcp.field(body, "Effect"), '"Allow"')
        self.assertEqual(tcp.field(body, "n"), "[1, 2]")
        self.assertEqual(tcp.field(body, "obj"), '{ a = "b" }')
        self.assertEqual(tcp.field(body, "word"), "var.foo")

    def test_field_returns_none_for_missing_key(self):
        self.assertIsNone(tcp.field('a = 1', "missing"))

    def test_tokens_empty_and_quoted(self):
        self.assertEqual(tcp.tokens(None), [])
        self.assertEqual(tcp.tokens('["*", "s3:GetObject"]'),
                         ["*", "s3:GetObject"])

    def test_iam_statements_without_array_returns_empty(self):
        body = 'policy = file("admin.json")'
        self.assertEqual(tcp.iam_statements(body), [])

    def test_iam_statements_splits_array(self):
        body = 'Statement = [{ Effect = "Allow" }, { Effect = "Deny" }]'
        statements = tcp.iam_statements(body)
        self.assertEqual(len(statements), 2)
        self.assertIn("Allow", statements[0])
        self.assertIn("Deny", statements[1])

    def test_iam_statements_reads_every_array(self):
        body = ('assume_role_policy = jsonencode({ Statement = [{ Effect = "Allow" }] })\n'
                'inline_policy = jsonencode({ Statement = [{ Effect = "Deny" }] })')
        statements = tcp.iam_statements(body)
        self.assertEqual(len(statements), 2)
        self.assertIn("Allow", statements[0])
        self.assertIn("Deny", statements[1])

    def test_iam_statements_accepts_quoted_json_key(self):
        body = '"Statement": [{ "Effect": "Allow" }, { "Effect": "Deny" }]'
        statements = tcp.iam_statements(body)
        self.assertEqual(len(statements), 2)
        self.assertIn("Deny", statements[1])

    def test_iam_statements_rejects_unbalanced_array(self):
        with self.assertRaises(ValueError):
            tcp.iam_statements("Statement = [{ Effect = \"Allow\"")

    def test_variable_default_resolves_declared_variable(self):
        with terraform_dir(**{"main.tf": CONFORMING}) as root:
            parsed = []
            for path in root.glob("*.tf"):
                parsed.extend(tcp.blocks(tcp.strip_comments(
                    path.read_text(encoding="utf-8"))))
        self.assertEqual(tcp.variable_default(parsed, "retention"), "14")

    def test_variable_default_returns_none_for_unknown(self):
        self.assertIsNone(tcp.variable_default([], "absent"))

    def test_is_public_principal_detects_wildcards(self):
        self.assertTrue(tcp.is_public_principal('"*"'))
        self.assertTrue(tcp.is_public_principal(
            '{ AWS = "arn:aws:iam::*:root" }'))
        self.assertFalse(tcp.is_public_principal(
            '{ Service = "ecs-tasks.amazonaws.com" }'))
        self.assertFalse(tcp.is_public_principal(None))

    def test_positive_retention_literal_values(self):
        self.assertTrue(tcp.positive_retention([], " 7 "))
        self.assertFalse(tcp.positive_retention([], " 0 "))
        self.assertFalse(tcp.positive_retention([], "many"))
        self.assertFalse(tcp.positive_retention([], None))

    def test_positive_retention_resolves_variable_default(self):
        self.assertTrue(tcp.positive_retention(
            tcp.blocks(tcp.strip_comments(CONFORMING)), "var.retention"))

    def test_positive_retention_rejects_variable_without_default(self):
        parsed = tcp.blocks(tcp.strip_comments(
            'variable "retention" {\n  type = number\n}\n'))
        self.assertFalse(tcp.positive_retention(parsed, "var.retention"))

    def test_positive_retention_rejects_unknown_variable(self):
        self.assertFalse(tcp.positive_retention([], "var.missing"))


class PublicBucketTest(unittest.TestCase):
    def test_conforming_bucket_has_no_public_findings(self):
        self.assertEqual(tcp.check_public_bucket(tcp.blocks(CONFORMING)), [])

    def test_public_acl_is_rejected(self):
        report = evaluate_text(CONFORMING.replace(
            'bucket = "synthetic-documents"',
            'bucket = "synthetic-documents"\n  acl = "public-read"'))
        self.assertIn("public_bucket.acl.docs", names(report))

    def test_missing_public_access_guard_is_rejected(self):
        report = evaluate_text(CONFORMING.replace(GUARD, ""))
        self.assertIn("public_bucket.no_guard.docs", names(report))

    def test_false_and_missing_guard_flags_are_rejected(self):
        weakened = GUARD.replace("ignore_public_acls      = true",
                                 "ignore_public_acls      = false")
        weakened = weakened.replace("restrict_public_buckets = true", "")
        report = evaluate_text(CONFORMING.replace(GUARD, weakened))
        self.assertIn("public_bucket.ignore_public_acls.docs", names(report))
        self.assertIn("public_bucket.restrict_public_buckets.docs", names(report))

    def test_public_allow_policy_is_rejected(self):
        policy = '''
resource "aws_s3_bucket_policy" "docs" {
  policy = jsonencode({
    Statement = [{
      Effect    = "Allow"
      Principal = "*"
    }]
  })
}
'''
        report = evaluate_text(CONFORMING + policy)
        self.assertIn("public_bucket.allow_policy.docs", names(report))

    def test_wildcard_account_principal_allow_is_rejected(self):
        policy = '''
resource "aws_s3_bucket_policy" "docs" {
  policy = jsonencode({
    Statement = [{
      Effect    = "Allow"
      Principal = { AWS = "arn:aws:iam::*:root" }
    }]
  })
}
'''
        report = evaluate_text(CONFORMING + policy)
        self.assertIn("public_bucket.allow_policy.docs", names(report))

    def test_deny_guardrail_with_public_principal_is_allowed(self):
        policy = '''
resource "aws_s3_bucket_policy" "docs" {
  policy = jsonencode({
    Statement = [{
      Effect    = "Deny"
      Principal = "*"
    }]
  })
}
'''
        self.assertNotIn("public_bucket.allow_policy.docs",
                         names(evaluate_text(CONFORMING + policy)))

    def test_statement_without_effect_is_not_flagged(self):
        policy = '''
resource "aws_s3_bucket_policy" "docs" {
  policy = jsonencode({
    Statement = [{ Principal = "*" }]
  })
}
'''
        self.assertNotIn("public_bucket.allow_policy.docs",
                         names(evaluate_text(CONFORMING + policy)))

    def test_unresolved_bucket_policy_is_rejected(self):
        policy = '''
resource "aws_s3_bucket_policy" "docs" {
  policy = file("bucket-policy.json")
}
'''
        report = evaluate_text(CONFORMING + policy)
        self.assertIn("public_bucket.unresolved_policy.docs", names(report))

    def test_authenticated_read_acl_is_rejected(self):
        report = evaluate_text(CONFORMING.replace(
            'bucket = "synthetic-documents"',
            'bucket = "synthetic-documents"\n  acl = "authenticated-read"'))
        self.assertIn("public_bucket.acl.docs", names(report))

    def test_unresolvable_acl_is_rejected(self):
        report = evaluate_text(CONFORMING.replace(
            'bucket = "synthetic-documents"',
            'bucket = "synthetic-documents"\n  acl = var.acl'))
        self.assertIn("public_bucket.acl.docs", names(report))

    def test_private_canned_acl_is_allowed(self):
        report = evaluate_text(CONFORMING.replace(
            'bucket = "synthetic-documents"',
            'bucket = "synthetic-documents"\n  acl = "private"'))
        self.assertNotIn("public_bucket.acl.docs", names(report))

    def test_standalone_public_acl_resource_is_rejected(self):
        acl = '''
resource "aws_s3_bucket_acl" "docs" {
  bucket = aws_s3_bucket.docs.id
  acl    = "public-read-write"
}
'''
        self.assertIn("public_bucket.acl.docs",
                      names(evaluate_text(CONFORMING + acl)))

    def test_standalone_private_acl_resource_is_allowed(self):
        acl = '''
resource "aws_s3_bucket_acl" "docs" {
  bucket = aws_s3_bucket.docs.id
  acl    = "bucket-owner-full-control"
}
'''
        self.assertNotIn("public_bucket.acl.docs",
                         names(evaluate_text(CONFORMING + acl)))

    def test_guard_for_another_bucket_with_spoofing_tag_is_ignored(self):
        spoofed = GUARD.replace(
            "bucket                  = aws_s3_bucket.docs.id",
            'bucket = aws_s3_bucket.docs-archive.id\n'
            '  tags = { Note = "aws_s3_bucket.docs" }')
        report = evaluate_text(CONFORMING.replace(GUARD, spoofed))
        self.assertIn("public_bucket.no_guard.docs", names(report))


class BucketEncryptionTest(unittest.TestCase):
    def test_conforming_bucket_has_approved_encryption(self):
        self.assertEqual(tcp.check_bucket_encryption(tcp.blocks(CONFORMING)), [])

    def test_missing_configuration_is_rejected(self):
        report = evaluate_text(CONFORMING.replace(SSE, ""))
        self.assertIn("bucket_encryption.docs", names(report))

    def test_configuration_for_another_bucket_does_not_count(self):
        other = SSE.replace("aws_s3_bucket.docs.id", "aws_s3_bucket.other.id")
        report = evaluate_text(CONFORMING.replace(SSE, other))
        self.assertIn("bucket_encryption.docs", names(report))

    def test_configuration_without_algorithm_is_skipped(self):
        blank = SSE.replace('sse_algorithm = "aws:kms"', "")
        report = evaluate_text(CONFORMING.replace(SSE, blank + SSE))
        self.assertNotIn("bucket_encryption.docs", names(report))

    def test_unapproved_algorithm_is_rejected(self):
        weak = SSE.replace('"aws:kms"', '"something-else"')
        report = evaluate_text(CONFORMING.replace(SSE, weak))
        self.assertIn("bucket_encryption.docs", names(report))

    def test_algorithm_key_from_json_heredoc_is_accepted(self):
        plain = SSE.replace('sse_algorithm = "aws:kms"', 'algorithm = "AES256"')
        report = evaluate_text(CONFORMING.replace(SSE, plain))
        self.assertNotIn("bucket_encryption.docs", names(report))

    def test_encryption_for_a_prefix_colliding_bucket_is_ignored(self):
        other = SSE.replace("aws_s3_bucket.docs.id",
                            "aws_s3_bucket.docs-archive.id")
        report = evaluate_text(CONFORMING.replace(SSE, other))
        self.assertIn("bucket_encryption.docs", names(report))


class VolumeTest(unittest.TestCase):
    def test_conforming_database_is_encrypted_and_backed_up(self):
        self.assertEqual(tcp.check_unencrypted_volume(tcp.blocks(CONFORMING)), [])

    def test_unencrypted_database_is_rejected(self):
        report = evaluate_text(CONFORMING.replace("storage_encrypted       = true",
                                                  "storage_encrypted       = false"))
        self.assertIn("unencrypted_volume.main", names(report))

    def test_backup_free_database_is_rejected(self):
        report = evaluate_text(CONFORMING.replace(
            "backup_retention_period = var.retention",
            "backup_retention_period = 0"))
        self.assertIn("backups.main", names(report))

    def test_rds_cluster_is_checked_too(self):
        cluster = '''
resource "aws_rds_cluster" "analytics" {
  storage_encrypted       = false
  backup_retention_period = 0
}
'''
        self.assertEqual(
            {"unencrypted_volume.analytics", "backups.analytics"},
            names(evaluate_text(cluster)))

    def test_unencrypted_ebs_volume_is_rejected(self):
        volume = '''
resource "aws_ebs_volume" "scratch" {
  encrypted = false
}
'''
        self.assertIn("unencrypted_volume.scratch",
                      names(evaluate_text(volume)))

    def test_encrypted_ebs_volume_is_accepted(self):
        volume = '''
resource "aws_ebs_volume" "scratch" {
  encrypted = true
}
'''
        self.assertNotIn("unencrypted_volume.scratch",
                         names(evaluate_text(volume)))


class WildcardRoleTest(unittest.TestCase):
    def test_conforming_roles_have_no_wildcards(self):
        self.assertEqual(tcp.check_wildcard_role(tcp.blocks(CONFORMING)), [])

    def test_wildcard_action_is_rejected(self):
        role = ROLE.replace('"sts:AssumeRole"', '"*"')
        self.assertIn("role.action_wildcard.task", names(evaluate_text(role)))

    def test_wildcard_principal_is_rejected(self):
        role = ROLE.replace('{ Service = "ecs-tasks.amazonaws.com" }', '"*"')
        self.assertIn("role.principal_wildcard.task", names(evaluate_text(role)))

    def test_wildcard_resource_is_rejected(self):
        role = ECR_ROLE.replace('"ecr:GetAuthorizationToken"', '"s3:GetObject"')
        self.assertIn("role.resource_wildcard.execution",
                      names(evaluate_text(role)))

    def test_ecr_authorization_wildcard_is_allowed(self):
        self.assertNotIn("role.resource_wildcard.execution",
                         names(evaluate_text(ECR_ROLE)))

    def test_wildcard_resource_with_multiple_actions_is_rejected(self):
        role = ECR_ROLE.replace('["ecr:GetAuthorizationToken"]',
                                '["ecr:GetAuthorizationToken", "s3:GetObject"]')
        self.assertIn("role.resource_wildcard.execution",
                      names(evaluate_text(role)))

    def test_non_iam_resources_are_not_scanned_as_roles(self):
        policy = '''
resource "aws_s3_bucket_policy" "docs" {
  policy = jsonencode({
    Statement = [{
      Effect   = "Deny"
      Action   = "*"
      Resource = "*"
    }]
  })
}
'''
        self.assertEqual([], tcp.check_wildcard_role(tcp.blocks(policy)))

    def test_partial_action_wildcard_is_rejected(self):
        role = ROLE.replace('"sts:AssumeRole"', '"s3:*"')
        self.assertIn("role.action_wildcard.task", names(evaluate_text(role)))

    def test_wildcard_account_principal_in_trust_policy_is_rejected(self):
        role = ROLE.replace('{ Service = "ecs-tasks.amazonaws.com" }',
                            '{ AWS = "arn:aws:iam::*:root" }')
        self.assertIn("role.principal_wildcard.task", names(evaluate_text(role)))

    def test_unresolved_iam_policy_is_rejected(self):
        policy = '''
resource "aws_iam_role_policy" "external" {
  policy = file("admin.json")
}
'''
        self.assertIn("role.unresolved_policy.external",
                      names(evaluate_text(policy)))

    def test_second_statement_array_is_inspected(self):
        role = '''
resource "aws_iam_role" "two" {
  assume_role_policy = jsonencode({
    Statement = [{
      Effect    = "Allow"
      Action    = "sts:AssumeRole"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
    }]
  })
  inline_policy = jsonencode({
    Statement = [{
      Effect   = "Allow"
      Action   = "s3:PutObject"
      Resource = "arn:aws:s3:::synthetic/*"
    }, {
      Effect   = "Allow"
      Action   = "s3:*"
      Resource = "arn:aws:s3:::synthetic/*"
    }]
  })
}
'''
        self.assertIn("role.action_wildcard.two", names(evaluate_text(role)))

    def test_quoted_json_statement_key_is_inspected(self):
        role = '''
resource "aws_iam_role_policy" "quoted" {
  policy = jsonencode({
    "Statement": [{ "Effect": "Allow", "Action": "ec2:*", "Resource": "*" }]
  })
}
'''
        report = evaluate_text(role)
        self.assertIn("role.action_wildcard.quoted", names(report))
        self.assertIn("role.resource_wildcard.quoted", names(report))


class EvaluateAndCliTest(unittest.TestCase):
    def test_conforming_directory_passes(self):
        report = evaluate_text(CONFORMING)
        self.assertEqual(report["verdict"], "pass")
        self.assertEqual(report["failed"], [])
        self.assertEqual(report["requirement"], "OPS-02/OPS-06")

    def test_missing_directory_raises(self):
        with self.assertRaises(FileNotFoundError):
            tcp.evaluate("/definitely/not/a/terraform/directory")

    def test_empty_directory_fails(self):
        with terraform_dir() as root:
            report = tcp.evaluate(root)
        self.assertIn("terraform_files.missing", names(report))

    def test_tf_json_file_fails_closed(self):
        with terraform_dir(**{
                "main.tf": CONFORMING,
                "public.tf.json": '{"resource": {"aws_s3_bucket": '
                                  '{"public": {"acl": "public-read"}}}}',
        }) as root:
            report = tcp.evaluate(root)
        self.assertEqual(report["verdict"], "fail")
        self.assertIn("terraform_files.unsupported_json", names(report))

    def test_all_breaches_are_reported_together(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "violating.tf"
            source.write_text('''
resource "aws_s3_bucket" "public" {
  acl = "public-read"
}
resource "aws_db_instance" "plain" {
  storage_encrypted       = false
  backup_retention_period = 0
}
resource "aws_iam_role_policy" "wild" {
  policy = jsonencode({
    Statement = [{ Effect = "Allow", Action = "*", Resource = "*" }]
  })
}
''', encoding="utf-8")
            report = tcp.evaluate(directory)
        self.assertEqual(report["verdict"], "fail")
        self.assertTrue({"public_bucket.acl.public",
                         "unencrypted_volume.plain",
                         "backups.plain",
                         "role.action_wildcard.wild",
                         "role.resource_wildcard.wild"}.issubset(names(report)))

    def test_format_report_shows_each_check_and_verdict(self):
        passing = tcp.format_report(evaluate_text(CONFORMING))
        self.assertIn("Cloud Terraform policy (OPS-02/OPS-06): PASS", passing)
        self.assertIn("[PASS] public_bucket: ok", passing)
        failing = tcp.format_report(evaluate_text(CONFORMING.replace(
            "storage_encrypted       = true", "storage_encrypted       = false")))
        self.assertIn("[FAIL] unencrypted_volume: unencrypted_volume.main",
                      failing)
        self.assertIn(": FAIL", failing)

    def test_main_evaluates_a_conforming_directory(self):
        with terraform_dir(**{"main.tf": CONFORMING}) as root:
            stdout = io.StringIO()
            with contextlib.redirect_stdout(stdout):
                code = tcp.main([str(root)])
        self.assertEqual(code, 0)
        self.assertIn("PASS", stdout.getvalue())

    def test_main_returns_one_for_a_failing_directory(self):
        with terraform_dir(**{"main.tf": CONFORMING.replace(
                "storage_encrypted       = true",
                "storage_encrypted       = false")}) as root:
            with contextlib.redirect_stdout(io.StringIO()):
                code = tcp.main([str(root)])
        self.assertEqual(code, 1)

    def test_main_reads_sys_argv_when_no_arguments_are_passed(self):
        with terraform_dir(**{"main.tf": CONFORMING}) as root:
            stdout = io.StringIO()
            with mock.patch.object(sys, "argv",
                                   ["terraform_policy_check.py", str(root)]):
                with contextlib.redirect_stdout(stdout):
                    code = tcp.main()
        self.assertEqual(code, 0)

    def test_main_rejects_bad_usage(self):
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            code = tcp.main([])
        self.assertEqual(code, 2)
        self.assertIn("usage", stderr.getvalue())

    def test_main_rejects_a_missing_directory(self):
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            code = tcp.main(["/definitely/not/here"])
        self.assertEqual(code, 2)
        self.assertIn("cannot read", stderr.getvalue())

    def test_main_rejects_unbalanced_hcl(self):
        with terraform_dir(**{"main.tf":
                              'resource "aws_s3_bucket" "x" { unclosed'}) as root:
            stderr = io.StringIO()
            with contextlib.redirect_stderr(stderr):
                code = tcp.main([str(root)])
        self.assertEqual(code, 2)
        self.assertIn("unbalanced", stderr.getvalue())

    def test_main_rejects_non_utf8_file(self):
        with tempfile.TemporaryDirectory() as directory:
            (Path(directory) / "main.tf").write_bytes(b'\xff\xfe')
            stderr = io.StringIO()
            with contextlib.redirect_stderr(stderr):
                code = tcp.main([directory])
        self.assertEqual(code, 2)
        self.assertIn("cannot read", stderr.getvalue())


class RepositoryEvidenceTest(unittest.TestCase):
    """The committed Terraform and the negative fixture are the live contract."""

    @classmethod
    def setUpClass(cls):
        cls.module_dir = MODULE_PATH.parent

    def test_committed_terraform_is_conforming(self):
        report = tcp.evaluate(self.module_dir)
        self.assertEqual(report["verdict"], "pass", report["failed"])

    def test_violation_fixture_fails_all_three_controls(self):
        report = tcp.evaluate(self.module_dir / "fixtures")
        self.assertEqual(report["verdict"], "fail")
        self.assertTrue({"public_bucket.acl.public_fixture",
                         "unencrypted_volume.unencrypted_fixture",
                         "backups.unencrypted_fixture",
                         "role.action_wildcard.wildcard_fixture",
                         "role.resource_wildcard.wildcard_fixture"
                         }.issubset(names(report)))

    def test_files_contain_no_phi_or_credential_shapes(self):
        for path in sorted(self.module_dir.rglob("*.tf")):
            with self.subTest(path=path.name):
                text = path.read_text(encoding="utf-8")
                self.assertIsNone(re.search(r"[\w.+-]+@[\w-]+\.[\w.]+", text))
                self.assertIsNone(re.search(r"\b\d{3}-\d{2}-\d{4}\b", text))
                self.assertNotIn("-----BEGIN", text)


if __name__ == "__main__":
    unittest.main()
