# Clinician task guide

- Requirements: PRD-01, UX-01
- Role in the staff web: `clinician`
- Screens you use: Patients, Patient overview, Longitudinal chart, Clinical note

These are the things you do most when you see a patient. Work in the correct
active organization; the chart and note belong to that organization. All names
and values in the product are synthetic.

## What you do most

1. **Find the patient.** Screen: **Patients**. Type at least two characters in
   "Search by name" and press "Search". You should see "Patient search results"
   with "Name", "MRN", "Issuer" and "Date of birth". Press the patient's name to
   open "Patient overview".

2. **Confirm the identity and safety context.** Screen: **Patient overview**.
   The "Identity" section shows "MRN", "Issuer", "Date of birth", "Language"
   and "Version". A possible match is flagged as "Likely duplicate records";
   review it before linking anything. Press "Open chart".

3. **Read the chart.** Screen: **Longitudinal chart** (heading "Patient chart
   summary"). Read the "Problems", "Allergies", "Medications" and "Vitals with
   units" cards. Press "Load more entries" if more history is available. The
   "Chart status" line confirms the chart you are viewing.

4. **Write the note.** Screen: **Clinical note**. Fill the four fields "Chief
   concern and history", "Examination and findings", "Assessment and diagnoses"
   and "Plan", then press "Save draft". You should see "Saving…" and then
   "Draft saved to the patient record." If the save fails you see "The draft
   could not be saved. Your changes are still here." and your text stays in the
   form.

5. **Sign the note.** Screen: **Clinical note**. Tick "I attest to this note and
   its patient context" and press "Sign draft". A "Sign clinical note" dialog
   explains: "Signing makes this revision read only and records your
   attestation." Press "Sign". You should see "Note signed. The revision is now
   read only." and the note becomes read only. If you still have unsaved text,
   you see "Save your changes before signing. Unsaved text is not signed."

6. **Correct the record.** Screen: **Clinical note**. To add to a signed note,
   press "Create amendment"; you should see "Amendment draft created from the
   signed revision." To mark a signed note as wrong, press "Mark entered in
   error", enter the "Reason for entered-in-error" and press "Mark entered in
   error" again in the dialog. You should see "Entered-in-error recorded. The
   prior revision is preserved." Signed revisions show "Signed revisions are
   read only" and stay in "Revision history".

7. **Manage chart documents and stale saves.** Screen: **Chart and document
   associations**. A document can be "Active" or "Voided"; "Void association"
   asks for a "Reason", and "Rotate view" changes the display rotation. If
   another clinician saved first, the app offers "Compare", "Reload" and "Copy
   my changes" under "Compare the saved revision with your changes"; choose one
   before saving again.

8. **Order and review results.** The "Orders", "Results" and "Critical results"
   screens, and the "New order" and "Prescribe" actions on the patient banner,
   are not in the code today. They are `PLANNED: READY-26` (orders),
   `PLANNED: BUILD-69` (results and critical queue) and `PLANNED: BUILD-45`
   (prescriptions). Use the steps above for the chart work that is available now.
