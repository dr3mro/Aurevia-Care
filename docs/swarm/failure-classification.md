# Swarm failure classification

When a swarm pull request fails, the useful question is not only *what broke*
but *which layer should have caught it*. `diagnose` in `scripts/swarm.py`
summarises the log; `classify` in `scripts/failure_classes.py` takes that log
together with the task text and the changed file paths and returns a failure
class and a `catch_layer` — the place where one regression test would stop the
same kind of failure reaching the integrator again.

This classification supports OPS-06 (CI policy checks and actionable
operational diagnostics). It is an offline keyword classifier: no network, no
credentials, no patient data. All example logs below are synthetic.

## The five classes

| Class | Meaning | Catch layer |
|---|---|---|
| `task-policy` | The task itself was wrong or ambiguous: protected files, `needs-human`, an unknown requirement, work outside scope. No code change can fix it. | `task template` |
| `missing-tests` | A new line or branch shipped without a test; coverage below the 100% gate. | `worker-check` |
| `check-parity` | A check passed locally and failed in Delivery: contract drift, a route absent from OpenAPI, a gate only the integrator runs. | `integrator-gate` |
| `implementation` | An ordinary code defect: a failing assertion, build or type check with no policy or environment cause. This is the catch-all when no other signal matches. | `unit test` |
| `infrastructure` | The runner or an external dependency failed: quota, rate limit, credentials, connection refused, timeout, out of memory. | `contract test` |

Each catch layer is where a regression belongs, not merely where the failure
was seen:

- `task template` — validate the task at authoring time so an impossible scope
  never reaches a worker.
- `worker-check` — the worker's coverage gate must reject an uncovered new
  line or branch before the pull request is published.
- `integrator-gate` — the full Delivery-equivalent gate runs on the merge
  result; a check that only fails there belongs in this gate.
- `unit test` — reproduce the defect deterministically at the boundary that
  owns the rule.
- `contract test` — simulate the dependency failure (refusal, timeout, rate
  limit) at the interface and assert the retry or fail-closed behaviour.

## Calling the classifier

```python
from failure_classes import classify  # with scripts/ on the import path

result = classify(log, task_text, changed_files)
# {"class": "check-parity", "catch_layer": "integrator-gate"}
```

`log` and `task_text` are strings and `changed_files` is a sequence of
repository-relative paths. Any of them may be empty. The result always names
one of the five classes and its single catch layer; an unrecognised log is
treated as `implementation` so that every failure has an owner.

## Example log per class

`task-policy`:

```text
$ ./check.sh
ERROR: protected files changed; a person must decide (needs-human).
Process completed with exit code 4
```

`missing-tests`:

```text
[INFO] BUILD FAILURE
[ERROR] Coverage for lines (87%) does not meet global threshold (100%)
[ERROR] 1 branch not covered in scripts/example.py
Process completed with exit code 1
```

`check-parity`:

```text
$ ./check.sh
ERROR: check parity: openapi contract mismatch; route POST /patients
is missing from the contract locally but fails in Delivery.
Process completed with exit code 1
```

`implementation`:

```text
[INFO] BUILD FAILURE
[ERROR] FAIL com.example.OrderTest.rejectsMissingId
[ERROR] AssertionError: expected 5 to equal 7
Process completed with exit code 1
```

`infrastructure`:

```text
$ ./check.sh
ERROR: partner endpoint connection refused; HTTP 503 after a 30s timeout.
Process completed with exit code 7
```

## Review rule

After every five swarm pull requests have been judged (merged, `needs-fix` or
`needs-debug`), reconcile the batch:

1. Run `classify` on the log, task text and changed files of each failure,
   including worker-check failures, integrator `needs-fix` labels and debugger
   reports.
2. Group the results by class. A class with no failure in the batch needs no
   action.
3. For each class that did produce a failure, add **one** regression at the
   catch layer in the table above — the layer that should have caught that
   class earlier. Do not add it at a weaker layer and do not change a class's
   layer to suit a convenient test.
4. Record the date, the pull requests, the class and the regression added in
   the table below. A repeat of an already-recorded class is only a new row if
   it produced a new regression; otherwise note the repeat in the pull request
   instead.

The table is intentionally empty until the first review is held.

## Review record

| Date | Pull requests | Class | Regression added |
|---|---|---|---|
