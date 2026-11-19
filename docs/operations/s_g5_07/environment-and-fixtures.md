# Environment and fixture separation (OPS-06)

The evaluator accepts an `environments` object and a `fixtures` object. Both are
declarations; the evaluator checks their form and fail-closes on any breach.

## Environments

The environment name set is exactly `development`, `test`, `staging`,
`production` (`environments.names`). Aliases, missing names and extra names
fail. Each entry must be an object with two non-blank string fields:

| Field | Meaning |
|---|---|
| `account` | The isolated account identifier for that environment |
| `resource` | The isolated resource namespace for that environment |

All four account identifiers must be distinct
(`environments.distinct_accounts`) and all four resource identifiers distinct
(`environments.distinct_resources`). A shared identifier between any two
environments fails the check, as does a missing, blank or non-string value.
Identifiers are compared with exact string equality: no case folding or
whitespace trimming is applied to the *comparison* (values are stripped only to
detect a blank declaration), so two environments cannot be conflated.

An entry that is not an object records `environments.<name>` as failed and the
two distinctness checks also fail, because a complete set of four identifiers
cannot be collected.

## Fixtures

The `fixtures` object must declare:

| Field | Required value | Check name |
|---|---|---|
| `synthetic_only` | literal `true` | `fixtures.synthetic_only` |
| `no_real_patient_data` | literal `true` | `fixtures.no_real_patient_data` |
| `environments` | a list containing exactly `development`, `test`, `staging` | `fixtures.environments` |

The production environment is deliberately absent from the fixture list: this
repository ships synthetic rehearsal material only. A list with duplicates, a
non-string member, a missing lower environment or production included fails.
The element type guard runs before the set comparison, so an unhashable member
(a nested object) fails closed instead of raising.

## CI gates

The `ci_checks` object must enable all three mandated gates as literal booleans:

- `ci_checks.policy` — repository policy checks (`scripts/check.sh`,
  `scripts/quick-check.sh`);
- `ci_checks.dependency_scan` — OWASP dependency analysis inside `mvn verify`;
- `ci_checks.secret_scan` — credential and PHI-shaped string scanning.

A missing key, a value of `1` or `"true"`, or a `false` value fails the named
check. The evaluator does not read scanner output; an authorized pipeline must
supply that evidence. The [evidence contract](evidence-contract.md) lists the
claim boundary.

## What this does not prove

A conforming declaration proves only that the artifact names four different
accounts and resources, declares synthetic fixtures and names the three CI
gates. It cannot prove that those cloud accounts exist, that IAM policies keep
them separate, that lower environments contain no real patient data, or that
any scanner actually ran. Those claims require cloud and pipeline evidence and
remain open G5 items in the [handoff](README.md).
