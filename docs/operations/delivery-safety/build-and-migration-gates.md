# Signed builds and migration checks (OPS-06)

## Signed builds

The `signed_builds` object binds a released artifact to a verifiable identity:

| Field | Required value | Check name |
|---|---|---|
| `method` | one of `cosign`, `sigstore`, `in-toto` | `signed_builds.method` |
| `digest` | `sha256:` followed by exactly 64 hexadecimal characters | `signed_builds.digest` |
| `verified` | literal `true` | `signed_builds.verified` |
| `signer_identity` | a non-blank string naming the trusted signer | `signed_builds.signer_identity` |
| `promotion_gate` | exactly `signature_verified` | `signed_builds.promotion_gate` |

The digest is matched with a full-string regular expression; a short digest, a
wrong hash prefix, non-hexadecimal characters, leading or trailing text, a
non-string value, and a value of the wrong length all fail. Upper- and
lowercase hexadecimal are both accepted. The literal-boolean rule rejects
`"true"` and `1`, so an unsigned build cannot pass by type confusion. The
promotion gate makes verification a precondition of release rather than an
after-the-fact label.

## Migration checks

The `migration_checks` object ties the artifact to the repository's real
change-safety gate:

| Field | Required value | Check name |
|---|---|---|
| `gate` | exactly `check-migrations --require-baseline` | `migrations.gate` |
| `expand_contract` | literal `true` | `migrations.expand_contract` |
| `forward_fix_required` | literal `true` | `migrations.forward_fix_required` |

`check-migrations --require-baseline` is the offline checker introduced by
decision `docs/decisions/0002-ops06-migration-change-safety.md`. It enforces
unique version numbers, a fail-closed non-empty migration directory and
expand/contract discipline with a format-valid `Forward-fix:` and a real
earlier `Change: expand` reference. The canonical
[`check.sh`](../../../infra/delivery-safety/check.sh) runs that checker for real instead
of trusting the artifact's declared command. The runtime authority remains
`db/check.sh`, which applies migrations to a real PostgreSQL instance.

The `forward_fix_required` declaration records the commitment to a follow-up
migration; the checker intentionally does not require the forward-fix file to
exist yet, because it may be a future version.

## What this does not prove

The evaluator cannot verify the cryptographic signature, the trust root behind
`signer_identity`, or that promotion was truly blocked before verification. It
cannot prove the declared migration command was executed with the recorded exit
code. Real pipeline records, signed artifact provenance and a migration run log
must be supplied by release engineering and reviewed by DevSecOps; those are
open items in the [handoff](README.md).
