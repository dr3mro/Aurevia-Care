# Screens, navigation and layout

- Requirements: UX-01, WEB-07, WEB-08, WEB-09, PRD-05
- Status: contract version 1.0.0, proposed. Documentation only.
- Audience: maintainers of `apps/staff-web` and `apps/patient-mobile`, and the reviewers of any change that adds a screen.

This file completes the design contract. The [token contract](token-contract.md) names the visual values, the [accessibility patterns](accessibility-patterns.md) say how each control behaves, and the [right-to-left and localization rules](rtl-and-localization.md) cover Arabic. This file says which screens exist, how a person moves between them and what every screen must show in each state.

## 1. Staff web application shell

Every authenticated page uses one shell.

| Region | Content | Rules |
|---|---|---|
| Header | Product name, organization switcher, facility selector, language toggle (English or Arabic), user menu with sessions and sign out | Always visible. The active organization is always shown. Switching organization reloads data and clears any cached patient data. |
| Navigation | Left rail, grouped as in section 2 | Items the signed-in role cannot use are not rendered. The rail collapses to icons at widths from 768 to 1279 px. In right-to-left mode the rail is on the right. |
| Page header | Breadcrumb, page title, primary action | One primary action per page. |
| Content | The screen | Maximum reading width 1200 px for forms and notes; tables use the full width. |
| Banner area | Session expiring, offline, read-only (stale version), maintenance | Above the content, announced to screen readers, never covers the header. |

Supported widths follow WEB-07: full layout at 1280 px and above, compact rail from 768 to 1279 px. Below 768 px the staff web shows a plain "use a larger screen" notice and no data.

## 2. Staff web screen map

Routes are stable and never contain patient names, identifiers that reveal identity, or free text. A patient is addressed by the opaque ID only.

| Group | Route | Screen | Roles | Main actions | Requirements |
|---|---|---|---|---|---|
| Today | `/` | Role-aware home: worklist for reception and clinicians, queues for operators | all staff | open next item | CLN-01, OPS-05 |
| Scheduling | `/schedule` | Day and week calendar by clinician or room | reception, clinician | book, move, cancel, hold | CLN-01 |
| Scheduling | `/schedule/book` | Booking form with conflict feedback | reception | confirm booking | CLN-01 |
| Clinic | `/checkin` | Check-in worklist with identity verification | reception | verify identity, check in | CLN-02 |
| Patients | `/patients` | Search and register, likely-duplicate warnings | reception, clinician | register, open chart | PRD-01, IAM-03, IAM-04 |
| Patients | `/patients/:id` | Chart overview | clinician | start note, view documents | CLN-03 |
| Patients | `/patients/:id/chart` | Longitudinal chart with revision history | clinician | open note, add addendum | CLN-03, CLN-04 |
| Patients | `/patients/:id/notes/:noteId` | Note editor with sign and amend | clinician | save draft, sign, amend, enter in error | CLN-04, CLN-05 |
| Orders | `/orders` | Order list and inbox | clinician, lab, radiology | create, accept, reject | XCH-01 |
| Orders | `/orders/:id` | Order detail with task states | clinician, lab, radiology | accept, respond | XCH-02, XCH-03 |
| Results | `/results` | Result inbox and unmatched reports | clinician, operator | match, review, release | XCH-04 |
| Results | `/results/critical` | Critical-result queue with acknowledgment timers | clinician | acknowledge, escalate | XCH-05 |
| Specialty | `/lab/accessions` | Specimen accession and receipt | lab | accession, reject, recollect | LAB-01 |
| Specialty | `/radiology` | Imaging orders and reports | radiology | report, addend | RAD-01 |
| Specialty | `/therapy` | Therapy episodes | therapist | plan, discharge | PT-01 |
| Specialty | `/pharmacy` | Prescriptions and transmission state | clinician, pharmacy | prescribe, reconcile | RX-01, RX-02 |
| Messages | `/messages` | Unit inbox for patient conversations | unit staff | reply, assign, close | MSG-01 |
| Finance | `/finance` | Invoices, payments, receipts | finance | invoice, take payment, refund | FIN-01 |
| Administration | `/admin/facilities` | Facilities and resources | tenant admin | create, edit | IAM-01 |
| Administration | `/admin/staff` | Staff lifecycle and role history | tenant admin | invite, deactivate, change role | IAM-02 |
| Administration | `/admin/config` | Catalogs and policies with approval and version history | tenant admin | define, approve | ADM-01 |
| Administration | `/admin/audit` | Audit event search | privacy auditor, tenant admin | filter, export | OPS-02 |
| Operations | `/operations/queues` | Operational queues | operator | assign, retry, escalate with reason | OPS-05 |
| Account | `/account/sessions` | Active sessions | all staff | revoke | WEB-03 |
| System | `/signed-out`, `/session-expired`, `/forbidden`, `/not-found`, `/unsupported-browser` | Fixed pages with no patient data | anyone | sign in again | WEB-03, WEB-07 |

