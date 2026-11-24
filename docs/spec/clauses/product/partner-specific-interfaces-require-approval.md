# PRD-02 clause 2 — Partner-specific interfaces require approval

## Clause

Partner-specific interfaces require approval.

## Status

Not built

## Evidence

The clause is a governance requirement, not an implementation: it is stated in
[PROJECT_V1_SPEC.md](../../../../.opencode/config/PROJECT_V1_SPEC.md) section 1 as the PRD-02 boundary,
restated in section 8 (partner-specific profiles and conformance fixtures at agreed boundaries),
enforced as a site prerequisite by the section 9 G5 gate, and assigned an accountable role and
evidence list in section 10: the "Lab, PACS and pharmacy interface contracts" decision is owned by
the Integration lead and partner owner and needs conformance results, approved credentials,
reconciliation and an outage drill. Section 10 also says to enable provider or partner traffic
only after that evidence exists.

Repository files found that relate to the clause (none of them is a partner-interface approval):

- Generic named-approval mechanism, reused for configuration and catalog entries (ADM-01) but not
  for partner interfaces: [ConfigurationService.java](../../../../apps/api/src/main/java/com/aurevia/configuration/ConfigurationService.java)
  (`approve`), [ConfigurationController.java](../../../../apps/api/src/main/java/com/aurevia/web/ConfigurationController.java)
  (`POST /api/v1/configuration/{key}/versions/{version}/approve`),
  [Approval.java](../../../../apps/api/src/main/java/com/aurevia/catalog/dictionary/Approval.java),
  [VersionStore.java](../../../../apps/api/src/main/java/com/aurevia/catalog/dictionary/VersionStore.java)
  and [CatalogController.java](../../../../apps/api/src/main/java/com/aurevia/web/CatalogController.java).
- Synthetic partner conformance scaffolding (input to conformance evidence, not an approval record):
  [ConformanceFixtures.java](../../../../apps/api/src/main/java/com/aurevia/exchange/conformance/ConformanceFixtures.java)
  and its package (`ConformanceLedger`, `PartnerEvent`, `EventKind`, `Disposition`), the
  [PartnerConformanceFixturesTest.java](../../../../apps/api/src/test/java/com/aurevia/it/exchange/PartnerConformanceFixturesTest.java)
  integration test, [partner-events.json](../../../../contracts/events/fixtures/partner-events.json),
  [events/README.md](../../../../contracts/events/README.md) and
  [contracts/README.md](../../../../contracts/README.md). The fixtures are explicitly synthetic,
  deterministic and replayable without a live partner, credential or database (XCH-01–07, LAB-01,
  LAB-02, ARC-05).
- Partner boundary references in code: `radiology/ImagingService.java` records that patient/accession
  verification for the partner PACS/DICOMweb study is the partner's responsibility (RAD-01);
  RX-02 routes through an approved prescribing channel or a locally approved printable prescription.

Workflow runs / infrastructure files / signed decision records: **none found**. The launch decision
pack `docs/launch/partner-interfaces.md` (prepared by TODO task `GATE-DECISION-PARTNER-INTERFACES`,
marked "Not approved") and the G5 human decision `S-G5-04` are not present as approval evidence in
the repository at the time of writing. No partner-specific interface is named as approved, and no
conformance result, approved credential, reconciliation record or outage drill for a real partner is
stored here.

## Gap

Missing: the actual approval decision from section 10 and the associated evidence for each lab,
PACS and pharmacy interface. The repository holds the generic ability to name-approve configuration
and catalog versions and to rehearse conformance with synthetic fixtures, but it does not hold a
signed approval for any partner-specific interface, nor the section 10 evidence (conformance
results, approved credentials held by the site, reconciliation and a timed outage drill). G5 site
sign-off that integrations are approved for the enabled site is also absent.

Files that would change, without changing code to produce the approval itself:

1. `docs/launch/partner-interfaces.md` — create the decision pack (owned by
   `GATE-DECISION-PARTNER-INTERFACES`, currently `[-]`); the approval record stays empty until a
   person signs it.
2. `docs/operations/production-readiness/launch-checklist.md` and the G5 evidence set — record the
   site's integration sign-off (owned by `S-G5-04`, a human decision).
3. `contracts/` partner-specific profiles/conformance fixtures and the
   `exchange/conformance` fixture catalog — extend with each real partner's approved interface
   once a contract is signed (follow-up tasks, not yet in tasks/).

Number of separate tasks: at least two tracked tasks remain — `GATE-DECISION-PARTNER-INTERFACES`
(prepare the decision pack) and `S-G5-04` (the accountable people approve at G5) — plus one
implementation follow-up per approved partner to add its real interface fixtures. Approved
credentials must be referenced, never committed to the repository.

## How to verify

Repository checks a reviewer can run now (all use synthetic data):

- `python3 -I scripts/check-docs.py` — link/requirement-ID integrity of this note; must exit 0.
- `test -f docs/launch/partner-interfaces.md` — currently fails; passes only when the decision pack
  is merged.
- `grep -rn "approve" apps/api/src/main/java/com/aurevia/configuration apps/api/src/main/java/com/aurevia/catalog apps/api/src/main/java/com/aurevia/web`
  — shows the generic named-approval endpoints; confirms they are not partner-interface approvals.
- `grep -rn "partner" contracts/events/fixtures/partner-events.json contracts/events/README.md`
  — shows the synthetic partner conformance fixtures.
- `mvn -q -B -f apps/api/pom.xml test -Dtest=PartnerConformanceFixturesTest` — the synthetic partner
  conformance rehearsal (conformance input, not an approval).

Human evidence a site must produce before G5 (from spec sections 9 and 10), which no command can
substitute for: a signed approval of each Lab, PACS and pharmacy interface contract by the
Integration lead and partner owner, with conformance results, approved credentials (held by the
site, never committed), reconciliation evidence and a timed outage drill; plus G5 sign-off that the
enabled site's integrations are approved. The Integration lead, partner owner and site
clinical/operations governance supply this evidence. Until it exists, provider or partner traffic
must not be enabled.
