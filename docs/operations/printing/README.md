# Patient handout printing guide

- Requirements: CLN-07, RX-02, LAB-01
- Status: this guide and its [permission matrix](permissions.md) and
  [acceptance scenario](uat.md) are delivered. The API rendering and print
  endpoints and the staff-web preview and buttons are delivered by separate
  implementation stories; this directory is the operational handoff for those
  capabilities, not a claim that a live site is enabled.
- Audience: clinicians, lab and imaging staff, reception, tenant admins,
  operations, QA and acceptance reviewers.
- Evidence source: the approved [specification](../../../.opencode/config/PROJECT_V1_SPEC.md)
  and the implemented behaviour. All examples use synthetic data.
  [Authorization boundaries](#authorization-and-audit) are enforced by the
  server, not by this document.

A "handout" is an immutable, versioned PDF produced for a patient from a
committed clinical source. Printing never changes the source record and never
mints a clinical assertion: a signed prescription is still signed, a placed
order is still placed, and a handout is only a rendering of that fact for the
patient to carry. The rules here implement CLN-07 (immutable versioned PDFs),
RX-02 (approved prescribing channel or locally approved printable prescription)
and LAB-01 (issuer-scoped requisition identity).

## Requirement mapping

| Requirement clause | Where it is handled here |
|---|---|
| CLN-07 — immutable versioned PDFs, English or Arabic, embedded fonts, direction-aware layout, page numbers, provenance and print profile | [Output and layout](#output-and-layout), [Reprints and versions](#reprints-and-versions) |
| RX-02 — prescriber attestation, approved channel or locally approved printable prescription, actual channel recorded | [What can be printed](#what-can-be-printed), [Refusal rules](#refusal-rules) |
| LAB-01 — issuer-scoped unique accession, specimen, site, collection and receipt facts | [What can be printed](#what-can-be-printed), [Refusal rules](#refusal-rules) |
| OPS-02 — durable, sanitized audit of sensitive downloads and disclosures | [Audit entry](#audit-entry) |
| IAM-01, IAM-02 — active membership, role, facility scope, next-request permission changes | [Authorization boundaries](#authorization-and-audit), [permission matrix](permissions.md) |
| PAT-02 — patient downloads released documents and sees correction context | [Reprints and versions](#reprints-and-versions) |

## What can be printed

Five kinds of handout exist. Each is produced from a source in a specific
committed state; an uncommitted source is refused rather than rendered.

| Kind | Source state required | Content the handout carries |
|---|---|---|
| Prescription — patient copy | A signed or attested prescription with an approved print channel (RX-02) | Medication, strength, formulation, dose, route, frequency, quantity, instructions, prescriber, patient and facility, issue time, provenance footer |
| Prescription — pharmacy copy | Same as the patient copy, plus a channel that requires a pharmacy copy | The same prescription facts, marked as the pharmacy copy. A printable local channel produces this document only; it never implies electronic delivery or pharmacy acceptance |
| Lab order requisition sheet | An order that is **placed**, not a draft (LAB-01) | Tests, clinical question, priority, preparation instructions (for example fasting), where to go, and the issuer-scoped order/accession number in human-readable form |
| Imaging order requisition sheet | An order that is **placed**, not a draft | Study, clinical question, priority, preparation, where to go and the order identifier. The sheet is not the diagnostic workstation; images stay under partner access control |
| Chart summary | Signed content only (CLN-05) | Demographics, allergies, active problems, medications, vitals with units and time, signed notes for the chosen visit or range, and results released to the patient |
| Signed report | A signed report revision only | The signed report content and its provenance |

For chart summaries and reports, the clinician chooses the visit or date range
and the sections before printing. The choice is recorded with the version.
Unsigned drafts and withheld results are never rendered; the preview lists what
was left out and why so the omission is visible rather than silent.

## From where

Printing is a staff-web action. The entry points are the Print button on the
prescription screen, on the lab and imaging order screens, and on the chart and
signed-report screens. Each button opens one shared preview dialog that shows
the handout and lets the clinician:

- choose the language, English or Arabic;
- choose a reason from **give to patient**, **referral**, or **patient request** —
  a reason is required before Print is allowed;
- see loading, error, forbidden and refused states, with any refusal reason in
  plain words;
- Print, Download or Close.

The browser may also print the preview page itself using the A4 print styles
(15 mm margins, no application chrome). The download is of the same immutable
version that the preview shows. No handout is generated or released merely by
opening a screen or a preview.

## Who may print

Printing is default-deny. The server authorizes every print and every download
from the caller's current active membership, role, care relationship to the
patient and facility scope; it never trusts a role or patient identifier
supplied by the client. Permission changes take effect on the next protected
request (IAM-01, IAM-02). The full role-by-handout matrix is in
[permissions.md](permissions.md); in short:

- the **treating clinician** with the required privilege may print every kind
  for a record inside their care relationship and facility;
- **lab collector/verifier** and **radiology staff** may print the requisition
  sheet for orders assigned to them, within their site privileges;
- **reception, finance, tenant admin, privacy auditor, pharmacy and general
  clinical-assistant roles** do not print patient handouts in this flow;
- the **patient** does not use staff printing. The patient app shows only
  handouts a clinician has released to that linked chart, and checks each
  download again at request time (PAT-02).

A print permission is a named role-action in tenant configuration. A site may
narrow it; it may not widen a role beyond the clinical privileges and facility
scope the specification already gives that role.

## Refusal rules

A refusal produces no PDF and no download handle. The common refusals are:

| Situation | Result |
|---|---|
| The source is a draft (unsigned note, unplaced order, unsigned or unattested prescription) | Refused — only signed or placed items print |
| A chart summary would include an unsigned draft or a withheld result | Those items are excluded and listed as left out with their reason; the handout still prints from the remaining signed content |
| The prescription has no approved print channel, or the channel needs partner status that is ambiguous | Refused until the channel is approved or partner status is verified; an ambiguous transmission is never retried on a guess (RX-02) |
| The record belongs to another tenant, another facility, or a patient outside the caller's care relationship | Refused. Cross-tenant records are concealed as "not found"; no existence or content is revealed (ARC-02) |
| The caller lacks the print permission or the role's scope excludes that kind | Forbidden; the preview shows the forbidden state |
| The source has changed since the version the user previewed | Refused with a stale-version conflict; reload and preview the new version rather than printing a mismatched handout |

A refusal is reported in words in the preview and is never a silent no-op.
Denials are audited with the same shape as a successful print, with an outcome
that records the refusal (OPS-02).

## Output and layout

Every handout is A4, numbered, and generated in English or Arabic with embedded
fonts and direction-aware layout. It shows the patient's name and identifier,
the clinician, the facility, the issue time and a provenance footer that names
the source revision and version. Facility letterhead, footer text, default
language and paper size come from approved, effective-dated facility settings
with history (CLN-07). The bytes and checksum of the source are retained with
the binding; image rotation, where an image appears, is presentation metadata
and does not change the bound source (ARC-06, CLN-07).

## Reprints and versions

- A handout is bound to the exact source revision, version and content checksum.
- Printing the **unchanged** source again returns the **same version**. This is
  a reprint, and it is audited again.
- A **changed** source produces a **new version**; the old version remains
  immutable and retrievable.
- When a new version replaces one the patient already holds, the patient app
  marks the older one as **Corrected** and shows the correction context
  (PAT-02). A replaced handout is never silently overwritten.
- A reprint of a superseded version is allowed for audit and referral needs but
  the preview tells the clinician that a newer version exists.

## Authorization and audit

The print and download endpoints are protected commands. They require an
authenticated staff subject, an active membership in the record's organization,
the print permission in the correct facility scope and a current care
relationship where the kind demands one. The check is re-derived on every call;
revocation therefore applies to the next request (IAM-01, IAM-02). No patient
data is placed in URLs, logs, notifications, analytics or browser storage
(WEB-02).

Every print, reprint, download and refusal writes one append-only audit entry
(OPS-02) containing:

| Field | Content |
|---|---|
| Actor | The authenticated staff subject and the role used for the decision |
| Purpose | One of the three reasons: give to patient, referral, patient request |
| Action | Print, reprint, download or a named refusal |
| Resource | The handout kind, source revision id and handout version — identifiers only |
| Scope | Organization and facility of the record |
| Time / correlation | Server timestamp and the request correlation id |
| Outcome | Success or the specific refusal |

The audit entry never contains handout content, clinical narrative, the
patient's name or identifier, a file name, a URL or an object key. This is why
the reason list exists: it records *why* a disclosure happened without recording
*what* was disclosed. Audit records are append-only, restricted and covered by
tamper detection (OPS-02).

## Boundaries and open items

- Synthetic data only. No document in this directory contains a real person,
  identifier or credential.
- No clinical, privacy or launch approval is implied. The site policy pack,
  market entitlement, partner contracts and permission assignments belong to
  the accountable owners (OPS-01, ADM-01); real-patient acceptance is a G5 gate.
- This is a documentation story. No application code, schema, API contract, CI
  or shared tracker file is in scope.
- Facilities that enable the electronic prescribing channel must record the
  actual channel and the stable submission id; a locally printable prescription
  is not evidence of electronic delivery or pharmacy acceptance (RX-02).

## Verification

| Check | Command | Proves |
|---|---|---|
| Links and requirement IDs | `python3 -I scripts/check-docs.py` | Relative links resolve and every requirement ID used here is defined in the specification |
| Credential and PHI shapes | `python3 -I scripts/check-secrets.py` | No credential or patient-identifier pattern was introduced |

The [acceptance scenario](uat.md) is a simulated clinician walkthrough for QA;
it is not evidence of a real clinical pilot or human acceptance.
