"""Fail-closed evaluator for an OPS-08 onboarding dataset manifest.

The specification requires approved onboarding datasets to be imported through a
versioned manifest and a deterministic mapping; a dry run that reports counts,
unsupported fields, duplicate risk, missing attachments and errors; preservation
of import provenance and historical signature evidence without creating a new
attestation; controlled batches with reconciled counts and hashes; and authorized
sign-off before the source of truth changes.

This module turns those clauses into a deterministic, synthetic check. It judges
a manifest artifact, not a running importer: it proves the declared contract is
complete and internally consistent and that a clean dry run and sign-off exist.
It cannot prove an import actually happened or that a person really approved it;
that remains a human, G5-gated step.

Usage: ``python3 -I infra/s_g5_09/onboarding.py <manifest.json>``
Exit codes: 0 = all OPS-08 checks pass, 1 = a check fails, 2 = unreadable input.
"""
import hashlib
import json
import re
import sys
from pathlib import Path

SCHEMA_VERSION = "1.0"
REQUIREMENT = "OPS-08"
MAX_BATCH_SIZE = 100
DETERMINISTIC_STRATEGY = "canonical_sha256"
REQUIRED_TARGET_FIELDS = ("mrn", "mrnIssuer", "givenName", "familyName", "birthDate")
SIGNOFF_ROLES = ("tenant_admin", "privacy_auditor", "clinical_safety_lead")
ATTACHMENT_TYPES = ("pdf", "jpeg", "png")
SHA256 = re.compile(r"^[0-9a-f]{64}$")

MAPPING_CHECKS = (
    "mapping", "mapping.deterministic", "mapping.strategy",
    "mapping.required_fields", "mapping.identity_fields", "mapping.ignored_fields",
)
PROVENANCE_CHECKS = (
    "provenance", "provenance.source_system", "provenance.source_version",
    "provenance.preserve_historical_signatures",
    "provenance.creates_new_attestation", "provenance.historical_signature_evidence",
)
DRY_RUN_CHECKS = (
    "dry_run.counts", "dry_run.unsupported_fields", "dry_run.duplicate_risk",
    "dry_run.missing_attachments", "dry_run.errors",
)
BATCH_CHECKS = (
    "batches", "batches.sequence", "batches.order", "batches.coverage",
    "batches.size", "batches.sha256",
)
SIGNOFF_CHECKS = (
    "sign_off", "sign_off.approver", "sign_off.role", "sign_off.decision",
    "sign_off.content", "sign_off.scope", "sign_off.approved_at",
)


def _is_sha256(value):
    """Return true for a lowercase 64-hex-digit SHA-256 digest."""
    return isinstance(value, str) and bool(SHA256.fullmatch(value))


def _nonempty_string(value):
    """Return true for a non-blank string."""
    return isinstance(value, str) and bool(value.strip())


def _canonical(value):
    """Encode a JSON value deterministically for hashing."""
    return json.dumps(value, sort_keys=True, separators=(",", ":"),
                      ensure_ascii=False).encode("utf-8")


def _digest(data):
    """Return the hex SHA-256 digest of bytes."""
    return hashlib.sha256(data).hexdigest()


def _content_digest(manifest_version, batches):
    """Digest the version and ordered batch hashes the sign-off must bind to.

    Batch hashes are recomputed from the dataset by ``_check_batches``. Binding
    the sign-off to their concatenation plus the manifest version means a change
    to any record, batch order or version invalidates the approval, even if the
    batch digests themselves were recomputed by an importer.
    """
    digests = [batch.get("sha256") for batch in batches if isinstance(batch, dict)]
    return _digest(_canonical({"manifest_version": manifest_version,
                               "batch_sha256": digests}))


def _check(checks, name, passed, detail):
    """Append one named check result to the report."""
    checks.append({"name": name, "passed": bool(passed), "detail": detail})


def _unavailable(checks, names, reason):
    """Mark every named check as failed because its input section is unusable."""
    for name in names:
        _check(checks, name, False, reason)


def _section(manifest, key):
    """Return a dictionary section of the manifest, or None."""
    if isinstance(manifest, dict):
        value = manifest.get(key)
        if isinstance(value, dict):
            return value
    return None


