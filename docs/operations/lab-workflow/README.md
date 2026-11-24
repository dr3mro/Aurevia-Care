# Laboratory workflow guide: order, collect, reject, enter, verify, release and correct

- Requirements: LAB-01, LAB-02, WEB-09
- Audience: laboratory collectors and verifiers, the clinicians who order and
  read laboratory tests, and the staff who support the service.
- Status: this is the operational guide for the laboratory screens and the
  synthetic browser journey that exercises them. It explains what each role does
  and what the screen shows. It is **not** clinical approval, a partner contract
  or permission to enable patient release. Market approval, site policy and real
  clinical acceptance remain separate G5 gates in the specification.
- All names, accessions, subjects and reasons below are synthetic. No real
  patient, credential or identifier is used in the guide or the browser test.

The approved clauses are in the [product specification](../../../.opencode/config/PROJECT_V1_SPEC.md)
(LAB-01 and LAB-02 in section 5; WEB-09 in section 6). The executable journey is
[`apps/staff-web/e2e/labflow/lab-journey.spec.ts`](../../../apps/staff-web/e2e/labflow/lab-journey.spec.ts);
its deterministic API stand-in is in the same folder.

## Who does what

| Role | What the journey calls them | What they may do |
|---|---|---|
| Laboratory user 1 | the collector / enterer | find the order, check the patient at the bedside, collect or reject a specimen, key the observations |
| Laboratory user 2 | the independent verifier | check the entered observations and verify them; this must be a different person from the enterer |
| Clinician | the ordering clinician and report reader | places the order, is notified when a specimen is rejected, and reads the final or corrected report |
| Patient | the report subject | sees a released report only while it is final or corrected; sees nothing while it is withdrawn |

Two different laboratory users are required for entry and verification. The
screen shows both subjects ("Entered by …" and "Verifying as …") so that the
separation is visible. A person cannot verify a report they entered.

## The journey, step by step

### 1. Order and find the requisition

The clinician places a laboratory order from the patient chart. The order carries
an issuer-scoped accession: a number plus the issuer that allocated it. A number
alone is not an identity — `ACC-100` from one issuer and `ACC-100` from another
are different requisitions.

The collector opens the **Specimen collection desk** (`/lab/accessions`) and finds
the order by accession number or by patient. The result list shows the accession
and the patient's name in both the Latin form and the local script.

- **Clinician sees:** the order on the chart and its source order reference.
- **Patient sees:** nothing yet; an order is not a result.

### 2. Check identity at the bedside

Before collecting, the collector reads three things from the wristband and tube:
the patient identifier, the accession number and the accession issuer. The screen
shows both recorded patient names and the issuer-scoped accession, then asks the
collector to type the three presented values and confirm the name and accession
checks.

If any value differs, the screen says **"Identity mismatch — collection is
blocked"** and names exactly which identifier to recheck. The collect and reject
buttons stay disabled and nothing is written. Do not continue, and do not type
around the mismatch. Correct the specimen or the requisition first.

- **Clinician sees:** no change while the check fails.
- **Patient sees:** nothing.

### 3. Collect the specimen and print the label

With a match, the collector records the specimen type, collection site, collector
subject, collection time in UTC and specimen quality, then chooses **Record
collection**. The screen confirms "Collection recorded." A collected specimen can
have its **Specimen label sheet** printed; the label repeats the accession,
issuer, patient identifiers, specimen type and collection time. A blank or
uncollected specimen never produces a label.

- **Clinician sees:** the order move to Collected on the turnaround board.
- **Patient sees:** nothing; collection is not a result.

### 4. Reject a poor specimen

If the specimen is not usable (for example haemolysed, the wrong tube, unlabelled
or insufficient volume), the collector selects a reason from the five allowed
codes and, for "other", writes the detail. A rejection with no reason, or an
"other" with no detail, is blocked. The collector may ask that the patient be
notified where policy allows; the site decides whether that notification is
actually sent.

The reason is retained with the specimen and the ordering clinician is recorded
as the notification target. The specimen's history keeps the rejection event.

- **Clinician sees:** the rejection and its reason, and the notification target.
- **Patient sees:** a notification only where site policy permits it.

### 5. Recollect with a linked specimen

A recollection mints a **new specimen identifier** linked to the rejected one
(through `replaces_accession_id`) and reuses the same patient and source order.
The rejected specimen keeps its own history; both specimens stay on one order.
The collector can find the new accession, sees its state as **Recollected**, and
proceeds with the collection steps again.

The browser journey currently drives the recollect command through the same
browser endpoint the API provides, because the collection desk does not yet have
a recollect button. That gap is recorded in the guide's limitations below.

- **Clinician sees:** both specimens under the one order, with the new one linked.
- **Patient sees:** no result until one is released.

### 6. Enter and verify the observations

