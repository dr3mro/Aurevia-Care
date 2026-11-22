# Pharmacy workflow guide: prescribe, route, clarify, substitute, accept and dispense

- Requirements: RX-02, WEB-09.
- Audience: prescribers, pharmacy staff, and the staff who support or audit the
  prescription hand-off.
- Status: this is the operational guide for the pharmacy screens and their
  synthetic browser journey. It explains who does what and what the screen says.
  It is **not** clinical approval, a partner contract or permission to enable
  e-prescribing. Market approval, policy pack, site policy and real clinical
  acceptance remain separate gates in the specification.
- All subjects, identifiers, medications and reasons below are synthetic. No real
  patient, credential or identifier appears in this guide or in the
  [browser journey](#verify-the-journey).

The approved clauses are in the
[product specification](../../../SMARTCLINIC_V1_SPEC.md) (RX-02 in section 5 and
WEB-09 in section 6). The executable journey is
[`pharmacy-journey.spec.ts`](../../../apps/staff-web/e2e/pharmacyflow/pharmacy-journey.spec.ts);
its in-browser API stand-in is in the same folder.

## What is happening, in one paragraph

A prescriber attests a prescription and routes it through an approved channel.
The pharmacy receives it on its worklist and reads only the bounded prescription
snapshot it needs: patient reference, medication, strength, dose, route,
frequency and quantity. If something is unclear, the pharmacy sends a question;
the prescriber answers it on the same thread without silently changing the
prescription. If the medicine is unavailable, the pharmacy proposes a tagged
catalog alternative; nothing changes until the prescriber approves with an
explicit attestation, which creates a signed revision. The pharmacy then accepts
the prescription. Acceptance and the evidence of handing medicine over are two
different facts: the pharmacy records dispensing separately, and the prescriber
sees them as two separate rows. Each of these events keeps its own time and
actor, so no single status word has to carry several meanings.

## Requirement mapping

| Requirement | What it guarantees | Where it appears below |
|---|---|---|
| RX-02 | Prescriber attestation, recorded channel, stable submission id, separate submitted / delivery-confirmed / accepted-or-rejected / clarification / renewal / cancellation facts, and dispensing evidence that is never implied by acceptance | [Steps 1 to 7](#the-steps), [Status words](#status-words) |
| WEB-09 | Each screen states loading, empty, error, forbidden, stale-version and session-expired states, keeps typed work on failure, and works in English and right-to-left Arabic | [Screen states and recovery](#screen-states-and-recovery) |

This guide introduces no new requirement or threshold.

## Who does what

| Role | What the journey calls them | What they may do |
|---|---|---|
| Prescriber | the prescriber | attest and route a prescription, answer a clarification, approve or decline a substitution, and read the status timeline |
| Pharmacy staff | the pharmacy user | find the routed prescription, ask a question, propose a substitute, accept or reject, and record dispensing evidence |
| Partner channel | the connected prescribing channel | confirm delivery of the routed submission; it is not a clinical decision-maker |

The two users are represented by separate browser contexts. The prescriber's
status timeline is one fact per row: submitted, delivery-confirmed,
clarification, accepted and dispensing never collapse into a single state.

## The steps

### 1. The prescriber prescribes and routes

The prescriber attests the prescription and chooses the approved prescribing
channel. The platform records the channel actually used and issues a stable
submission id (`submission-synthetic-001` in the journey). The prescription
leaves the prescriber's hands as two separate facts: **Submitted** and, once the
channel confirms receipt, **Delivery confirmed**. A locally approved printable
prescription is the alternative only where the policy pack permits it; the
channel used is recorded either way. The synthetic journey models only the
routing command and the stable submission id; it does not model or assert the
attestation or the channel-selection fields, which remain part of the RX-02
behaviour implemented elsewhere.

- **Prescriber sees:** the status timeline with **Submitted** and
  **Delivery confirmed** as two rows.
- **Pharmacy sees:** the prescription on its worklist in the
  **Delivery confirmed** state.

### 2. The pharmacy finds the routed prescription

The pharmacy opens the **Pharmacy inbox** (`/pharmacy`) and finds the item for
the synthetic patient reference. The list is tenant-scoped and cursor-paginated;
it shows the patient reference, medication, dose, quantity, state and an action
to open each item. A status filter narrows the worklist; **All** sends no filter.

The detail screen shows only the bounded snapshot the pharmacy is allowed to
see. It includes the prescriber and the submission id, but not the patient's
chart. Dispensing instructions remain clinician data; pharmacy staff do not
rewrite them here.

- **Pharmacy sees:** "Prescription detail", the clinical snapshot, and two
  separate flags: **Accepted: No** and **Dispensed: No**.
- **Prescriber sees:** no change; the item is still on the timeline.

### 3. The pharmacy asks a question

If the strength to dispense is unclear, the pharmacy types the question in
**Question (required)** and chooses **Send clarification request**. An empty
question is refused in words and nothing is written. A sent question appends a
**Clarification requested** fact and opens a thread addressed to the prescriber;
it does not accept or reject the prescription.

- **Pharmacy sees:** the command confirmation "Command sent to the pharmacy
  API", its flags still **Accepted: No** and **Dispensed: No**, and the question
  in the status history.
- **Prescriber sees:** the question under "Clarification from the pharmacy" and
  **Clarification requested** as a separate timeline row.

### 4. The prescriber answers

The prescriber types a plain answer, an amended prescription revision id, or
both, then chooses **Send answer**. An empty answer is refused. The answer is
appended to the same thread; once answered, the form becomes read-only and a
linked revision is shown as a link, not implied by prose. The prescription is
not silently changed by a text answer.

- **Prescriber sees:** **Answered**, the answer text, and the linked revision
  when one was supplied.
- **Pharmacy sees:** the question's answer on the thread when it next reads the
  prescription; acceptance and dispensing are still untouched.

### 5. The pharmacy proposes a substitute and the prescriber approves

When the prescribed medicine is out of stock, the pharmacy proposes a tagged
catalog alternative with a bounded reason. The review shows the original and
the proposed entry side by side. The active medication stays the original until
the prescriber acts. Choosing **Approve** without an attestation is refused;
with the attestation, the server creates a signed revision and the prescriber
sees its id and time. **Decline** keeps the original and creates no revision.
Neither action accepts the prescription or records dispensing.

- **Prescriber sees:** **Pending review** changing to **Approved**, the signed
  revision link, and the replacement as the active medication.
- **Pharmacy sees:** the approved alternative when it next reads the
  prescription; the acceptance flag is still **No**.

### 6. The pharmacy accepts

The pharmacy chooses **Accept**. The platform appends one **Accepted** fact.
Acceptance and dispensing evidence are stored separately: accepting never
claims that medicine changed hands, and the **Dispensed** flag stays **No**. A
rejection is the alternative command, requires a reason and appends its own
fact; the journey does not reject this prescription.

- **Pharmacy sees:** **Accepted: Yes** and **Dispensed: No**.
- **Prescriber sees:** **Accepted by pharmacy** as its own timeline row, with no
  dispensing evidence on it.

### 7. The pharmacy records dispensing

Only after handing over the medicine does the pharmacy open **Record dispensing
evidence** and record who dispensed it, the time, and what was handed over.
This appends a separate **Dispensed** fact carrying the three evidence fields.
The prescriber reads them on the dispensing row; they never appear on the
acceptance row.

- **Pharmacy sees:** **Accepted: Yes** and **Dispensed: Yes** together, because
  both facts now exist.
- **Prescriber sees:** two rows — **Accepted by pharmacy** and **Dispensed** —
  with the dispenser, time and hand-over text on the dispensing row only.

## Status words

Each word describes one fact, not a summary of the whole prescription.

| Status | What it means | Who records it |
|---|---|---|
| Submitted | The prescription was attested and sent with a stable submission id | Prescriber |
| Delivery confirmed | The approved channel confirmed receipt of the submission | Partner channel |
| Clarification / Clarification requested | A question is open or was answered on the thread; the prescription itself is unchanged. The pharmacy worklist shortens it to **Clarification**; the prescriber timeline shows **Clarification requested** | Pharmacy, then prescriber |
| Pending review | A substitution proposal is waiting for the prescriber's decision; the original medication is still active | Pharmacy |
| Approved / Declined | The prescriber approved the proposal, creating a signed revision, or declined it, leaving the original in place; neither accepts the prescription | Prescriber |
| Accepted | The pharmacy accepted the prescription; it does **not** mean the medicine was handed over | Pharmacy |
| Rejected | The pharmacy declined with a recorded reason; the original prescription is not dispensed | Pharmacy |
| Dispensed | Evidence of who dispensed, when, and what was handed over exists; it is separate from acceptance | Pharmacy |
| Amendment / revision | A signed new version created only after a prescriber approves a substitution | Prescriber |
| Cancelled / renewal | Further lifecycle facts tracked separately; not exercised by this journey | Prescriber |

**Accepted** and **Dispensed** are independent. A prescription can be accepted
and not dispensed; dispensing evidence is never inferred from acceptance. A
text answer or a signed revision is not an acceptance either.

## Screen states and recovery

Every screen defines what it shows while data loads, when there is nothing to
display, when a request fails or times out, when access is forbidden, when a
version conflicts, and when the session has expired (WEB-09). A refused command
shows the reason above the screen and keeps every value the user typed: an empty
clarification question does not clear the form, and a failed decision does not
clear the attestation. A stale-version response offers compare, reload and copy
rather than discarding work. Leaving a screen with typed but unsaved text asks
for confirmation first. The interface is available in English and Arabic; in
Arabic the page direction is right-to-left while stored clinical labels keep
their own direction. The browser journey reads the completed timeline in both
languages.

## Verify the journey

From the repository root:

| Check | Command | What it proves |
|---|---|---|
| Browser journey | `cd apps/staff-web && npm run e2e -- --config e2e/playwright.config.ts e2e/pharmacyflow/pharmacy-journey.spec.ts` | Both roles pass in a real Chromium browser: prescribe and route, question and answer, substitution approval, acceptance and dispensing, the wording each side sees, the empty-question barrier and the Arabic timeline |
| Staff-web gate | `bash apps/staff-web/check.sh` | Generated client, strict types, lint, build, bundle budget and 100% unit coverage stay green |
| Documentation | `python3 -I scripts/check-docs.py` | Links resolve and every requirement ID used here is defined in the specification |

The Playwright configuration starts a local Vite development server on
`127.0.0.1:5173` when `E2E_BASE_URL` is not set. The prescriber leg uses a
[dev-only harness](../../../apps/staff-web/e2e/pharmacyflow/prescriber-harness.tsx)
to mount the exported timeline, clarification and substitution components; it is
not part of the production build. A staged base URL must provide an equivalent
harness or that leg will fail its sentinel check.

### Entry point to test trace

- `/pharmacy` → `PharmacyInboxPage` → `GET /api/v1/pharmacy/inbox` → worklist row
  and state label → assertions in steps 1 and 2 of the journey.
- `/pharmacy/:id` → `PharmacyItemPage` → `GET /api/v1/pharmacy/inbox/{id}` and
  `GET /api/v1/pharmacy/status/{id}` → accepted and dispensed flags and the
  status history → assertions in steps 3, 6 and 7.
- The review, clarify and dispense buttons → `POST /api/v1/pharmacy/status/{id}/…`
  → append-only facts → the stub's shared store and the prescriber timeline.
- The harness → `getClarificationThread`, `answerClarification`,
  `getSubstitutionProposal`, `approveSubstitution`, `declineSubstitution` →
  thread and proposal views → the answer, approval and revision assertions.

## Boundaries and known gaps

- Synthetic data only. The journey proves the steps can be executed, not that a
  clinician accepts the workflow or that a real patient is safe. No real
  clinician, pharmacist or patient participated in this review.
- There is no production browser "prescribe and route" screen in this slice.
  Routing and the substitution proposal are sent through the same browser
  endpoint the API provides, as the laboratory journey does for recollection.
  The prescriber's decision itself is driven through the real component and API
  client. The harness exists because those components are not yet wired to a
  chart route.
- The two browser contexts share an in-memory test store, not a server. The
  journey proves the screens and commands work together; persistence, tenant
  isolation and authorization across a restart belong to the API and database
  stories and their integration tests.
- Playwright is not yet invoked by a GitHub Actions job. The journey currently
  runs locally; wiring it into CI needs a workflow change outside this
  documentation scope.
- This is a test-and-guide change. No application source, dependency, schema,
  contract or shared tracker is changed here.