def _record_shape_ok(record):
    """Return true when a record has the minimum shape a dry run can inspect."""
    return (isinstance(record, dict) and _nonempty_string(record.get("source_id"))
            and isinstance(record.get("fields"), dict)
            and isinstance(record.get("attachments"), list))


def _check_envelope(manifest, checks):
    """Check the manifest identity, version and synthetic-only declaration."""
    if not isinstance(manifest, dict):
        _check(checks, "manifest", False, "manifest must be a JSON object")
        return
    _check(checks, "manifest", True, "manifest is a JSON object")
    _check(checks, "schema_version", manifest.get("schema_version") == SCHEMA_VERSION,
           "expected " + SCHEMA_VERSION)
    _check(checks, "requirement", manifest.get("requirement") == REQUIREMENT,
           "expected " + REQUIREMENT)
    version = manifest.get("manifest_version")
    _check(checks, "manifest_version",
           isinstance(version, int) and not isinstance(version, bool) and version > 0,
           "must be a positive integer")
    _check(checks, "source", _nonempty_string(manifest.get("source")),
           "source must be a non-blank string")
    _check(checks, "synthetic_only", manifest.get("synthetic_only") is True,
           "onboarding fixtures must be synthetic only")


def _check_dataset(manifest, checks):
    """Return the dataset's record objects and check the dataset section."""
    dataset = _section(manifest, "dataset")
    if dataset is None:
        _check(checks, "dataset", False, "dataset must be an object")
        _check(checks, "dataset.records", False, "records unavailable")
        return []
    _check(checks, "dataset", True, "dataset is an object")
    records = dataset.get("records")
    valid = (isinstance(records, list) and bool(records)
             and all(_record_shape_ok(record) for record in records))
    _check(checks, "dataset.records", valid,
           "records must be a non-empty list of well-formed records")
    if isinstance(records, list):
        return [record for record in records if isinstance(record, dict)]
    return []


def _check_mapping(manifest, checks):
    """Return a usable mapping or None, recording every mapping check."""
    mapping = _section(manifest, "mapping")
    if mapping is None:
        _unavailable(checks, MAPPING_CHECKS, "mapping must be an object")
        return None
    _check(checks, "mapping", True, "mapping is an object")
    deterministic = mapping.get("deterministic") is True
    _check(checks, "mapping.deterministic", deterministic,
           "mapping must declare deterministic=true")
    _check(checks, "mapping.strategy",
           mapping.get("strategy") == DETERMINISTIC_STRATEGY,
           "strategy must be " + DETERMINISTIC_STRATEGY)
    targets = mapping.get("source_to_target")
    targets_ok = isinstance(targets, dict)
    values = list(targets.values()) if targets_ok else []
    targets_ok = targets_ok and all(isinstance(value, str) for value in values)
    unique_targets = targets_ok and len(set(values)) == len(values)
    targets_ok = targets_ok and unique_targets
    _check(checks, "mapping.required_fields",
           targets_ok and all(field in values for field in REQUIRED_TARGET_FIELDS),
           "required target fields must be mapped exactly once by string names")
    identity = mapping.get("identity_fields")
    identity_ok = (isinstance(identity, list) and bool(identity)
                   and all(isinstance(field, str) and targets_ok
                           and field in values for field in identity)
                   # IAM-03 makes MRN uniqueness issuer scoped: duplicate risk
                   # must always be evaluated on the issuer-scoped MRN.
                   and {"mrn", "mrnIssuer"}.issubset(identity))
    _check(checks, "mapping.identity_fields", identity_ok,
           "identity fields must include the issuer-scoped MRN")
    ignored = mapping.get("ignored_fields")
    _check(checks, "mapping.ignored_fields", isinstance(ignored, list),
           "ignored_fields must be a list")
    if deterministic and targets_ok and identity_ok:
        return mapping
    return None


def _signature_ok(signature):
    """Return true for preserved historical signature evidence."""
    return (isinstance(signature, dict)
            and _nonempty_string(signature.get("issuer"))
            and _nonempty_string(signature.get("signed_at"))
            and _is_sha256(signature.get("checksum")))


