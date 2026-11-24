# Imaging workflow: from order to acknowledged addendum

- Requirements: RAD-01, WEB-09.
- Audience: the referring clinician, radiology staff at the performing site, the
  interpreting radiologist, and the staff who support or audit this workflow.
- All names and identifiers here are synthetic. No real patient appears in this
  guide or in the [browser journey](#verify-the-journey).

This guide describes one imaging request from the clinician's order to the
referrer's acknowledgment of a later addendum. It says who does what, what the
screen shows and what the system records. It is an operational description, not
clinical approval: the partner, the workstation and the site's clinical policy
are approved separately.

## What is happening, in one paragraph

A clinician places an imaging order and says how urgent it is and what question
the study should answer. The performing site receives the order on its worklist,
schedules it with an approved modality and an accession number, then links the
external study from the partner's diagnostic workstation. Before linking, the
site compares the patient label and the accession with the order; if either is
wrong, the link is refused and nothing is saved. After the study is performed,
the radiologist signs an interpretation. The signed report goes back to the
referring clinician. If the radiologist later adds an addendum, it arrives as a
new revision with a notice that stays open until the referrer acknowledges that
exact revision.

## Requirement mapping

| Requirement | What it guarantees | Where it appears below |
|---|---|---|
| RAD-01 | Imaging order with urgency and clinical question, scheduling, accession and modality, patient/accession verification before the study is linked, a signed interpretation with its sections, and linked addenda | [Steps 1 to 6](#the-steps) |
| WEB-09 | Each screen states loading, empty, error, forbidden, conflict and session-expired states, keeps typed work on a conflict, and works in English and right-to-left Arabic | [Screen states](#screen-states-and-recovery) |

The approved wording is in the
[product specification](../../../.opencode/config/PROJECT_V1_SPEC.md). This guide introduces
no new requirement or threshold.

## The steps

### 1. The clinician places the imaging order

From the patient's chart, the clinician chooses the imaging study and records
the urgency and the clinical question. The question is required: the performing
site needs to know what the study is meant to answer. The clinician reviews the
order before placing it; once placed, it is fixed and can only change through a
formal amendment. See the
[clinician orders guide](../clinician-orders/README.md) for order entry and
sending.

The order arrives at the performing site in the **Received** state. In the
synthetic journey the order is `ORD-001` for patient `synthetic-patient-001`.

### 2. The site finds the order on its worklist

Radiology staff open the **Imaging worklist** from the Radiology rail item. The
list shows only orders for the active facility and the selected performing
site. A row from another site is never shown. In the synthetic journey the
performing site is `site-rad-main`, and an order for `site-rad-other` is
deliberately left out.

Each row shows the patient, order code, urgency, scheduled time, status,
modality and accession. Values that have not been recorded yet are shown as
"Not recorded". Filters let the staff look at all orders or at one status.

### 3. The site schedules the study

Open the order and record the scheduled time in UTC, choose an approved modality
(CT, MRI, X-ray, Ultrasound or PET) and enter the accession with its issuer. The
accession identifies the imaging request at the performing site; for the
synthetic order the values are `ACC-001` and `SYN-ORG`. Nothing is sent until all
fields are present. The order moves to **Scheduled** and the study-link form
becomes available.

If the server has changed the order in the meantime, the screen shows a
stale-version conflict. The text you typed is not thrown away: you can compare
it with the server version, reload or copy it before you decide what to do.

### 4. The site links the external study — and refuses a wrong patient

Read the patient label and the accession from the partner's diagnostic
workstation and enter them on the **Link the external study** form. The screen
compares them with the open order as you type and says the result in words:

- both match — "Patient and accession match this order; the study can be
  linked.";
- patient is wrong — "Patient label does not match this order. Linking is
  blocked.";
- accession is wrong — "Accession label does not match this order. Linking is
  blocked.";
- both are wrong — "Patient and accession labels do not match this order.
  Linking is blocked."

On any mismatch the **Link study** button stays disabled and no link command is
sent through the screen. This is the wrong-patient refusal: a study cannot be
attached to a different person's order. The API repeats the check on the actual
link command and refuses a mismatch with `422 LABEL_MISMATCH`, so a mismatch
cannot create a performed study even if the screen were bypassed; the browser
journey checks that boundary directly as well. Only the imaging partner's
workstation displays images; the images stay under the partner's access control
and are not stored here.

When both labels match, enter the Study Instance UID from the workstation and a
workstation reference, then select **Link study**. The order moves to
**Performed** and the handoff action appears.

### 5. The interpretation is signed, with addenda as revisions

The interpreter writes the signed report with its five sections in clinical
order: **Technique, Comparison, Findings, Impression, Follow-up**. Signing is an
API action performed by an authorized interpreter; it binds the report to that
person and sends it to the referring clinician. There is no production signing
screen in this slice. A signed report is never edited in place. A correction or
an added comment is an **addendum**: a new revision with its own notice to the
referrer.

In the synthetic referrer report the signer is `synthetic.radiologist`, the
current version is 3 and the impression reads "No acute cardiopulmonary
abnormality."

The handoff action on the worklist records that the performed study has been
passed to reporting. The order then shows **Reported**.

### 6. The referrer reads the report and acknowledges the addendum

The referring clinician opens the signed report in the chart. An open addendum
shows a banner naming its revision, for example "Addendum version 3 is waiting
for your acknowledgment." The referrer selects **Acknowledge version 3** for
that exact revision. The banner for that revision then disappears and the
acknowledgment is recorded with the referrer and the time.

**Important limitation in this slice:** the notice names a revision number, but
the addendum receipt does not yet carry the changed clinical text, its signer or
its signing time. The referrer should not treat acknowledging a bare revision
number as having read and accepted new clinical content. The API work to attach
that content is tracked separately; this guide does not claim clinical
acceptance.

Only notices for this order (tenant plus order identifier) are shown. A
version-3 notice from another tenant, a different order or an earlier addendum
that was already acknowledged is not displayed. An addendum that is not
acknowledged ages into an overdue list for follow-up. Delivery of a report is
not the same as clinical acknowledgment: the referrer still has to act.

Critical communications are a separate part of RAD-01: a critical result follows
its own verification, owner, due time, escalation, read-back and acknowledgment
path. This guide and the browser journey cover the addendum path only; they do
not exercise critical communications, and seeing a report must not be taken as
a critical-result acknowledgment.

## Screen states and recovery

Every screen in this journey defines what it shows when data is loading, when
there is nothing to display, when a request fails or times out, when access is
forbidden, when a version conflicts and when the session has expired (WEB-09).
A failed command is shown above the screen; the current rows and the text you
typed stay in place. A conflict offers compare, reload and copy. The interface
is available in English and Arabic; switching to Arabic changes the document
direction to right-to-left while the stored clinical narrative keeps its own
direction inside the report card.

## Verify the journey

The browser journey lives in
[`radiology-journey.spec.ts`](../../../apps/staff-web/e2e/radflow/radiology-journey.spec.ts).
It uses synthetic data only. The worklist leg mocks the four API calls with a
stateful in-test API and drives the real `/radiology` screen: schedule, refuse a
wrong-patient link, link a correct study and hand off. The referrer leg uses a
[dev-only harness](../../../apps/staff-web/e2e/radflow/referrer-harness.tsx) to
mount the exported report and addendum components, then reads the five sections
in order, acknowledges version 3 and repeats the view in Arabic right-to-left.
The legs use different synthetic orders: the worklist leg uses `ORD-001` and
`synthetic-patient-001`, while the referrer leg uses the signed-report fixture
`rad-004` and `synthetic-patient-004`. This is not one order carried end to end.

From the repository root:

| Check | Command | What it proves |
|---|---|---|
| Browser journey | `cd apps/staff-web && npm run e2e -- --config e2e/playwright.config.ts` | Both journeys pass in a real Chromium browser; the harness entry is present and the English and Arabic assertions hold |
| Staff-web gate | `bash apps/staff-web/check.sh` | Generated client, strict types, lint, build, bundle budget and 100% unit coverage stay green |
| Screen states | `cd apps/staff-web && npx vitest run src/features/radworklist/RadWorklistPage.test.tsx` | The loading, empty, error, forbidden, conflict and session-expired states and the conflict draft recovery described above are asserted at component level; the browser journey does not drive those states |
| Documentation | `python3 -I scripts/check-docs.py` | Links resolve and every requirement ID used here is defined in the specification |

The Playwright configuration starts a local Vite development server on
`127.0.0.1:5173` when `E2E_BASE_URL` is not set. The referrer harness is served
only in development; it is not part of the production build. A staged base URL
must provide its own equivalent harness or the referrer leg will fail its
sentinel check.

## Boundaries and known gaps

- Synthetic data only. The journey proves the steps can be executed, not that a
  clinician accepts the workflow or that a real patient is safe. Real-patient
  approval remains a separate gate.
- There is no production browser "sign" screen in this slice. Signing is an API
  action; the report shown to the referrer is already signed. The journey
  covers the worklist handoff and the referrer's view of a signed report.
- The referrer components are exported for the chart but are not yet wired to a
  chart route. The harness mounts them through their real providers for the
  browser test; the acknowledge action there updates local test state, not a
  production endpoint. A real acknowledgment command and its persistence remain
  separate work.
- Playwright is not yet invoked by a GitHub Actions job. The journey currently
  runs locally; wiring it into CI needs a workflow change outside this
  documentation scope.
- This is a test-and-guide change. No application source, dependency, schema,
  contract or shared tracker is changed here.
