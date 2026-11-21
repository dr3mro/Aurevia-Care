"""Offline security gate for the cloud Terraform under ``infra/cloud``.

The gate parses a directory of ``.tf`` files with the Python standard library
only; Terraform itself is not required. It fails closed on a public object
bucket, a missing encryption configuration, an unencrypted database or volume,
a backup-free database, or an IAM policy with a wildcard action, principal or
resource. The only accepted resource wildcard is the AWS-required scope of
``ecr:GetAuthorizationToken``, which by itself grants no repository access.

All fixtures used by the tests are synthetic. Usage::

    python3 -I terraform_policy_check.py <terraform-directory>

Exit codes: 0 = conforming, 1 = a policy breach, 2 = unreadable input or usage.
"""
import re
import sys
from collections import namedtuple
from pathlib import Path

PUBLIC_ACCESS_FLAGS = (
    "block_public_acls",
    "block_public_policy",
    "ignore_public_acls",
    "restrict_public_buckets",
)
SSE_ALGORITHMS = ("aws:kms", "AES256")
ECR_AUTH_ACTION = "ecr:GetAuthorizationToken"
# Only these canned ACLs keep the bucket private. Everything else, including
# authenticated-read and aws-exec-read, grants access to other AWS accounts.
SAFE_ACLS = ("private", "bucket-owner-read", "bucket-owner-full-control")

Block = namedtuple("Block", "kind labels body")
BLOCK = re.compile(r'(?m)^[ \t]*([a-z][a-z0-9_-]*)[ \t]+((?:"[^"]*"[ \t\n]*)+)\{')


def strip_comments(text):
    """Return ``text`` with HCL comments blanked, preserving string contents.

    A single left-to-right pass keeps a ``#`` or ``//`` inside a quoted string
    from eating the rest of the file, which a sequence of regular expressions
    cannot guarantee.
    """
    result = []
    index = 0
    in_string = False
    escaped = False
    while index < len(text):
        char = text[index]
        if in_string:
            result.append(char)
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                in_string = False
            index += 1
            continue
        if char == '"':
            in_string = True
            result.append(char)
            index += 1
        elif char == "#" or text.startswith("//", index):
            newline = text.find("\n", index)
            if newline == -1:
                break
            result.append("\n")
            index = newline + 1
        elif text.startswith("/*", index):
            end = text.find("*/", index + 2)
            if end == -1:
                break
            result.append("\n" * text.count("\n", index, end))
            index = end + 2
        else:
            result.append(char)
            index += 1
    return "".join(result)


def matching_delimiter(text, start, opening, closing):
    """Return the index of the delimiter matching the one at ``start``.

    Quoted strings are skipped so braces or brackets inside interpolation and
    JSON strings do not change the nesting depth. An unbalanced input raises
    ``ValueError`` rather than failing open with a truncated parse.
    """
    depth = 0
    in_string = False
    escaped = False
    for index in range(start, len(text)):
        char = text[index]
        if in_string:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                in_string = False
            continue
        if char == '"':
            in_string = True
        elif char == opening:
            depth += 1
        elif char == closing:
            depth -= 1
            if depth == 0:
                return index
    raise ValueError("unbalanced " + opening + closing)


def split_objects(array):
    """Return the bodies of the top-level objects inside an HCL array literal."""
    objects = []
    index = 0
    while index < len(array):
        if array[index] == "{":
            end = matching_delimiter(array, index, "{", "}")
            objects.append(array[index + 1:end])
            index = end + 1
        else:
            index += 1
    return objects


def blocks(text):
    """Return the labelled top-level blocks found in comment-free ``text``."""
    parsed = []
    for match in BLOCK.finditer(text):
        start = match.end() - 1
        end = matching_delimiter(text, start, "{", "}")
        labels = re.findall(r'"([^"]*)"', match.group(2))
        parsed.append(Block(match.group(1), labels, text[start + 1:end]))
    return parsed


def field(body, name):
    """Return the raw value assigned to ``name`` in a block or JSON object."""
    pattern = re.compile(
        r"(?<![A-Za-z0-9_])" + re.escape(name) +
        r'["\']?\s*[:=]\s*("[^"]*"|\[[^\]]*\]|\{[^{}]*\}|[A-Za-z0-9_.-]+)')
    match = pattern.search(body)
    return match.group(1) if match else None


def tokens(value):
    """Return the quoted string tokens inside a field value."""
    return re.findall(r'"([^"]*)"', value) if value else []


