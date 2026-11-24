# Observability checklist

Quick reference for instrumenting new critical paths in `apps/api` and the workers.
Use alongside `aurevia-devsecops` and the operations runbooks. Telemetry without a
question is noise: before adding a signal, write down the on-call questions it answers.

## On-call questions

- [ ] 2–4 questions an on-call engineer would ask about this behavior are written down.
- [ ] Every signal maps to one of those questions: metrics say *that*, traces say *where*,
      logs say *why*.
- [ ] The question can be answered without reading source code.

## Structured logging

- [ ] Logs are structured JSON with `correlationId`, `actor` (synthetic in tests),
      `action`, `outcome` and a stable schema version.
- [ ] Correlation IDs are generated at the entry point and echoed in responses.
- [ ] No password, token, patient identifier or raw request body is ever logged.
- [ ] Failures log the reason and the boundary that refused them, not just "failed".

## Metrics and health

- [ ] A counter/rate exists for each success and each structured failure code on a new route.
- [ ] Latency is recorded for new protected routes (the `CorrelationIdFilter` does this).
- [ ] The health endpoint reflects dependency readiness, not just process liveness.
- [ ] Alerts on new metrics have a threshold, an owner and a runbook link.

## Verification

- [ ] The signal is asserted in a test (log event, metric or emitted marker).
- [ ] `scripts/check-secrets.py` passes after adding log fields.
- [ ] The runbook in `docs/operations/` names the new signal and its meaning.

## Red flags

- A new "log and continue" catch block with no metric and no alert.
- A log line carrying a real patient ID or a credential.
- A dashboard panel with no linked question or owner.
