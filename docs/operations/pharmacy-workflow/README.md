# Pharmacy workflow guide: prescribe, route, clarify, substitute, accept and dispense

- Requirements: RX-02, WEB-09.
- Audience: prescribing clinicians, pharmacy staff, and the staff who support,
  supervise or audit the prescribing and pharmacy hand-off.
- Status: this is the operational guide for the pharmacy screens and their
  synthetic browser journey. It explains who does what and what the screen says.
  It is **not** clinical approval, a partner contract or permission to enable
  e-prescribing for real patients. Market approval, the policy pack, site policy
  and real clinical acceptance remain separate G5 gates in the specification.
- All names, subjects, identifiers, medicines, questions and times below are
  synthetic. No real patient, credential or identifier appears in this guide or
  in the [browser journey](#verify-the-journey).

The approved clauses are in the
[product specification](../../../.opencode/config/PROJECT_V1_SPEC.md) (RX-02 in section 5 and
WEB-09 in section 6). The executable journey is
[`pharmacy-journey.spec.ts`](../../../apps/staff-web/e2e/pharmacyflow/pharmacy-journey.spec.ts);
its deterministic API stand-in
([`synthetic-pharmacy-api.ts`](../../../apps/staff-web/e2e/pharmacyflow/synthetic-pharmacy-api.ts))
and the prescriber harness
([`prescriber-harness.tsx`](../../../apps/staff-web/e2e/pharmacyflow/prescriber-harness.tsx))
are in the same folder.

## What is happening, in one paragraph

A prescriber confirms the medicine, strength, dose, route, frequency and
quantity, records a **prescriber attestation**, and routes the prescription
through an approved channel. The platform records the channel actually used and
issues a **stable submission id** that does not change when a message is resent
or reconciled. The pharmacy receives the prescription on its worklist and reads
only the bounded snapshot it needs: patient reference, medication, strength,
dose, route, frequency and quantity; dosing instructions remain clinician data.
If something is unclear, the pharmacy sends a question; the prescriber answers
it on the same thread without silently changing the prescription. If the
medicine is unavailable, the pharmacy proposes a tagged catalog alternative;
nothing changes until the prescriber approves with an explicit attestation,
which creates a signed revision. The pharmacy then accepts the prescription.
Acceptance and the evidence of handing medicine over are two different facts:
the pharmacy records dispensing separately, and the prescriber sees them as two
separate rows. Each event keeps its own time and actor, so no single status word
has to carry several meanings.

## Requirement mapping

| Requirement | What it guarantees | Where it appears below |
|---|---|---|
| RX-02 | Prescriber attestation, recorded channel, stable submission id, separate submitted / delivery-confirmed / accepted-or-rejected / clarification / renewal / cancellation facts, and dispensing evidence that is never implied by acceptance | [The steps](#the-steps), [Status words](#status-words) |
| WEB-09 | Each screen states loading, empty, error, forbidden, stale-version and session-expired states, keeps typed work on failure, and works in English and right-to-left Arabic | [Screen states and recovery](#screen-states-and-recovery), [Verify the journey](#verify-the-journey) |

This guide introduces no new requirement or threshold.

## Who does what

| Role | What the journey calls them | What they may do |
|---|---|---|
| Prescriber | a clinician with an enabled prescribing entitlement | attest and route a prescription, answer a clarification, approve or decline a substitution, and read the status timeline |
| Pharmacy staff | the pharmacy user | find the routed prescription, read it, ask a question, propose a substitute, accept or reject, and record dispensing evidence |
| Partner channel | the connected prescribing channel | confirm delivery of the routed submission; it is not a clinical decision-maker |
| Patient | the prescription subject | sees plain status words in the patient app; never sees a clinical draft or another tenant's record |

The two users act in separate browser sessions, represented by separate browser
contexts. They share one synthetic fact trail, so a status written by one side is
what the other side reads. The server, not either screen, remains the authority
for identity, entitlement, tenant scope and the audit record. The prescriber's
status timeline is one fact per row: submitted, delivery-confirmed,
clarification, accepted and dispensing never collapse into a single state.

## The steps

### 1. The prescriber prescribes and routes

The prescriber confirms the medicine and records an explicit **prescriber
attestation** before choosing the approved channel. The channel actually used is
recorded; a locally approved printable prescription is the alternative only
where the policy pack permits it, and the channel used is recorded either way.
The prescription leaves the prescriber's hands as two separate facts:
**Submitted** and, once the partner acknowledges receipt, **Delivery confirmed**.
The synthetic journey models the routing command and the stable submission id
(`submission-synthetic-001`); the attestation and channel-selection fields are
part of the RX-02 behaviour implemented elsewhere.

- **Prescriber sees:** the prescription as *Submitted*, then *Delivery confirmed*
  as two rows in the status timeline.
- **Pharmacy sees:** a new card on the worklist in the *Delivery confirmed*
  state, with the medication, dose, quantity, instructions, prescriber and the
  opaque patient dispensing reference.

### 2. The pharmacy reads before responding

The pharmacy opens the **Pharmacy inbox** (`/pharmacy`) and the detail card for
the synthetic patient reference. The list is tenant-scoped and cursor-paginated;
a status filter narrows it and **All** sends no filter. The detail screen shows
only the bounded snapshot the pharmacy is allowed to see — including the
prescriber and submission id, but not the patient's chart. The status panel
states **Accepted: No** and **Dispensed: No** and lists the fact trail with who
recorded each fact and when.

- **Accepted** and **dispensed** are two separate switches. Reading or accepting
  a prescription never claims that a medicine was handed over.

### 3. The pharmacy asks a question

If something is unclear, the pharmacy writes a **question** under *Question
(required)* and chooses **Send clarification request**. An empty question is
refused in words — the text must contain something before the request is sent —
and nothing is written. The request becomes its own fact in the history;
acceptance and dispensing are unchanged.

- **Pharmacy sees:** the command confirmation "Command sent to the pharmacy
  API", its flags still **Accepted: No** and **Dispensed: No**, and the question
  in the status history.
- **Prescriber sees:** a *Clarification requested* row in the status timeline and
  the question in the clarification view.
- **Patient sees:** "Pharmacy needs to check with your doctor" — no claim that
  the medicine is ready.

### 4. The prescriber answers

The prescriber reads the question and answers with text, an amended revision id,
or both, then chooses **Send answer**. An empty answer with no revision is
refused. Once answered, the thread is read-only and shows the answer and any
linked revision as a link, not implied by prose, without implying a prescription
change the server did not make.

- **Pharmacy sees:** the answer on the thread when it next reads the
  prescription; the original status facts remain separate rows, and acceptance
  and dispensing are still untouched.
- **Patient sees:** no readiness claim from an answer alone.

### 5. The pharmacy proposes a substitute

If the prescribed item cannot be supplied, the pharmacy proposes a **catalog
alternative** with a bounded reason (out of stock, different brand or different
formulation) and an optional note. The review shows the original and proposed
entries side by side. Nothing on the prescription changes at this point: the
original medicine stays active while the proposal is pending.

In the current staff web the inbox has no dedicated propose button yet, so the
browser journey sends this command through the same intercepted browser
endpoint the pharmacy API provides (as the laboratory journey does for
recollection). That gap is recorded under [Boundaries and known gaps](#boundaries-and-known-gaps).

- **Prescriber sees:** *Pending review*, the original and proposed entries, the
  reason, and "Original medication retained".
- **Pharmacy sees:** the proposal as pending until the prescriber decides.

### 6. The prescriber approves or declines

The prescriber compares the two entries and, to approve, enters an explicit
**attestation**. Approval without an attestation is refused and changes nothing.
Approval creates a **signed revision** that carries the replacement; the link to
that revision appears in the review and the prescription now shows the approved
medicine. **Decline** keeps the original and creates no revision. The decision
requires the prescriber to still hold the prescribing entitlement, so a revoked
entitlement cannot approve. Neither action accepts the prescription or records
dispensing.

- **Prescriber sees:** *Approved* and the signed revision link (or *Declined*
  with the original retained).
- **Pharmacy sees:** the approved replacement on the prescription when it
  reloads; the acceptance flag is still **No**.

### 7. The pharmacy accepts

The pharmacy chooses **Accept** for the prescription as it now stands. The
platform appends one **Accepted** fact. It does **not** set dispensing: the
screen still shows **Dispensed: No** immediately after acceptance. A rejection
is the alternative command, requires a reason and appends its own fact; the
journey does not reject this prescription.

- **Prescriber sees:** an *Accepted by pharmacy* row, separate from every other
  fact, with no dispensing evidence on it.
- **Patient sees:** "Pharmacy accepted".

### 8. The pharmacy records dispensing evidence

Only after handing over the medicine does the pharmacy open **Record dispensing
evidence** and record who dispensed it, when (an ISO instant) and what was handed
over. This appends a separate **Dispensed** fact carrying the three evidence
fields. The screen never treats acceptance as evidence of a hand-over, and a
hand-over is never silently inferred from acceptance.

- **Prescriber sees:** a distinct *Dispensed* row carrying all three evidence
  fields; accepted and dispensed remain separate rows.
- **Patient sees:** "Handed over" only now that evidence exists.

## Status words

Each word describes one fact, not a summary of the whole prescription.

| Status or label | Meaning | Who records it | Does it mean the medicine is ready? |
|---|---|---|---|
| Submitted | The prescriber routed the prescription and gave it a stable submission id | Prescriber | No |
| Delivery confirmed | The approved channel or partner acknowledged the submission | Partner channel | No |
| Clarification / Clarification requested | A question is open or was answered on the thread; the prescription itself is unchanged. The pharmacy worklist shortens it to **Clarification**; the prescriber timeline shows **Clarification requested** | Pharmacy, then prescriber | No |
| Answered | The prescriber answered the open question; the thread is now read-only | Prescriber | No |
| Pending review | A substitute has been proposed; the original medication is still active | Pharmacy | No |
| Approved / Declined | The prescriber approved the proposal, creating a signed revision, or declined it, leaving the original in place; neither accepts the prescription | Prescriber | No, not by itself |
| Accepted | The pharmacy accepted the prescription; it does **not** mean the medicine was handed over | Pharmacy | No — acceptance is not dispensing |
| Rejected | The pharmacy declined with a recorded reason; the original prescription is not dispensed | Pharmacy | No |
| Dispensed | Evidence of who dispensed, when, and what was handed over exists; it is separate from acceptance | Pharmacy | Yes, only now |
| Amendment / revision | A signed new version created only after a prescriber approves a substitution | Prescriber | No, not by itself |
| Cancelled / renewal | Further lifecycle facts tracked separately; not exercised by this journey | Prescriber | No |

**Accepted: Yes and Dispensed: No is a normal, expected combination.** Dispensing
evidence without an acceptance is also possible and is shown as its own fact;
neither switch is derived from the other. A text answer or a signed revision is
not an acceptance either.

## Screen states and recovery

Every screen defines what it shows while data loads, when there is nothing to
display, when a request fails or times out, when access is forbidden, when a
version conflicts, and when the session has expired (WEB-09). A refused command
shows the reason above the screen and keeps every value the user typed: an empty
clarification question does not clear the form, and a failed decision does not
clear the attestation. A stale-version response offers compare, reload and copy
rather than discarding work; the shared stand-in returns `412 VERSION_CONFLICT`
for a command carrying an `If-Match` for a different version, and the journey
asserts that no fact is appended. Leaving a screen with typed but unsaved text
asks for confirmation first. The interface is available in English and Arabic;
in Arabic the page direction is right-to-left while stored clinical labels keep
their own direction. The journey reads the completed timeline in both languages.

## Verify the journey

From the repository root:

| Check | Command | What it proves |
|---|---|---|
| Browser journey | `cd apps/staff-web && npm run e2e -- --config e2e/playwright.config.ts e2e/pharmacyflow/pharmacy-journey.spec.ts` | Both roles pass in a real Chromium browser: prescribe and route, question and answer, substitution approval, acceptance and dispensing, the wording each side sees, the empty-question barrier, the stale-version guard and the Arabic timeline |
| Staff-web gate | `bash apps/staff-web/check.sh` | Generated client, strict types, lint, build, bundle budget and 100% unit coverage stay green |
| Documentation | `python3 -I scripts/check-docs.py` | Links resolve and every requirement ID used here is defined in the specification |

The Playwright configuration starts a local Vite development server on
`127.0.0.1:5173` when `E2E_BASE_URL` is not set. The prescriber leg uses a
[dev-only harness](../../../apps/staff-web/e2e/pharmacyflow/prescriber-harness.tsx)
to mount the exported timeline, clarification and substitution components; it is
not part of the production build. A staged base URL must provide an equivalent
harness or that leg will fail its sentinel check. The journey stubs only
`/api/v1/pharmacy/**` and the logical `/api/v1/prescriptions/**` commands, so it
needs no live partner, patient record or credential.

### Entry point to test trace

- `/pharmacy` → `PharmacyInboxPage` → `GET /api/v1/pharmacy/inbox` → worklist row
  and state label → assertions in steps 1 and 2 of the journey.
- `/pharmacy/:id` → `PharmacyItemPage` → `GET /api/v1/pharmacy/inbox/{id}` and
  `GET /api/v1/pharmacy/status/{id}` → accepted and dispensed flags and the
  status history → assertions in steps 3, 7 and 8.
- The review, clarify and dispense buttons → `POST /api/v1/pharmacy/status/{id}/…`
  → append-only facts → the stub's shared store and the prescriber timeline.
- The harness → `getClarificationThread`, `answerClarification`,
  `getSubstitutionProposal`, `approveSubstitution`, `declineSubstitution` →
  thread and proposal views → the answer, approval and revision assertions.
- The route command → `POST /api/v1/pharmacy/inbox/route` (and the logical
  `transmit` and `confirm-delivery` commands recorded in the command log) →
  `Submitted` and `Delivery confirmed` as two rows.

## What the browser journey proves — and what it does not

The journey clicks the real controls and asserts the status wording each side
sees at every step. It also checks the refusal paths: an empty clarification
question, an approval without attestation, a stale `If-Match`, and the guard that
acceptance never sets dispensing.

### Boundaries and known gaps

- The prescriber status timeline, clarification reply and substitution review
  are exported components that are not yet routed in the staff portal. The
  journey mounts them through a dev-only harness that uses their real providers
  and API client; the harness is not a production entry point.
- There is no production browser "prescribe and route" screen or propose button
  in this slice. Routing and the substitution proposal are sent through the same
  browser endpoint the API provides. The prescriber's decision itself is driven
  through the real component and API client. Wiring visible controls belongs to
  a feature story, not this test-and-guide story.
- The two browser contexts share an in-browser stand-in, not a real server. The
  journey proves the screens interpret and present the facts consistently; it
  does not prove cross-replica persistence, server-side entitlement or tenant
  isolation. Those belong to the API, database and integration stories and
  their tests.
- Playwright is not yet invoked by a GitHub Actions job. The journey currently
  runs locally; wiring it into CI needs a workflow change outside this
  documentation scope.
- This is a test-and-guide change. No application source, dependency, schema,
  contract or shared tracker is changed here.
- No real clinician, pharmacist or patient participated in this review. Live
  enablement needs market approval, the policy pack, partner readiness and the
  site-specific clinical and G5 sign-offs. The client is never the source system
  authority for identity, release, authorization or the audit record.
