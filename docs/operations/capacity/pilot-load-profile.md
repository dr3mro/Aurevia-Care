# Pilot load profile (OPS-03)

- Requirement: OPS-03
- Machine-readable artifact: [pilot-load-profile.json](../../../infra/capacity/pilot-load-profile.json)
- Back to the [S-G5-05 handoff](README.md).

## The specified scenario

| Parameter | Value | Source |
|---|---|---|
| Organizations | 20 | Specification literal |
| Total patient charts | 100,000 | Specification literal |
| Concurrently active staff | 200 | Specification literal |
| Mixed API request rate | 50 per second | Specification literal |
| Measured window | 30 minutes | Specification literal |

These values are encoded identically in the artifact and enforced by the
[evaluator](../../../infra/capacity/capacity_evidence.py). The unit tests assert
the artifact and the module agree, so the two cannot drift silently.

## Declared workload mix — an assumption

The specification says "mixed API requests" but does not fix the mix. The
artifact declares a **70% read / 30% command** starting split, weighted toward
interactive chart reading. This is a design assumption to be revised after
observation, as OPS-03 requires; it is not a specification value and is not
enforced beyond the weights summing to 1.0 and every family being class-mapped.

Read families: patient chart lookup, appointment worklist, catalog/configuration
reads and audit queries. Command families: appointment booking, encounter
check-in, clinical documentation sign, order transitions, result reconciliation,
patient release and messaging instructions. Each family's `name` is the single
canonical route identifier: the conforming evidence artifact's `route_class`
uses exactly these names and classes, and a unit test asserts the two agree, so
a measured run cannot silently drop a declared command family.

## Synthetic dataset

The generator seeds 20 organizations with 5,000 deterministic synthetic charts
each, giving 100,000 records. Two organizations share identical local identifiers
to exercise tenant-context derivation, following the G0 isolation precedent. No
real person, identifier or credential appears in the dataset. Dataset and
warm-up are declared separately from the measured window:

- a 5-minute warm-up is excluded from the reported latency percentiles;
- the 30-minute measurement window is steady-state only;
- each request is tagged with its request class and route so the declared
  `route_class` envelope can be checked at scoring time.

## Running a rehearsal

The artifact in this repository is a *rehearsal*: it proves the evaluator and
fixtures work, using synthetic numbers. Run it with:

```bash
bash infra/capacity/check.sh
python3 -I infra/capacity/capacity_evidence.py \
  infra/capacity/fixtures/synthetic_pilot_evidence.json
```

A real run replaces the synthetic fixture with measurements collected by an
authorized load-test tool in an approved environment, keeps the same profile and
envelope, and records its provenance. That environment and its measurement are an
open gate, documented in [Performance evidence](performance-evidence.md).
