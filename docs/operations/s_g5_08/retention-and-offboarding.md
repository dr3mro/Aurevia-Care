# Retention, legal hold, deletion, offboarding and disputed access (OPS-07)

## Retention ledger

The `retention` section is a set of ledgers. A missing or malformed ledger is
**never** interpreted as "nothing happened".

- `policy_version`: non-blank string.
- `legal_holds`: list of holds with `id`, `reason`, `scope`, `placed_by` and a
  boolean `active`. An empty list is a valid explicit "no holds" declaration.
- `deletions`: list of records with `record_id`, `reason`, `approved_by` and a
  boolean `active_data`. Every entry must represent an approved deletion of
  active data: `active_data is true`, a non-blank reason and a non-blank
  approver (`retention.deletion_entries`, `.deletion_approval`).
- `backup_expirations`: list with `backup_set`, `scope`, `approved_by` and
  `approved is true`; an unapproved expiration fails
  (`retention.backup_expiration_entries`).

## Legal hold is binding

A hold's `scope` is an exact record id, an ancestor separated by `/`, or `*`
for the whole tenant. Deletion is rejected when any **active** hold covers the
deleted `record_id` (`retention.legal_hold_respected`). Backup expiration is
rejected when any active hold covers the expiration `scope`
(`retention.backup_hold_respected`). An inactive hold permits both. This is the
key safety invariant: retention jobs must not destroy evidence under hold.

## Offboarding

`offboarding` must name the tenant `organization`, a `authorized_by` actor and
an `audit_reference`. Its `steps` list must contain all five governed actions in
order: `suspend_access`, `export`, `delete_active_data`, `expire_backups`,
`audit`. Extra steps between them are allowed; a missing or reordered step
fails. "Complete" with no authorization, no audit reference or an incomplete
step list is never accepted.

## Disputed access

`disputed_access` must name the affected `link`, record `suspended_at` and
`suspended_by`, and name `rereviewed_by` / `rereviewed_at`. `outcome` is
`suspended`, `restored_after_verification` or `closed`. Restoration additionally
requires `identity_reverified is true`; a bare "reinstated" or "granted" outcome
fails. This encodes the IAM-05 rule that a disputed link is suspended,
re-reviewed by an authorized person and re-verified before future access is
restored.

## Representative scenarios

The `scenarios` list must contain exactly the four tested cases —
`record_request`, `export`, `offboarding` and `disputed_access` — each once.
Duplicates and mixed non-string members fail `scenarios.no_duplicates`.

## What this check cannot prove

The evaluator cannot confirm that the ledgers reflect reality, that a hold was
placed by an authorized privacy owner, that a backup was actually expired, that
memberships and invitations were revoked, or that a human re-reviewed a dispute.
These are G5 site-approval items with named clinical, privacy and operations
owners, recorded outside this synthetic harness.