def _check_provenance(manifest, records, checks):
    """Check that provenance is preserved and no new attestation is created."""
    provenance = _section(manifest, "provenance")
    if provenance is None:
        _unavailable(checks, PROVENANCE_CHECKS, "provenance must be an object")
        return
    _check(checks, "provenance", True, "provenance is an object")
    _check(checks, "provenance.source_system",
           _nonempty_string(provenance.get("source_system")),
           "source_system must be a non-blank string")
    _check(checks, "provenance.source_version",
           _nonempty_string(provenance.get("source_version")),
           "source_version must be a non-blank string")
    _check(checks, "provenance.preserve_historical_signatures",
           provenance.get("preserve_historical_signatures") is True,
           "historical signatures must be preserved")
    _check(checks, "provenance.creates_new_attestation",
           provenance.get("creates_new_attestation") is False,
           "import must not create a new attestation")
    evidence_ok = bool(records) and all(
        _signature_ok(record.get("historical_signature")) for record in records)
    _check(checks, "provenance.historical_signature_evidence", evidence_ok,
           "every record must carry its original signature evidence")


def _dry_run(records, mapping, batch_count, checks):
    """Compute and check the OPS-08 dry-run findings for the dataset."""
    dry = {"counts": {}, "unsupported_fields": [], "duplicate_risk": [],
           "missing_attachments": [], "errors": []}
    if mapping is None:
        _unavailable(checks, DRY_RUN_CHECKS, "dry run needs a valid mapping")
        return dry

    targets = mapping["source_to_target"]
    identity = mapping["identity_fields"]
    ignored = mapping.get("ignored_fields")
    if not isinstance(ignored, list):
        ignored = []
    reverse = {target: source for source, target in targets.items()}
    identities = {}

    for record in records:
        source_id = record.get("source_id")
        label = source_id if _nonempty_string(source_id) else "<invalid>"
        if not _nonempty_string(source_id):
            dry["errors"].append(label + ": invalid source_id")
        fields = record.get("fields")
        if not isinstance(fields, dict):
            dry["errors"].append(label + ": fields must be an object")
            fields = {}
        attachments = record.get("attachments")
        if not isinstance(attachments, list):
            dry["errors"].append(label + ": attachments must be a list")
            attachments = []

        for field in fields:
            if field not in targets and field not in ignored:
                dry["unsupported_fields"].append(str(field))

        # A mapping declaration is not enough: every record must actually carry
        # each required value. Otherwise a blank MRN maps to nothing, produces
        # no unsupported field and can quietly pass the dry run.
        for target in REQUIRED_TARGET_FIELDS:
            if not _nonempty_string(fields.get(reverse[target])):
                dry["errors"].append(label + ": missing required field " + target)

        for attachment in attachments:
            if not isinstance(attachment, dict):
                dry["errors"].append(label + ": invalid attachment")
                continue
            valid = (_nonempty_string(attachment.get("key"))
                     and _is_sha256(attachment.get("sha256"))
                     and attachment.get("type") in ATTACHMENT_TYPES
                     and isinstance(attachment.get("present"), bool))
            if not valid:
                dry["errors"].append(
                    label + ": invalid attachment " + str(attachment.get("key")))
            if attachment.get("present") is False:
                dry["missing_attachments"].append(
                    {"record": label, "key": attachment.get("key")})

        values = [fields.get(reverse[field]) for field in identity
                  if field in reverse]
        key = _digest(_canonical(values))
        identities.setdefault(key, []).append(label)

    for key, labels in identities.items():
        if len(labels) > 1:
            dry["duplicate_risk"].extend(labels)

    dry["unsupported_fields"] = sorted(set(dry["unsupported_fields"]))
    dry["duplicate_risk"] = sorted(dry["duplicate_risk"])
    dry["counts"] = {
        "total_records": len(records),
        "batch_count": batch_count,
        "unsupported_fields": len(dry["unsupported_fields"]),
        "duplicate_risk": len(dry["duplicate_risk"]),
        "missing_attachments": len(dry["missing_attachments"]),
        "errors": len(dry["errors"]),
    }
    _check(checks, "dry_run.counts", len(records) > 0,
           "total_records=" + str(len(records)))
    _check(checks, "dry_run.unsupported_fields", not dry["unsupported_fields"],
           "unsupported fields: " + ", ".join(dry["unsupported_fields"]))
    _check(checks, "dry_run.duplicate_risk", not dry["duplicate_risk"],
           "duplicate identities: " + ", ".join(dry["duplicate_risk"]))
    _check(checks, "dry_run.missing_attachments", not dry["missing_attachments"],
           "missing attachments: " + str(len(dry["missing_attachments"])))
    _check(checks, "dry_run.errors", not dry["errors"],
           "record errors: " + str(len(dry["errors"])))
    return dry


