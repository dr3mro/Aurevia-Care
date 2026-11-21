# Staff messaging UAT scenario (MSG-07)

- Requirement: **MSG-07** (see the [staff messaging guide](README.md) for the
  rules this scenario exercises).
- Data: **synthetic only**. Every identifier below is fictional and stands for a
  test fixture; no real patient, staff member, facility or clinical payload is
  used.
- Nature: a **simulated** walkthrough for engineering and synthetic reviewers.
  It is not real clinical or patient acceptance. G5 real-patient sign-off remains
  a separate, authorized activity.

## Synthetic fixtures

| Fixture | Synthetic value | Purpose |
|---|---|---|
| Organization | `SYN-ORG-ALPHA` | Tenant under test |
| Facility A / Unit | `SYN-FAC-01` / `SYN-UNIT-CARD` | Originating care unit |
| Facility B / Unit | `SYN-FAC-02` / `SYN-UNIT-RAD` | Second, separate care unit |
| Verified patient chart | `SYN-PAT-0001` | Fictional patient reference; no demographics |
| Staff actors | `SYN-STF-NURSE` (`unit_staff`), `SYN-STF-DOC` (`clinician`), `SYN-STF-ADMIN` (`tenant_admin`), `SYN-STF-PRIV` (`privacy_auditor`) | Role-scoped actions |
| Materials | `prep-sheet.pdf`, `photo-1.png` | Synthetic PDF and image attachments |

## Scenario 1 — Coordinate care, file with provenance, and verify governance

Run the steps in order. Each step records the expected outcome and the audit fact
that should appear. Steps marked **NEGATIVE** must be refused.

1. **Confirm the service profile is shown.** As `SYN-STF-NURSE`, open
   `SYN-UNIT-CARD`'s published profile.
   Expected: approved and effective profile lists supported topics, service
   hours, response target, queue owner, backup coverage and the urgent and
   downtime channels. No message is sent in this step.
2. **Open a conversation inside the facility.** `SYN-STF-NURSE` opens the
   conversation linked to verified chart `SYN-PAT-0001` in `SYN-UNIT-CARD`.
   Expected: the conversation opens within the unit; fact `open` is recorded with
   the correct subject, unit and correlation identifier.
3. **View and send an operational message.** `SYN-STF-NURSE` views the thread,
   then sends a short operational coordination message (title, text, language,
   author, unit and issue time).
   Expected: recipient and pending content are shown before an explicit send;
   facts `view` then `send` are recorded. A generic push notice contains no
   clinical detail.
4. **Send a clinical instruction as an authorized clinician.** `SYN-STF-DOC`
   sends a typed clinical instruction within professional scope, using the
   configured clinician authorization.
   Expected: the instruction is accepted and fact `send` records it with the
   clinician actor and revision.
5. **NEGATIVE — unauthorized clinical instruction.** `SYN-STF-NURSE`
   (`unit_staff`, no clinician authorization) attempts to issue a clinical
   instruction.
   Expected: refused because issuing a clinical instruction requires the
   configured clinician authorization; no `send` fact is produced. The nurse may
   still send ordinary coordination messages (step 3).
6. **Attach a permitted file.** `SYN-STF-NURSE` adds `prep-sheet.pdf`, sees the
   recipient and the pending file, and explicitly sends.
   Expected: integrity, size and type checks pass, the file is quarantined and
   gets a clean verdict, and only then becomes downloadable; original, checksum
   and provenance are preserved. Fact `send` records the publication; a later
   download records `download`.
7. **NEGATIVE — unsupported or failed attachment.** The sender tries to attach
   an executable and, separately, a file whose scan fails.
   Expected: both are refused before send with a visible reason and a removal or
   replacement option; no `send` fact is produced for either attempt.
8. **NEGATIVE — secrets or bulk export as an attachment.** The sender tries to
   attach a credentials file and, separately, a bulk patient-data export.
   Expected: both are refused before send; no `send` fact is produced and no
   bulk data or secret leaves the unit through messaging.
9. **Reference the patient and file with provenance.** `SYN-STF-DOC` links the
   instruction to chart `SYN-PAT-0001`, recording source type, stable reference
   and immutable revision.
   Expected: the link is stored with the authorizing actor and link instant; fact
   `chart_link` records provenance and fact `file` records the filing. No
   diagnosis, message body or attachment bytes appear in the audit trail.
10. **Correct an issued instruction.** `SYN-STF-DOC` issues a correction.
    Expected: a linked replacement supersedes the earlier revision and the patient
    is notified; fact `correct` is recorded and the prior revision is still
    traceable.
