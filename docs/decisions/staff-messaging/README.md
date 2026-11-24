# Staff messaging — decision pack (proposed specification amendment)

> **This document is a proposal only. Do NOT edit `PROJECT_V1_SPEC.md`.**
> Only an authorized person (the specification/product owner) amends the
> specification, after answering the open questions in
> [Questions a person must answer](#questions-a-person-must-answer). No agent,
> swarm worker or integrator may add these requirements to the specification or
> treat them as approved V1 scope. This file records the proposed text and the
> decisions needed before a person can amend the spec.

- Status: proposed — awaiting a person's decision; not part of approved V1 scope
- Date: 2026-11-21
- Requirement IDs informed by: MSG-03, MSG-07, ARC-07 (existing; see
  [Relationship to existing requirements](#relationship-to-existing-requirements))
- New requirement IDs: none assigned. A person assigns formal IDs when the
  specification is amended; this pack uses descriptive labels instead.
- Scope of this pack: `docs/decisions/staff-messaging` only. No production code,
  database migration, API contract or dependency is included.

## Context

Section 6.1 of the specification defines secure **patient-to-unit**
communication in MSG-01 to MSG-07: a verified patient connects to exactly one
recipient care unit and the two parties exchange conversations, attachments and
instructions inside that verified relationship. ARC-07 fixes the matching data
contract: a conversation belongs to one recipient unit and one verified patient
connection.

**Staff-to-staff messaging is new scope.** It is not covered by MSG-01 to
MSG-07 and there is no existing staff-to-staff domain type in the codebase (the
messaging module models care units, connections, conversations, messages,
attachments and instructions for patients and units only). This pack proposes
the requirement text for staff-to-staff messaging so that a person can decide
whether and how to amend the specification. It deliberately reuses the
vocabulary already present in the system — organization, facility, care unit,
staff member, message, attachment — and does not invent parallel concepts.

This is a **scope change, not a gap fill**: the G3 gate currently tests MSG-01 to
MSG-07 only, so it would need extending after an amendment. Dependencies are
staff identity and roles, tenant isolation and audit (ARC-02), the quarantine
and scan controls of MSG-03, and a durable store with atomic publication, audit
and outbox commit (ARC-07).

## Proposed requirement text

Labels below are descriptive, not formal requirement IDs. Each item is written
to be testable once a person approves it.

### Proposed requirement — staff-to-staff instant chat

Authorized staff members can exchange short, near-real-time chat messages with
other staff members. A message is bound to the sender's identity and role, the
active organization and facility, and its named recipient or recipients. The
server validates the sender's authorization to address each recipient at send
time and re-validates access when the message is viewed. Message content is
immutable after publication and carries a server timestamp and a stable
conversation/thread sequence. Within one facility this is the default mode;
between facilities it is subject to the cross-facility decision below.

### Proposed requirement — email-like messages

Authorized staff members can compose longer, asynchronous messages with a
subject, one or more recipients, a body and an optional reply/forward chain. A
sent message is a durable record with delivery and read/unread state. Full
content is available only after authentication; generic notifications must not
reveal message content. Drafts are private to their author and cannot be read
or reused by another staff member.

### Proposed requirement — messaging inside a facility and between facilities

A staff member can address recipients in their own facility and, where
permitted, in another facility of the same organization. Each message is scoped
to the active organization and to the recipient facility; authorization is
checked on every send and view. Messaging between facilities of **different**
organizations is not assumed: it is gated on the cross-organization decision in
this pack and stays disabled until a person approves an agreement and the
required controls.

### Proposed requirement — attachments

Both instant-chat and email-like messages may carry attachments. Before a
message is sent, the sender sees the recipients and the pending files and must
confirm explicitly. Attachment uploads are bound to the author and the draft
message, are private until publication, and cannot be reused across messages.
The server validates integrity, file type and size, scans in quarantine, and
makes a file accessible only after a clean verdict and authorized message
publication. Scan failures are shown to the sender with removal or replacement
options. Originals, checksums and provenance are preserved. This follows the
attachment controls of MSG-03, adapted to staff-to-staff messages (there is no
patient conversation or care-unit inbox in this flow).

### Proposed requirement — audit, retention and legal hold

Publication, viewing, downloading and deleting of staff-to-staff messages and
their attachments are audited with actor, purpose, scope, timestamp, action,
resource and outcome. Configured retention, export and legal-hold rules apply
to both parties. Both sender and recipient retention duties are preserved.
Revocation of a staff member's membership, role or unit assignment — or
termination of a cross-facility agreement — stops future access to messages and
attachments. This follows the audit and retention controls of MSG-07; the
patient record-access policy and patient-connection revocation in MSG-07 are
patient-specific and do not carry over.

## Out of scope

- **Patient messaging and patient-to-unit conversations.** These are governed
  by MSG-01 to MSG-07, including verified connections, care-unit inboxes and
  patient instructions. Staff-to-staff messaging neither replaces nor extends a
  patient connection, and must not create or imply one.
- **External email.** No sending to or receiving from email addresses outside
  the organization, no SMTP/IMAP integration and no email federation. Any
  interoperation with external email is a separate decision and specification
  amendment.
- **Voice.** No voice calls, voicemail or audio messages.
- **Other clinical workflows.** Staff-to-staff messaging does not replace the
  order, critical-result, clinical-consent, prescription or task/completion
  workflows, and is not a channel for time-critical care outside the site's
  approved urgent and downtime procedures.

## Relationship to existing requirements

| Existing ID | What it establishes | How it bears on this proposal |
|---|---|---|
| MSG-03 | Attachment controls: integrity, size and type validation, quarantine scan, checksum and provenance, explicit send, private draft uploads. | Reused as the baseline for the proposed attachment requirement. The patient-conversation binding and the "patient-supplied material filed in the chart" step do **not** apply to staff-to-staff messages. |
| MSG-07 | Audit of message/file actions, configured retention/export/legal holds, sender and recipient retention duties. | Reused as the baseline for the proposed audit/retention requirement. Connection revocation, prior-conversation readability under the patient record-access policy and chart-linking provenance are patient-specific and do **not** apply. |
| ARC-07 | Message record shape: sender identity and role, immutable content, server timestamp, conversation sequence, private draft uploads, atomic commit of publication/audit/queue/outbox, durable client message IDs, access via verified connection and staff membership/role. | The record shape, atomic commit, deduplication and staff authorization-by-membership/role are a suitable baseline. The defining clause — one recipient unit **and one verified patient connection** — does **not** apply to staff-to-staff messages; a person must define the staff-to-staff authorization and scope model. |

Existing identifiers, fields and tables are referenced only to show alignment;
this pack proposes no schema change, endpoint or migration.

## Patient-bound elements that do not carry over

The following parts of the patient-messaging model are deliberately **not**
inherited by staff-to-staff messaging:

- The verified patient connection and care relationship (order, referral,
  prescription, invitation) as the authorization basis (MSG-01).
- The patient as a party, the patient app inbox with unread counts and the
  "awaiting patient" work-item states (MSG-02).
- Patient revision-specific acknowledgment (MSG-05); read receipts for staff are
  a separate decision, not an inherited rule.
- Generic push notifications to a patient with content gated behind patient
  authentication (MSG-05); staff messaging is surfaced only in the authenticated
  staff portal/client.
- Patient-facing service hours, response targets and urgent instructions
  (MSG-06).
- "Patient-supplied material filed in the chart" (MSG-03) and the patient
  record-access policy (MSG-07). Filing or linking staff messages to a chart is
  a decision for a person, not an inherited behaviour.
- Arbitrary-directory-contact and self-linking-by-demographics rules (MSG-01)
  are replaced by an internal authorized-recipient directory plus authorization
  and abuse controls.

## Additional decisions for the spec owner

Beyond the four questions below, a person should settle: the authorization model
(staff identity, membership, role and unit assignment as the basis to send and
read); the conversation shape (one-to-one, group or unit broadcast); delivery
semantics for instant chat versus email-like messages (persistence, offline
recipients, notification, retry and deduplication, consistent with ARC-07);
whether read receipts are recorded and shown; and whether staff messages may
ever be filed or linked to a chart. None of these is decided by this pack.

## Expected scenarios (synthetic actors only)

- **Normal:** a staff member at Facility A sends a colleague at Facility B of
  the same organization a chat message with a PDF attachment. After explicit
  send and a clean scan verdict, the recipient sees the ordered message with
  sender identity, role and server timestamp and can download the file;
  publication, audit and the notification-outbox entry commit together and the
  message is still retrievable after a restart.
- **Exceptional:** a sender addresses a destination they are not authorized for,
  giving an authorization error and no stored message; an attachment fails
  scanning, so the sender sees remove/replace options and the file is never
  downloadable; a retry carrying the same client message ID yields one message,
  not two.

## Migration and rollout implications (informational)

If approved, tenant-owned tables would need row-level security with forced
enforcement, Jdbc stores behind `*Store` interfaces, controllers that receive
services by injection, HTTP-level integration tests and regenerated
OpenAPI/client types. None of this is authorized by this pack: no migration
version is reserved here, and no code or contract change lands until a person
amends the specification and separate implementation stories are approved.

## Questions a person must answer

These are open decisions. No safe default in this pack is approved until a
person records one and amends the specification.

1. **May facilities of different organizations message each other, and under
   what agreement?** If cross-organization messaging is allowed, a person must
   name the required agreement (for example a data-sharing/BA agreement and
   partner contract), the reciprocal authorization model and the audit and
   isolation controls. Organization isolation is enforced today (ARC-02); any
   exception needs an explicit, verified relationship. Until decided, the
   proposal keeps cross-organization messaging **disabled**.
2. **What is the retention period and the legal-hold period?** A person must set
   the retention window for messages and attachments, the legal-hold trigger
   and duration, and how holds suspend deletion. This determines storage,
   export and deletion behaviour and interacts with MSG-07 and OPS-01.
3. **Are patient identifiers allowed in message text?** Because staff-to-staff
   messages are outside a verified patient connection, a person must decide
   whether free text may contain patient names, MRNs or other identifiers. A
   fail-closed default (no patient identifiers in staff-to-staff text) is
   recommended but is **not approved** until a person decides; the decision
   affects validation, audit and clinical-safety review.
4. **What is the attachment size limit?** A person must set the maximum
   attachment size (and any per-message total), consistent with the quarantine
   and storage controls of MSG-03 and ARC-06. Until decided, no limit is
   assumed in this pack.

## Decision owner and next step

Decision owner: a person (specification/product owner), not an agent. Next step:
answer the four questions, decide whether to accept this text as new V1
requirements, assign formal requirement IDs, and amend
`PROJECT_V1_SPEC.md` in a person-authored change. Implementation stories
must not start until that amendment exists.

## Evidence and review status

- Source of requirement text: `PROJECT_V1_SPEC.md` sections 6.1 and 7, and
  requirement IDs MSG-03, MSG-07 and ARC-07 (tool: file reads).
- No synthetic patient data, credentials or real identifiers are used in this
  pack; it contains no fixtures or code.
- Review status: advisory specialist review was completed by the
  software-architect and product-manager specialists (simulated reviewer
  evidence; see `.agent/pr-notes/SMSG-01-20261121-163355.md`). There is **no
  human, clinical or simulated-user acceptance** recorded here. A person's
  decision is required before any requirement becomes part of the specification.