def _batch_digest(records):
    """Return one deterministic digest over the canonical bytes of a batch."""
    return _digest(b"".join(_canonical(record) for record in records))


def _check_batches(manifest, records, checks):
    """Check controlled batches and return the commit plan and sequences."""
    raw = manifest.get("batches") if isinstance(manifest, dict) else None
    if not isinstance(raw, list) or not raw:
        _unavailable(checks, BATCH_CHECKS, "batches must be a non-empty list")
        return [], []
    _check(checks, "batches", True, "batches is a non-empty list")
    if not all(isinstance(batch, dict) and isinstance(batch.get("record_ids"), list)
               for batch in raw):
        _unavailable(checks, BATCH_CHECKS[1:], "every batch needs a record_ids list")
        return [], []

    sequences = [batch.get("sequence") for batch in raw]
    sequence_ok = (all(isinstance(seq, int) and not isinstance(seq, bool)
                       for seq in sequences)
                   and sequences == list(range(1, len(raw) + 1)))
    _check(checks, "batches.sequence", sequence_ok,
           "sequences must be contiguous integers from 1")

    order_ok = True
    for batch in raw:
        ids = batch["record_ids"]
        if not ids or not all(_nonempty_string(identifier) for identifier in ids):
            order_ok = False
        elif ids != sorted(ids) or len(set(ids)) != len(ids):
            order_ok = False
    _check(checks, "batches.order", order_ok,
           "record_ids must be sorted, unique and non-empty")

    by_id = {record.get("source_id"): record for record in records
             if isinstance(record, dict) and _nonempty_string(record.get("source_id"))}
    all_ids = [identifier for batch in raw for identifier in batch["record_ids"]]
    expected_ids = sorted(by_id)
    coverage_ok = (all_ids == expected_ids and len(all_ids) == len(set(all_ids))
                   and len(all_ids) == len(records))
    _check(checks, "batches.coverage", coverage_ok,
           "batches must cover every record exactly once")

    size_ok = all(0 < len(batch["record_ids"]) <= MAX_BATCH_SIZE
                  and batch.get("expected_count") == len(batch["record_ids"])
                  for batch in raw)
    _check(checks, "batches.size", size_ok,
           "each batch count must match and stay within " + str(MAX_BATCH_SIZE))

    plan = [{"sequence": batch.get("sequence"),
             "count": len(batch["record_ids"]),
             "sha256": batch.get("sha256")} for batch in raw]
    if order_ok:
        hash_ok = True
        for batch in raw:
            identifiers = batch["record_ids"]
            chunk = [by_id[identifier] for identifier in identifiers
                     if identifier in by_id]
            if (len(chunk) != len(identifiers)
                    or batch.get("sha256") != _batch_digest(chunk)):
                hash_ok = False
    else:
        hash_ok = False
    _check(checks, "batches.sha256", hash_ok,
           "declared hashes must equal recomputed batch digests")
    return plan, sequences