## 3. Patient app

Four tabs, always in this order, mirrored in right-to-left mode.

| Tab | Screens | Requirements |
|---|---|---|
| Appointments | list with facility time zone, detail, confirm | PAT-01 |
| Reports | released reports with revision and correction context, download with confirmation | PAT-02 |
| Messages | units, conversation list, conversation, attachments, instructions with acknowledgment | MSG-01, MSG-02 |
| Account | enrollment and recovery, notification preferences, linked devices, support and accessibility help | PAT-03 |

Push notifications show generic text only. Details load after authentication.

## 4. Required states for every screen

Every screen implements all of the following. A screen that omits one is not complete (WEB-09).

| State | What the person sees | Notes |
|---|---|---|
| Loading | Skeleton of the final layout, no spinner-only pages | Announced politely to assistive technology. |
| Empty | Plain explanation and the next action | Never blank. |
| Error | What failed, whether to retry, the correlation ID | No technical detail, no other tenant's data. |
| Timeout or offline | Retry control, unsaved input kept | Requests time out after 15 seconds. |
| Forbidden | Plain statement that the role cannot do this | The item is not rendered earlier when avoidable. |
| Conflict | Stale-version message with compare, reload and copy | Typed work is never discarded. |
| Session expired | Sign-in prompt that returns to the same place | Draft text is kept in memory only, never in browser storage. |

## 5. Layout, density and patterns

- **Spacing and type** come only from the [token contract](token-contract.md). A screen never sets a raw colour, size or font.
- **Tables** use cursor pagination with stable ordering, a sticky header, sortable columns only where the server supports sorting, and row actions in the last column. Clinical status is shown with text as well as colour.
- **Forms** validate on submit and on leaving a field, show an error summary at the top that links to each field, and keep one primary button. A form with changes asks before leaving.
- **Destructive or irreversible actions** (sign, release, refund, deactivate) use a confirmation that names the record and the effect, and ask for a reason where the spec requires one.
- **Time** is shown in the facility time zone with the UTC instant on hover or focus. Civil dates stay as entered.
- **Identity** shows the patient name and the opaque local identifier together on every clinical page, and the organization name in the header.
- **Printing** hides navigation, shows the organization, patient identifiers, print time and the person who printed.
- **Language** switches without reloading the page and keeps the person's place. Mixed-script text follows the [right-to-left rules](rtl-and-localization.md).

## 6. Review checklist for a new screen

1. The route is in the map above, or the map is updated in the same change.
2. All seven states in section 4 have a component test.
3. Keyboard-only use, focus order and labels follow the [accessibility patterns](accessibility-patterns.md), with a WCAG 2.2 AA scan that reports no serious violations.
4. The screen works in English and Arabic, light and dark.
5. The route chunk stays within the 250 KB gzip budget (WEB-08).
6. No patient data appears in the URL, the title or browser storage.
