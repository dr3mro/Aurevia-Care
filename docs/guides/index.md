# Role task guides

Plain-language guides for the people who use the product. Each guide lists the
tasks a role does most, in the order a new person would learn them. Every step
names the screen and the exact button or field label shown in the interface,
then says what you should see when the step worked.

- Requirements: PRD-01, UX-01
- Audience: staff and patients learning the product, and the people who support them
- Source of truth: the screens in `apps/staff-web/src/features` and
  `apps/patient-mobile/src`. If a label here disagrees with the screen, the screen
  wins.

## Guides

- [Reception](reception.md) — book appointments, run the appointment worklist,
  check patients in and register new patients.
- [Clinician](clinician.md) — find a patient, read the chart, write and sign a
  note, and correct the record.
- [Laboratory](laboratory.md) — receive orders, manage accessions and follow
  results. These screens are not in the code yet.
- [Radiology](radiology.md) — manage imaging orders, schedule studies and
  sign interpretations. These screens are not in the code yet.
- [Finance](finance.md) — issue invoices, take payments, refund and void.
  These screens are not in the code yet.
- [Administrator](administrator.md) — manage facilities and staff, set
  configuration and catalogs, and review the audit trail.
- [Patient](patient.md) — use the mobile app to see appointments and reports,
  message a care unit, review attachments and acknowledge instructions.

## How to read a step

- A **Screen** is the name shown by the app. Quoted words, such as "Check in",
  are the exact English button, field or badge labels. Arabic uses the
  equivalent strings from the message files; the layout mirrors for Arabic.
- **You should see** is the visible result of a successful step. A message in
  quotes is the exact status text the app shows.
- Names in the product are synthetic. No real patient data appears in these
  guides.
- A step marked `PLANNED: <task id>` names work listed in `TODO.md` that is not
  in the code today. Do not look for that screen yet; the task id tells you
  which story will add it. Button and field names for a planned screen are
  taken from the approved design, not from running code, and may change when
  the screen is built.
