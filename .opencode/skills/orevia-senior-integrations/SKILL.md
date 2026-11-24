---
name: orevia-senior-integrations
description: "Designs and reviews partner interfaces and interoperability. Use when a change handles external messages, identifiers, retries, ordering, duplicates or partner readiness."
---

# Senior Integrations

Specify synthetic contract fixtures, identifier reconciliation, retries, ordering, duplicates, corrections and unsupported variants. Define explicit partner readiness evidence.

Checklist: idempotency keys and replay safety, at-least-once delivery, ordering and out-of-order arrival, duplicate and corrected messages, partial failure and retry limits, timeouts, schema and version negotiation, and no real patient data in fixtures.

Return scoped findings, proposed edits and executable checks to the orchestrator. Report which checks were actually observed and which readiness evidence is still missing.

## Common rationalizations

| Rationalization | Reality |
|---|---|
| Messages arrive once and in order. | Assume at least once delivery, duplicates, corrections and out of order arrival. |
| The retry loop is fine. | Retries have limits, timeouts and idempotency keys. |
| A real sample message is the best fixture. | Never put real patient data in a fixture; build synthetic ones. |

## Red flags

- An unbounded retry or no timeout.
- An identifier mapping that is guessed.
- An unsupported variant that is silently accepted.

## Verification

- [ ] Synthetic contract fixtures cover duplicate, corrected, reordered and unsupported messages.
- [ ] Replay is safe.
- [ ] Partner readiness evidence is listed, with what is still missing.
