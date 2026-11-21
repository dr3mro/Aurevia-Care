# Radiology task guide

- Requirements: PRD-01, UX-01
- Role in the staff web: `radiology`
- Planned screens: Orders, Radiology

No radiology screen exists in the code today. The steps below describe the work
this role will do. Each step is marked `PLANNED:` with the id of the task that
will add the screen, and the wording comes from the approved design and the
task details, not from a running screen, so button names may change when the
screen is built. If you follow the "Radiology" link in the portal now, you see
"Page not found" with "The page you requested does not exist. Go home to
continue." and a "Go home" button. Nothing is loaded or shown.

## What you will do most

1. `PLANNED: READY-26` — **See imaging orders.** Screen: **Orders**. Filter the
   order list by status and open a row for **Order detail**. You should see the
   order fields and the task state timeline for the work you can act on.

2. `PLANNED: BUILD-46` — **Schedule a study.** Screen: **Radiology**. From the
   order, set the schedule step with an accession and a modality. You should
   see the study linked to the order and the scheduled time.

3. `PLANNED: BUILD-46` — **Link the study to the right patient.** Screen:
   **Radiology**. Check the patient and accession before saving the link. If
   they do not match, the mismatch is shown and nothing is saved.

4. `PLANNED: BUILD-46` — **Sign an interpretation.** Screen: **Radiology**.
   Write the interpretation and sign it. You should see a signed report that the
   referrer can view. Corrections are added as addenda; a signed report is not
   edited in place.

5. `PLANNED: BUILD-69` — **Follow the result.** Screen: **Results**. Review the
   report for your order and acknowledge it when it needs action. You should
   see the review state change while the delivery state stays separate.

6. `PLANNED: READY-26` — **Ask for clarification.** Screen: **Order detail**.
   Press clarify and give a reason when the order is not clear. You should see
   the reason recorded in the timeline and the task state updated.

Use the [Clinician](clinician.md) guide for chart work that is available today.
