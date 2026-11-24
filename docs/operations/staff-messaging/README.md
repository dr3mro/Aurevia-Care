# Staff messaging operations guide (MSG-07)

- Requirement: **MSG-07** (operational context: MSG-01 through MSG-06, OPS-02,
  OPS-07, IAM-01, IAM-02 and IAM-05).
- Status: engineering and operations handoff for the secure staff messaging
  feature. It states what staff may and may not send, what the system records,
  and which retention rules apply. Site-specific channels, role names, retention
  windows and approvals are **not** decided here and remain G5 site-approval
  items.
- Audience: unit staff, unit supervisors, tenant administrators, privacy
  auditors and engineering reviewers.

This directory is the handoff for safe use of secure staff messaging. It is
written against the approved product specification
([PROJECT_V1_SPEC.md](../../../.opencode/config/PROJECT_V1_SPEC.md)) and the implemented
messaging contracts. All examples use synthetic identifiers; no real person,
facility or clinical payload appears here.

## Requirement mapping

| MSG-07 clause | Where this guide covers it |
|---|---|
| Audit opening, viewing, sending, assigning, downloading, acknowledging, filing, correcting and restricting conversations or files | [What is audited](#what-is-audited) |
| Apply configured retention, export and legal holds | [Retention, holds and exports](#retention-holds-and-exports) |
| Preserve provenance when authorized staff link clinically relevant messages or instructions to the chart | [Patient references and chart filing](#patient-references-and-chart-filing) |
| Revoking a connection stops new messaging and future attachment access through that connection | [Connection changes and revocation](#connection-changes-and-revocation) |
| Record whether prior conversations remain readable under the verified patient's record-access policy | [Connection changes and revocation](#connection-changes-and-revocation) |
| Preserve sender and recipient retention duties | [Retention, holds and exports](#retention-holds-and-exports) |
| Requests for copies follow the site's record access procedure | [Copies and record access](#copies-and-record-access) |

Clause wording is taken from the [product specification](../../../.opencode/config/PROJECT_V1_SPEC.md).
No new requirement, threshold or approval is introduced by this guide. The
[UAT scenario](uat.md) exercises the rules below with synthetic data.

## Instant messages versus mail

| Property | Secure instant message (this feature) | Mail (ordinary email or post) |
|---|---|---|
| Delivery | In-app, role-scoped, inside one care-unit conversation | External transport outside this feature |
| Relationship to patient | Bound to a verified patient-to-unit connection | Not bound by a messaging connection |
| Notification | Generic push notice; full content only after authentication | Content is carried by the external system |
| Audit | Append-only fact for every action (see [What is audited](#what-is-audited)) | Governed by the external system, not this audit trail |
| Formal record | Not a signed order, prescription, disclosure or formal letter | Use the site-approved channel for formal correspondence |

Use an instant message for short operational coordination, approved typed
instructions and patient-supplied material attached to an existing conversation.
It is **not** a replacement for a signed prescription, a placed order, a signed
report, a formal disclosure or a record-access response; those use their own
workflows. Do not use either channel to move bulk patient data, credentials or
secrets. A message is never a clinical approval or a critical-result
acknowledgment (see [Not for emergencies](#not-for-emergencies)).

Operational coordination (logistics, scheduling, "the unit has your referral")
is distinct from a **clinical instruction**. Issuing a typed clinical
instruction requires the configured clinician authorization and must stay within
the sender's professional scope (MSG-04); a unit staff member who is not so
authorized may not issue one, even though they may coordinate and send ordinary
conversation messages. Filing, correcting and linking clinical material to the
chart are clinician-only actions (see [What is audited](#what-is-audited)).

## Inside a facility and between facilities

- **Inside a facility.** A conversation belongs to one care unit. Staff with an
  active membership and the appropriate role may act on it: conversation roles
  (`unit_staff`, `clinician`, `patient`) may open, view, send, download and
  acknowledge; `tenant_admin` may assign and restrict. Sending an ordinary
  coordination message is available to the conversation roles, but issuing a
  clinical instruction additionally requires clinician authorization (MSG-04).
  Every protected action re-derives membership and role from the server, so
  deactivation takes effect on the next request (IAM-01, IAM-02).
- **Between facilities.** Cross-facility contact exists only through a verified
  patient-to-unit connection. A conversation is never shared across units. A new
  recipient requires a separate authorized conversation with explicit disclosure;
  staff must not look up or contact a patient by directory search or demographics
  (MSG-01). Do not forward a conversation, a patient reference or an attachment
  to another unit as a workaround.
- **Before sending.** Each enabled unit publishes a service profile with its
  supported topics, hours, response target, queue owner, backup coverage and
  escalation, urgent and downtime channels (MSG-06). Show the recipient and any
  pending files and require an explicit send. When messaging is used outside
  service hours, the profile shows when the unit next handles messages.

## Attachments

- Allowed file types are **PDF** and **JPEG/PNG images** (MSG-03). Bind every
  upload to the uploader, the selected conversation and the unit.
- Before sending, validate integrity, size and type and quarantine-scan the
  file. A file becomes accessible only after a clean verdict and authorized
  message publication. Show scan failures to the sender with removal or
  replacement options; never bypass a failed scan.
- Preserve the original, checksum and provenance. A patient-supplied file enters
  the unit conversation inbox as patient-supplied material; an authorized
  reviewer must explicitly file it in the chart if it is clinically relevant.
- **Do not send:** executables or unsupported formats, oversized files, files
  that failed their scan, credentials, secrets, bulk exports, or any attachment
  through a connection that has been revoked or a conversation that is closed
  and read-only.

## Patient references and chart filing

- Reference a patient only through the verified chart bound to the conversation's
  authorized connection. Reference an appointment, order, referral, prescription
  or encounter when one is available (MSG-04). Never paste an identifier from
  another tenant or guess one from demographics: cross-tenant and nonexistent
  resources are concealed, and wrong-unit access is denied.
- When clinically relevant material is linked to the chart, preserve provenance:
  source type (message or instruction), stable source reference, immutable
  revision, the authorizing staff actor and the link instant. This is the
  `chart_link` audit fact; filing the material itself is the `file` fact and
  requires a clinician. The clinical payload stays in the authorized chart and
  conversation stores, never in the audit trail.
- A change after sending is a linked replacement or withdrawal that marks the
  previous revision superseded and notifies the patient (MSG-04). A correction
  is recorded as the `correct` fact; do not silently edit an issued instruction.
- Acknowledgment confirms receipt of the exact revision only (MSG-05). It is not
  consent, task completion or critical-result acknowledgment.

## Not for emergencies

Messaging is **not** an emergency or real-time service and must never be the sole
channel for time-critical care.

- For urgent or life-threatening situations, use the approved urgent, on-call or
  downtime channel published in the unit's service profile (MSG-06), or the
  site's emergency procedure. Do not wait for a message reply.
- The same rule applies to **inbound** content: if a patient reports acute or
  time-critical symptoms in a message, escalate it immediately through the
  published urgent/on-call channel or the site's emergency procedure. Never
  manage such a report by message reply or treat a message acknowledgment as a
  clinical response (MSG-06).
- Do not send critical results, emergency notifications or time-sensitive
  clinical decisions solely as a secure message. Critical-result acknowledgment
  uses its own workflow, not message acknowledgment (MSG-05).
- Outside service hours the profile shows when the unit next handles messages;
  that is not a promise of immediate review. If no appropriate channel is
  published for the unit, escalate through the named queue owner before relying
  on messaging.

## Retention, holds and exports

- Each resource type has a tenant-approved retention window. A resource becomes
  retention-eligible only after its window; retention application is recorded as
  the `retention_apply` fact. Number of days and approval belong to the site, not
  to this guide.
- A legal hold suspends retention and deletion for one resource. An active hold
  blocks retention; releasing it requires a named, reasoned authority and the
  original placement reason is always preserved. Holds and their release are
  recorded (`hold_place`, `hold_release`). See the tenant lifecycle
  [retention and offboarding handoff](../tenant-lifecycle/retention-and-offboarding.md)
  for the wider retention contract.
- **Both** the sender's and the recipient's retention duties are preserved as
  separate facts; a hold on one party never waives the other party's duty.
- Exports are scoped and expiring, with an explicit scope, creator, creation time
  and expiry (`export`). Do not create a broad or open-ended export to work around
  a retention or access rule.

## Connection changes and revocation

Revoking a patient-to-unit connection stops new messaging and future attachment
access through that connection. The effect is recorded with messaging blocked,
attachment access blocked, and whether earlier conversations stay readable. That
readability decision follows the verified patient's record-access policy, and
the policy version used is recorded as provenance; it is not inferred locally by
staff. Prior conversations are not silently published or silently destroyed on
revocation (MSG-07).

## Copies and record access

Requests for copies of messaging material follow the site's record-access
procedure, not an ad hoc staff reply. A copy request is routed and recorded as
the `copy_request` fact with the requesting actor, purpose and resource; the
feature does not itself disclose a record. Requesting a copy does not suspend the
sender's or recipient's retention duties.

## What is audited

Every protected messaging action appends a non-sensitive audit fact carrying the
actor, business purpose, tenant and unit scope, resource, correlation identifier,
server timestamp and outcome (OPS-02). The complete action vocabulary is:

| Group | Actions |
|---|---|
| Conversation and file actions | `open`, `view`, `send`, `assign`, `download`, `acknowledge`, `file`, `correct`, `restrict` |
| Governance actions | `export`, `hold_place`, `hold_release`, `chart_link`, `copy_request`, `retention_apply`, `audit_read` |

Points staff and reviewers should know:

- Failure facts are appended alongside successes. `denied`, `not_found` and
  `conflict` outcomes are recorded, so a refused action is never silently
  dropped.
- No audit fact carries a message body, attachment bytes, a diagnosis or any
  other clinical payload; provenance links reference a source and revision only.
- Reading the audit trail is itself audited (`audit_read`). Audit review,
  retention, holds and exports are limited to privacy or administrative roles;
  filing, correcting and chart linking require a clinician; assigning and
  restricting require `tenant_admin`; conversation actions require an active
  conversation role; copy requests are available to the patient, a tenant
  administrator or a privacy auditor.

## Boundaries

- Synthetic data only. No fixture, example or scenario in this directory
  contains a real person, identifier, credential or clinical payload.
- No clinical approval is implied. Urgent channel names, role-name mapping,
  retention windows, record-access policy versions and queue ownership are
  site decisions with named owners recorded outside this repository.
- No application code, schema, API contract, CI or tracker file belongs to this
  document set. These two Markdown files are the only scope.

## Open items

| Item | Why it is open | Who can close it |
|---|---|---|
| Site urgent, on-call and downtime channel values | Published by each unit's service profile, not fixed here | Unit supervisors with clinical operations |
| Retention windows and legal-hold process per resource type | Tenant-approved values; not specification literals | Privacy owner and tenant administrators |
| Mapping of site role names to the documented role keys | Declared keys must be mapped before live enablement | Tenant administrators with the privacy owner |
| Record-access policy version governing post-revocation readability | Site policy, recorded as provenance at decision time | Privacy owner and records management |
| Human acceptance of the [UAT scenario](uat.md) | A scripted walkthrough cannot attest that authorized staff performed it | Authorized unit staff and G5 acceptance owners |