11. **NEGATIVE — wrong patient or wrong unit reference.** The sender tries to
    reference a chart from another tenant and then a conversation belonging to
    `SYN-UNIT-RAD` while acting in `SYN-UNIT-CARD`.
    Expected: the cross-tenant reference is concealed and the wrong-unit access
    is denied; facts with `not_found` and `denied` outcomes are recorded. No
    clinical content is disclosed.
12. **Assign and restrict.** `SYN-STF-ADMIN` assigns the work item to a named
    owner and then restricts access to one file.
    Expected: both commands succeed under `tenant_admin`; facts `assign` and
    `restrict` are recorded with actor and resource. A conversation role acting
    here is denied.
13. **NEGATIVE — emergency use by staff.** A tester proposes sending a
    time-critical emergency notification solely as a secure message.
    Expected: the guide's rule is followed — the tester uses the published urgent
    or downtime channel instead; no emergency item is left waiting on a message
    reply, and critical-result acknowledgment is not performed by message
    acknowledgment.
14. **NEGATIVE — inbound emergency handled by reply.** A synthetic patient
    message reports acute, time-critical symptoms and a tester proposes answering
    it only with a message reply.
    Expected: the report is escalated immediately through the published
    urgent/on-call channel or emergency procedure; it is not managed by message
    reply and no message acknowledgment is treated as a clinical response.
15. **Between facilities.** `SYN-STF-NURSE` needs input from `SYN-UNIT-RAD` at
    `SYN-FAC-02`.
    Expected: a new, separately authorized conversation with explicit disclosure
    is used; no conversation, patient reference or attachment is forwarded across
    units, and no directory lookup is used to contact the patient.
16. **Acknowledge a revision.** The synthetic patient acknowledges the exact
    corrected revision.
    Expected: fact `acknowledge` binds to that revision; the acknowledgment means
    receipt only, not consent or task completion.
17. **Place a legal hold and attempt retention.** `SYN-STF-PRIV` places a hold on
    the filed resource, then a retention run is attempted against it.
    Expected: `hold_place` is recorded; the held resource is **not** removed by
    retention. Releasing the hold requires a named, reasoned authority
    (`hold_release`) and preserves the original reason. Sender and recipient
    retention duties both remain recorded; a hold on one does not waive the
    other.
18. **NEGATIVE — attachment access after revocation.** The connection to
    `SYN-PAT-0001` is revoked; a subsequent attempt to send a message and to
    download an attachment through that connection is made.
    Expected: new messaging and future attachment access are refused; the
    recorded revocation effect shows messaging and attachment access blocked and
    states whether prior conversations remain readable under the verified
    patient's record-access policy, including the policy version used. Prior
    conversations are neither silently published nor silently destroyed.
19. **NEGATIVE — closed, read-only conversation.** A tester tries to attach a
    file to, or send a message in, a closed conversation.
    Expected: both are refused because closed threads are read-only; no `send`
    fact is produced.
20. **Copy request.** `SYN-STF-PRIV` routes a request for a copy of the resource
    to the site's record-access procedure.
    Expected: fact `copy_request` records the actor, purpose and resource; the
    feature does not itself disclose the record and does not suspend either
    party's retention duty.
21. **Audit review.** `SYN-STF-PRIV` reads the audit trail.
    Expected: every expected fact above is present with the correct action,
    actor, unit, correlation identifier, timestamp and outcome, including the
    `denied`, `not_found` and `conflict` failures; reading the trail itself adds
    an `audit_read` fact. No clinical payload is present in any fact.

## Pass or fail criteria

- Steps 1–4, 6, 9, 10, 12, 15–17, 20 and 21 produce the stated result and audit
  fact.
- Every **NEGATIVE** step (5, 7, 8, 11, 13, 14, 18 and 19) is refused with the
  stated outcome and no unauthorized disclosure or unauthorized message
  publication.
- The audit trail contains no message body, attachment bytes, diagnosis or real
  identifier, and reading it is itself audited.
- Any unexpected disclosure, missing refusal or missing audit fact is a fail and
  is recorded as a defect with the fixture and step number.

## Evidence to record

Record, using synthetic fixtures only: the step number, the actor and role, the
unit and correlation identifier, the observed outcome and the audit fact
identifier and action. Attach sanitized screen captures from the synthetic
environment. Do not paste real clinical data, credentials or real identifiers
into the evidence.

## Not covered here

Real urgent and downtime channel values, actual site role mapping, real retention
window approvals, real record-access decisions and human clinical acceptance are
outside this simulated scenario and remain site/G5 responsibilities.