def _check_sign_off(manifest, sequences, expected_content, checks):
    """Check that an authorized sign-off covers this exact manifest content."""
    sign_off = _section(manifest, "sign_off")
    if sign_off is None:
        _unavailable(checks, SIGNOFF_CHECKS, "sign_off must be an object")
        return
    _check(checks, "sign_off", True, "sign_off is an object")
    _check(checks, "sign_off.approver", _nonempty_string(sign_off.get("approver")),
           "approver must be a non-blank string")
    _check(checks, "sign_off.role", sign_off.get("role") in SIGNOFF_ROLES,
           "role must be an authorized approver role")
    _check(checks, "sign_off.decision", sign_off.get("decision") == "approved",
           "decision must be approved")
    declared_batches = sign_off.get("batches")
    declared_version = sign_off.get("manifest_version")
    version_ok = (isinstance(declared_version, int)
                  and not isinstance(declared_version, bool)
                  and declared_version == manifest.get("manifest_version"))
    sequences_ok = (all(isinstance(seq, int) and not isinstance(seq, bool)
                        for seq in sequences))
    scope_ok = (
        version_ok
        and isinstance(declared_batches, list)
        and all(isinstance(seq, int) and not isinstance(seq, bool)
                for seq in declared_batches)
        and sequences_ok
        and sorted(declared_batches) == sorted(sequences))
    _check(checks, "sign_off.scope", scope_ok,
           "sign-off must cover this manifest version and every batch")
    _check(checks, "sign_off.content",
           sign_off.get("content_sha256") == expected_content,
           "sign-off must bind to the reconciled batch content and version")
    _check(checks, "sign_off.approved_at", _nonempty_string(sign_off.get("approved_at")),
           "approved_at must be a non-blank timestamp")


def evaluate(manifest):
    """Return a fail-closed OPS-08 verdict for one onboarding manifest.

    Content problems are recorded as failed checks rather than raised, so a
    reviewer sees every breach in one report. ``commit_allowed`` is true only
    when every check passes, including an authorized sign-off.
    """
    checks = []
    _check_envelope(manifest, checks)
    records = _check_dataset(manifest, checks)
    mapping = _check_mapping(manifest, checks)
    _check_provenance(manifest, records, checks)
    raw_batches = manifest.get("batches") if isinstance(manifest, dict) else None
    batch_count = len(raw_batches) if isinstance(raw_batches, list) else 0
    dry_run = _dry_run(records, mapping, batch_count, checks)
    batch_plan, sequences = _check_batches(manifest, records, checks)
    version = manifest.get("manifest_version") if isinstance(manifest, dict) else None
    expected_content = _content_digest(version, raw_batches or [])
    _check_sign_off(manifest, sequences, expected_content, checks)

    failed = [check["name"] for check in checks if not check["passed"]]
    commit_allowed = not failed
    # Only claim zero attestations when the manifest itself forbids creating one.
    # A non-conforming provenance section cannot support that claim.
    no_new_attestation = next(
        (check["passed"] for check in checks
         if check["name"] == "provenance.creates_new_attestation"), False)
    return {
        "verdict": "pass" if commit_allowed else "fail",
        "failed": failed,
        "commit_allowed": commit_allowed,
        "source_of_truth_action": "commit_in_batches" if commit_allowed else "unchanged",
        "new_attestations_created": 0 if no_new_attestation else None,
        "checks": checks,
        "dry_run": dry_run,
        "batch_plan": batch_plan,
    }


def format_report(report):
    """Render a verdict, its commit effect, dry-run counts and every check."""
    lines = ["OPS-08 onboarding: " + report["verdict"].upper()]
    lines.append("  commit allowed: " + str(report["commit_allowed"]))
    lines.append("  source of truth: " + report["source_of_truth_action"])
    lines.append("  new attestations created: "
                 + str(report["new_attestations_created"]))
    lines.append("  dry run counts: "
                 + json.dumps(report["dry_run"].get("counts", {}), sort_keys=True))
    for check in report["checks"]:
        status = "PASS" if check["passed"] else "FAIL"
        lines.append("  [" + status + "] " + check["name"] + ": " + check["detail"])
    return "\n".join(lines)


def main(argv=None):
    """Evaluate a JSON manifest path from the command line."""
    arguments = sys.argv[1:] if argv is None else list(argv)
    if len(arguments) != 1:
        print("usage: onboarding.py <manifest.json>", file=sys.stderr)
        return 2
    try:
        manifest = json.loads(Path(arguments[0]).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        print("cannot read manifest: " + str(error), file=sys.stderr)
        return 2
    report = evaluate(manifest)
    print(format_report(report))
    return 0 if report["verdict"] == "pass" else 1


if __name__ == "__main__":
    sys.exit(main())