def iam_statements(body):
    """Return statements from every ``Statement`` array in a policy body.

    Returns an empty list when there is no literal array, so callers can fail
    closed on policies hidden behind ``file()`` or a variable instead of
    silently treating an unresolvable policy as safe.
    """
    statements = []
    for match in re.finditer(r'\bStatement\b["\']?\s*[:=]\s*\[', body):
        start = match.end() - 1
        end = matching_delimiter(body, start, "[", "]")
        statements.extend(split_objects(body[start + 1:end]))
    return statements


def variable_default(parsed, name):
    """Return the declared default of variable ``name``, or ``None``."""
    for block in parsed:
        if block.kind == "variable" and block.labels == [name]:
            return field(block.body, "default")
    return None


def is_public_principal(value):
    """Return whether a Principal field grants access to an unspecified caller."""
    for token in tokens(value):
        if token == "*" or "iam::*" in token:
            return True
    return False


def positive_retention(parsed, value):
    """Resolve a backup retention value (literal or variable) to a positive integer."""
    if value is None:
        return False
    text = value.strip()
    reference = re.fullmatch(r"var\.([A-Za-z0-9_-]+)", text)
    if reference is not None:
        text = (variable_default(parsed, reference.group(1)) or "").strip()
    return text.isdigit() and int(text) >= 1


def resource_blocks(parsed, type_name):
    """Return resource blocks of one Terraform resource type."""
    return [b for b in parsed
            if b.kind == "resource" and b.labels and b.labels[0] == type_name]


def references_bucket(body, name):
    """Return whether the ``bucket`` field targets exactly this bucket resource.

    Matching the whole reference rather than a substring stops a guard for
    ``docs-archive`` from also satisfying a bucket named ``docs``.
    """
    value = field(body, "bucket")
    return value is not None and \
        re.search(r"aws_s3_bucket\." + re.escape(name) + r"(?![A-Za-z0-9_-])",
                  value) is not None


def is_safe_acl(value):
    """Return whether every canned ACL value keeps the bucket private.

    An ACL that cannot be resolved statically is not treated as safe.
    """
    canned = tokens(value)
    if not canned:
        return False
    return all(token.lower() in SAFE_ACLS for token in canned)


def check_public_bucket(parsed):
    """Fail on a public ACL, a missing public-access guard or a public Allow policy."""
    failures = []
    guards = resource_blocks(parsed, "aws_s3_bucket_public_access_block")
    policies = resource_blocks(parsed, "aws_s3_bucket_policy")
    for bucket in resource_blocks(parsed, "aws_s3_bucket"):
        name = bucket.labels[1]
        acl = field(bucket.body, "acl")
        if acl is not None and not is_safe_acl(acl):
            failures.append("public_bucket.acl." + name)
        matching_guards = [g for g in guards if references_bucket(g.body, name)]
        if not matching_guards:
            failures.append("public_bucket.no_guard." + name)
        for guard in matching_guards:
            for flag in PUBLIC_ACCESS_FLAGS:
                value = field(guard.body, flag)
                if value is None or value.strip() != "true":
                    failures.append("public_bucket." + flag + "." + name)
    for policy in policies:
        statements = iam_statements(policy.body)
        if not statements:
            failures.append("public_bucket.unresolved_policy." + policy.labels[1])
        for statement in statements:
            effect = field(statement, "Effect")
            principal = field(statement, "Principal")
            if effect is not None and "Allow" in tokens(effect) \
                    and is_public_principal(principal):
                failures.append("public_bucket.allow_policy." + policy.labels[1])
    for acl_resource in resource_blocks(parsed, "aws_s3_bucket_acl"):
        acl = field(acl_resource.body, "acl")
        if acl is not None and not is_safe_acl(acl):
            failures.append("public_bucket.acl." + acl_resource.labels[1])
    return failures


def check_bucket_encryption(parsed):
    """Fail when a bucket has no server-side encryption with an approved algorithm."""
    failures = []
    configs = resource_blocks(
        parsed, "aws_s3_bucket_server_side_encryption_configuration")
    for bucket in resource_blocks(parsed, "aws_s3_bucket"):
        name = bucket.labels[1]
        algorithm = None
        for config in configs:
            if not references_bucket(config.body, name):
                continue
            algorithm = field(config.body, "sse_algorithm") or \
                field(config.body, "algorithm")
            if algorithm is not None:
                break
        if algorithm is None or not any(a in tokens(algorithm)
                                        for a in SSE_ALGORITHMS):
            failures.append("bucket_encryption." + name)
    return failures


