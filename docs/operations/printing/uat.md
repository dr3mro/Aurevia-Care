# User acceptance scenario — clinician printing

- Requirements: CLN-07, RX-02, LAB-01
- Audience: QA, the product owner and clinical reviewers.
- Companion documents: [printing guide](README.md), [permission matrix](permissions.md).

This is a **simulated** walkthrough for a clinician using synthetic data only.
It is a reproducible acceptance script, not a record that a person or a real
patient took part. Real clinical and patient acceptance remain a G5 gate.
Run it in English and repeat the relevant steps in Arabic.

## Preconditions (synthetic fixtures)

- A test organization with two facilities, Facility A and Facility B.
- A synthetic clinician signed in with the print permission, a care
  relationship to the test patient and privileges at Facility A.
- A synthetic test patient linked to the chart, with an issuer-scoped MRN.
  No real person, identifier or document is used.
- A synthetic draft prescription, a signed prescription with an approved
  printable channel, a placed lab order and a placed imaging order, a signed
  report, and a chart with one signed note, one unsigned draft note and one
  withheld result.
- A second synthetic organization holding a record with an identical local id
  to prove tenant isolation.
- A readable audit log and a store of generated handout versions.

## Steps

1. Sign in as the clinician and set the active organization and Facility A.
   Confirm the session is the clinician's and that the patient in scope is the
   synthetic test patient.

2. Open the **draft** prescription and try to print. The Print action is
   disabled with a plain reason such as "the prescription is not signed". No
   handout, version or download handle is created.

3. Sign the prescription (or open the prepared signed one) and confirm the
   channel is approved. Open it again. A Print button is now available.

4. Select Print. The preview dialog opens and shows the patient's name and
   identifier, the clinician, the facility, the issue time, the medication and
   instructions, and a provenance footer. Confirm the layout is A4 with page
   numbers and that the language choice offers English and Arabic.

5. Choose Arabic. Confirm the text is right-to-left, the Arabic glyphs render
   (embedded font), and the page numbers and footer still appear. Switch back
   to English.

6. Try to print without choosing a reason. Printing is blocked and the message
   asks for a reason. Choose **Give to patient**. Confirm the reason is now
   recorded for this print.

7. Print the patient copy. A handout is produced. If the approved channel needs
   a pharmacy copy, confirm a second, clearly marked pharmacy copy is offered
   and that its text does not claim electronic delivery or pharmacy acceptance.

8. Inspect the audit log for this action. Confirm exactly one entry with the
   clinician actor, the purpose "give to patient", the action "print", the
   prescription kind, the source revision and handout version as identifiers,
   Facility A, the server time and the correlation id. Confirm the entry has
   no clinical content, patient name, MRN, file name, URL or object key.

9. Reprint the **unchanged** prescription with reason "patient request".
   Confirm the download reuses the same handout version, is marked as a reprint
   and writes its own audit entry with the new reason.

10. Change the prescription so the source revision changes, then print again.
    Confirm a **new** handout version is produced, the previous version still
    exists unchanged, and the patient's handout list marks the old one as
    **Corrected** with the correction context.

11. Open the placed **lab order**. Print the requisition sheet. Confirm it shows
    the tests, clinical question, priority, preparation instructions (for
    example fasting), where to go and the issuer-scoped order/accession number
    in human-readable form. Confirm a draft order cannot print (repeat step 2
    on a draft order).

12. Open the placed **imaging order** and print its sheet. Confirm the study,
    clinical question, priority, preparation, where to go and order identifier
    are present, and that this is a requisition sheet only, not access to
    partner-held images.

13. Open the **chart** and request a chart summary for the visit. Deselect a
    section and re-preview. Confirm the preview lists what was left out and
    why, that the **unsigned draft note** is absent, and that the **withheld
    result** is absent and named as withheld. Confirm the section choice is
    reflected in the preview and recorded with the version.

14. Open the **signed report** from its screen and print it through the same
    preview. Confirm the signed content and provenance appear and that no
    unsigned draft variant can be printed.

15. Try to print a record that belongs to **Facility B** or outside the care
    relationship. Confirm the request is forbidden with a plain message.

16. Try to open or print a record from the **second organization** with the
    identical local id. Confirm it is concealed as not found, with no content
    or existence revealed.

17. Sign out and sign in as a role with **no print permission** (for example
    reception, from the [permission matrix](permissions.md)). Confirm the Print
    actions are unavailable and a direct print request is forbidden and
    audited.

18. Sign in as the patient for the test chart. Confirm only the clinician's
    **released** handouts are visible, the replaced one is marked Corrected,
    and each download is checked again and audited.

## Pass criteria

| Area | Expected result |
|---|---|
| Source state | Only signed or attested prescriptions, placed orders and signed content print; drafts are refused with a reason |
| Withheld and unsigned content | Never present in a handout; listed as left out with a reason in the chart-summary preview |
| Output | A4, page numbers, patient/clinician/facility/issue time, provenance footer, English and right-to-left Arabic with embedded fonts |
| Reason | Required before Print; one of give to patient, referral or patient request |
| Versions | Unchanged source reuses the version; changed source makes a new one; old version marked Corrected to the patient |
| Authorization | Allowed roles only, facility of the record, care relationship, next-request revocation; cross-tenant concealed; forbidden roles denied |
| Audit | One sanitized, append-only entry per print, reprint, download and refusal; identifiers and purpose only, never content |

## Evidence to capture

For each pass, record the synthetic fixture names, the handout kind, the
language, the chosen reason, the source revision id and handout version, the
relevant audit entry identifiers, and the before/after of any replaced version.
Record failures as defects with the exact step and expected-versus-observed
result. State clearly in the evidence that this was a simulated clinician
scenario on synthetic data; a real clinician and patient acceptance session is
a separate G5 activity.

The authoritative clauses are in the
[product specification](../../../.opencode/config/PROJECT_V1_SPEC.md). The authorization
decisions expected at steps 2, 15, 16 and 17 are defined in the
[permission matrix](permissions.md).