Laboratory user 1 opens **Laboratory result entry and verification**
(`/lab/results/entry`) and keys the observations. Each row shows its unit, the
approved site reference range, an abnormal flag as a word and a distinct shape,
the previous value with its date, and a worded warning when the change exceeds
the configured limit. Colour is never the only signal. A critical result adds a
persistent instruction to notify the responsible clinician and record read-back.

Laboratory user 2, a different person, reviews "What will be released" and
chooses **Verify and prepare release**. The screen refuses if the verifier is the
same subject as the enterer. A rejected or edited verification keeps every typed
value and clears any earlier success message, so the screen never claims that
stale values were checked.

- **Clinician sees:** a report that is verified and ready for release.
- **Patient sees:** nothing until release.

### 7. Release

Release issues the report as a **final** revision. The **Release status** panel
states the report state and, separately, what the clinician and the patient can
see. A final report is released to both. The patient's access still follows the
facility's release policy (immediate, delayed or held for review); the panel shows
the current effect, not a promise of timing.

- **Clinician sees:** the final report.
- **Patient sees:** the released final report with its revision, issuing
  organization and effective date, when policy allows.

### 8. Correct a released report

If a released report is wrong, a supervisor opens **Laboratory report release and
corrections** (`/lab/release`). The screen shows an old-versus-new revision
comparison: each observation is marked changed, unchanged, added or removed, and
a value missing from one revision is stated in words rather than left blank.
To record the correction the supervisor types **what changed** and a **reason**;
the button stays disabled until the reason is present, and typed work is never
discarded.

A correction appends a new **corrected** revision and keeps the earlier one in
history. It does not silently edit the original. Clinicians and patients who saw
the earlier revision are sent a correction notice. Both audiences continue to see
the corrected report.

- **Clinician sees:** the corrected report and the comparison with the original.
- **Patient sees:** the corrected revision with its correction context.

### 9. Withdraw an entered-in-error report

If the report should never have been released, the supervisor records a
**withdrawal** with a mandatory reason. The screen explains the consequence
first: the report is removed from both clinician and patient view and marked
entered-in-error. A persistent banner shows who withdrew it, why and when, and
the earlier revisions remain in history.

- **Clinician sees:** the withdrawal banner; the report is no longer visible.
- **Patient sees:** nothing — the report is withheld.

### 10. Watch the turnaround board

The **Laboratory turnaround board** (`/lab/turnaround-board`) shows five columns:
Ordered, Collected, In analysis, Awaiting verification and Released. Each card
shows its age against its target and says **Overdue by … minutes** or **On track**
in words as well as colour. A partner-interface outage shows a persistent alert
with the approved manual-route link (see below).

## Outage route

When the partner result interface is down, the board shows a **Partner interface
down** alert. Use the **Open the manual route** link, which leads to the locally
approved manual procedure printed on the same page:

1. Print the locally approved request form.
2. Send it through the approved channel.
3. Record delivery and verification evidence against the order.

Manual keying of results is a distinct, audited path: it attaches the original
report and the key observations to the order, repeats the accession/patient
match, and places an unmatched entry in a named queue when the identity cannot be
matched. A manual workflow must state which evidence is *delivery* evidence and
which is *verification* evidence; neither is implied by the other. Do not claim
an electronic partner accepted a result that was handled manually. The electronic
label and the manual evidence stay separate.

## Correction rules (plain language)

- A correction is a **new revision**, never an edit in place. The original stays
  readable in history.
- A reason is mandatory. "What changed" plus "why" are both recorded with the
  actor and the time.
- Verification is tied to a revision. After a correction the new revision must be
  verified again by an independent user before it is treated as final.
- A corrected report remains visible to clinician and patient; a correction
  notice reaches those who saw the earlier revision.
- Entered-in-error is **withdrawal**, not correction. It needs its own reason,
  hides the report from both audiences, and is announced with a persistent
  banner. It cannot be quietly undone by reissuing the same revision.
- Only authorized users may correct or withdraw. The client shows the state and
  collects the reason; the source system remains the authority for identity,
  release policy, authorization and the audit record.

## What the browser journey proves — and what it does not

The Playwright journey opens the real staff-web routes, clicks the real controls
and asserts what the collector, verifier and clinician see at each step. It uses
a deterministic synthetic API stand-in for `/api/v1/lab/**`, so it needs no live
partner, patient record or credential. It also asserts the failure paths: a
mismatched identity blocks collection, an empty rejection reason blocks the
command, and withdrawal hides the report from both audiences.

The results and release screens are currently presentational with synthetic
fixtures, and the collection desk has no recollect button yet. The journey
therefore represents "two users" by separate browser contexts and by the
distinct subject labels on screen, and it mints the linked recollection through
the browser endpoint. It does not prove a shared server-side state across those
contexts; that continuity belongs to the API and database stories and their
integration tests. No real clinician or patient participated in this review.