def check_unencrypted_volume(parsed):
    """Fail on unencrypted database or block storage, or a backup-free database."""
    failures = []
    for kind in ("aws_db_instance", "aws_rds_cluster"):
        for volume in resource_blocks(parsed, kind):
            name = volume.labels[1]
            if field(volume.body, "storage_encrypted") != "true":
                failures.append("unencrypted_volume." + name)
            retention = field(volume.body, "backup_retention_period")
            if not positive_retention(parsed, retention):
                failures.append("backups." + name)
    for volume in resource_blocks(parsed, "aws_ebs_volume"):
        if field(volume.body, "encrypted") != "true":
            failures.append("unencrypted_volume." + volume.labels[1])
    return failures


def check_wildcard_role(parsed):
    """Fail on IAM wildcard actions, principals or resources.

    A ``Resource = "*"`` is accepted only in the statement whose sole action
    is ``ecr:GetAuthorizationToken``; AWS does not support resource scoping for
    that action and the token grants no repository access. A policy that cannot
    be statically resolved is a finding, never a pass.
    """
    failures = []
    for block in parsed:
        if block.kind != "resource" or not block.labels or \
                not block.labels[0].startswith("aws_iam_"):
            continue
        statements = iam_statements(block.body)
        if not statements:
            failures.append("role.unresolved_policy." + block.labels[1])
        for statement in statements:
            principal_field = field(statement, "Principal")
            actions = tokens(field(statement, "Action"))
            principals = tokens(principal_field)
            resources = tokens(field(statement, "Resource"))
            if any("*" in action for action in actions):
                failures.append("role.action_wildcard." + block.labels[1])
            if "*" in principals or is_public_principal(principal_field):
                failures.append("role.principal_wildcard." + block.labels[1])
            if "*" in resources and actions != [ECR_AUTH_ACTION]:
                failures.append("role.resource_wildcard." + block.labels[1])
    return failures


def evaluate(root):
    """Return a report for every Terraform file directly under ``root``."""
    root = Path(root)
    if not root.is_dir():
        raise FileNotFoundError("not a directory: " + str(root))
    sources = sorted(root.glob("*.tf"))
    # Terraform also loads *.tf.json. The gate cannot parse that syntax, so a
    # module containing one fails closed instead of being silently skipped.
    json_sources = sorted(root.glob("*.tf.json"))
    parsed = []
    for path in sources:
        parsed.extend(blocks(strip_comments(path.read_text(encoding="utf-8"))))
    file_findings = []
    if not sources:
        file_findings.append("terraform_files.missing")
    if json_sources:
        file_findings.append("terraform_files.unsupported_json")
    checks = {
        "terraform_files": file_findings,
        "public_bucket": check_public_bucket(parsed),
        "bucket_encryption": check_bucket_encryption(parsed),
        "unencrypted_volume": check_unencrypted_volume(parsed),
        "wildcard_role": check_wildcard_role(parsed),
    }
    failed = sorted({item for problems in checks.values() for item in problems})
    return {
        "requirement": "OPS-02/OPS-06",
        "checks": checks,
        "failed": failed,
        "verdict": "fail" if failed else "pass",
    }


def format_report(report):
    """Render a machine-readable report for a CI log."""
    lines = ["Cloud Terraform policy (" + report["requirement"] + "): " +
             report["verdict"].upper()]
    for name, problems in report["checks"].items():
        status = "FAIL" if problems else "PASS"
        detail = ", ".join(problems) if problems else "ok"
        lines.append("  [" + status + "] " + name + ": " + detail)
    return "\n".join(lines)


def main(argv=None):
    """CLI entry point. Returns 0 for conforming input, 1 for a breach, 2 for errors."""
    args = sys.argv[1:] if argv is None else argv
    if len(args) != 1:
        print("usage: terraform_policy_check.py <terraform-directory>",
              file=sys.stderr)
        return 2
    try:
        report = evaluate(args[0])
    except (OSError, UnicodeError, ValueError) as error:
        print("cannot read terraform files: " + str(error), file=sys.stderr)
        return 2
    print(format_report(report))
    return 1 if report["verdict"] == "fail" else 0


if __name__ == "__main__":
    sys.exit(main())  #pragma NO COVER - CLI entry point, exercised via main() in tests
