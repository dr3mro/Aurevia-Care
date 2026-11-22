# Pharmacy workflow guide: prescribe, route, clarify, substitute, accept and dispense

- Requirements: RX-02, WEB-09
- Audience: prescribing clinicians, pharmacy staff, and the staff who support
  the prescribing and pharmacy services.
- Status: this is the operational guide for the pharmacy screens and the
  synthetic browser journey that exercises them. It explains what each role does
  and what the screen shows. It is **not** clinical approval, a partner contract
  or permission to enable prescribing for real patients. Market approval, site
  policy and real clinical acceptance remain separate G5 gates in the
  specification.
- All names, subjects, medicines, questions and times below are synthetic. No
  real patient, credential or identifier is used in the guide or the browser
  test.

The approved clauses are in the [product specification](../../../SMARTCLINIC_V1_SPEC.md)
(RX-02 in section 5; WEB-09 in section 6). The executable journey is
[`apps/staff-web/e2e/pharmacyflow/pharmacy-journey.spec.ts`](../../../apps/staff-web/e2e/pharmacyflow/pharmacy-journey.spec.ts);
its deterministic API stand-in and the prescriber harness are in the same folder.

## Who does what

| Actor | What the journey calls them | What they may do |
|---|---|---|
| Prescriber | a clinician with an enabled prescribing entitlement | attest to a medicine, route it through the approved channel, answer a pharmacy question, and approve or decline a proposed substitute |
| Pharmacy user | a member of the destination pharmacy | read the routed prescription, accept or reject it, ask a question, propose a catalog substitute, and record dispensing evidence |
| Patient | the prescription subject | sees plain status words in the patient app; never sees a clinical draft or another tenant's record |

The two users act in separate browser sessions. They share one synthetic fact
trail, so a status written by one side is what the other side reads. The server,
not either screen, remains the authority for identity, entitlement, tenant scope
and the audit record.

## The journey, step by step

### 1. Prescribe and route

The prescriber confirms the medicine, strength, dose, route, frequency and
quantity, records a **prescriber attestation**, and routes the prescription to an
approved destination pharmacy. The channel actually used is recorded; a locally
printed prescription is a different, equally explicit route. The prescription
receives a **stable submission id** that does not change when a message is
resent or reconciled.

- **Prescriber sees:** the prescription as *Submitted*, then *Delivery confirmed*
  once the partner acknowledges the submission.
- **Pharmacy sees:** a new card in the pharmacy inbox with the medication, dose,
  quantity, instructions, prescriber and the opaque patient dispensing
  reference.

### 2. Pharmacy reads before responding

The pharmacy opens the detail card. The status panel states **Accepted: No** and
**Dispensed: No** and lists the fact trail (submitted, delivery confirmed) with
who recorded each fact and when. The card shows the dosing instructions taken
from the clinician's prescription, never text typed by pharmacy staff.

- **Accepted** and **dispensed** are two separate switches. Reading or accepting
  a prescription never claims that a medicine was handed over.

### 3. Pharmacy asks a question

If something is unclear, the pharmacy writes a **question** and sends a
clarification request. An empty question is refused: the text must contain
something before the request is sent. The request becomes its own fact in the
history; acceptance and dispensing are unchanged.

- **Prescriber sees:** a *Clarification requested* row in the status timeline and
  the question in the clarification view.
- **Patient sees:** "Pharmacy needs to check with your doctor" — no claim that
  the medicine is ready.

### 4. Prescriber answers

The prescriber reads the question and answers with text, an amended revision id,
or both. An empty answer with no revision is refused. Once answered, the thread
is read-only and shows the answer and any linked revision without implying a
prescription change the server did not make.

- **Pharmacy sees:** the answer in the thread and the original status facts kept
  as separate rows.
- **Patient sees:** no readiness claim from an answer alone.

### 5. Pharmacy proposes a substitute

If the prescribed item cannot be supplied, the pharmacy proposes a **catalog
alternative** with a bounded reason (out of stock, different brand or different
formulation) and an optional note. The proposal shows the original and proposed
entries side by side. Nothing on the prescription changes at this point: the
original medicine stays active while the proposal is pending.

In the current staff web the inbox has no dedicated propose button yet, so the
browser journey sends this command through the same intercepted endpoint the
pharmacy API provides. That gap is recorded under "Limitations" below.

- **Prescriber sees:** *Pending review*, the original and proposed entries, the
  reason, and "Original medication retained".
- **Pharmacy sees:** the proposal as pending until the prescriber decides.

### 6. Prescriber approves or declines

The prescriber compares the two entries and, to approve, enters an explicit
**attestation**. Approval without an attestation is refused and changes nothing.
Approval creates a **signed revision** that carries the replacement; the link to
that revision appears in the review and the prescription now shows the approved
medicine. Declining keeps the original and creates no revision. The decision
requires the prescriber to still hold the prescribing entitlement, so a revoked
entitlement cannot approve.

- **Prescriber sees:** *Approved* and the signed revision link (or *Declined* with
  the original retained).
- **Pharmacy sees:** the approved replacement on the prescription when it
  reloads.

### 7. Pharmacy accepts

The pharmacy chooses **Accept** for the prescription as it now stands. Acceptance
is one recorded fact. It does **not** set dispensing: the screen still shows
**Dispensed: No** immediately after acceptance.

- **Prescriber sees:** an *Accepted by pharmacy* row, separate from every other
  fact.
- **Patient sees:** "Pharmacy accepted".

### 8. Pharmacy records dispensing evidence

Dispensing is a separate form with its own fields: who dispensed, when (an ISO
instant) and what was handed over. The pharmacy records **evidence**, which
makes the status *Dispensed*. The screen never treats acceptance as evidence of a
hand-over, and a hand-over is never silently inferred from acceptance.

- **Prescriber sees:** a distinct *Dispensed* row carrying all three evidence
  fields; accepted and dispensed remain separate rows.
- **Patient sees:** "Handed over" only now that evidence exists.

## What each status means

| Status or label | Meaning | Does it mean the medicine is ready? |
|---|---|---|
| Submitted | The prescriber routed the prescription and gave it a stable submission id. | No |
| Delivery confirmed | The approved channel or partner acknowledged the submission. | No |
| Clarification | The pharmacy asked a question; the prescriber has not answered yet. | No |
| Answered | The prescriber answered the open question. The thread is now read-only. | No |
| Pending review | A substitute has been proposed; the original medicine is still active. | No |
| Approved | The prescriber attested and a signed revision replaced the original. | No, not by itself |
| Declined | The prescriber refused the substitute; the original stands. | No |
| Accepted | The pharmacy accepted the prescription (as revised if applicable). | No — acceptance is not dispensing |
| Dispensed | The pharmacy recorded who dispensed, when and what was handed over. | Yes, only now |

Accepted: Yes and Dispensed: No is a normal, expected combination. Dispensing
evidence without an acceptance is also possible and is shown as its own fact;
neither switch is derived from the other.

## What the browser journey proves — and what it does not

The Playwright journey opens the real staff-web pharmacy routes and the
prescriber harness, clicks the real controls and asserts the status wording each
side sees at every step. It uses a deterministic synthetic API stand-in for
`/api/v1/pharmacy/**` and `/api/v1/prescriptions/**`, so it needs no live partner,
patient record or credential. It also checks the refusal paths: an empty
clarification question, an approval without attestation, and the guard that
acceptance never sets dispensing.

### Limitations

- The prescriber status timeline, clarification reply and substitution review
  are exported components that are not yet routed in the staff portal. The
  journey mounts them through a dev-only harness that uses their real providers
  and API client; the harness is not a production entry point.
- The pharmacy inbox has no propose-a-substitute button yet, so the journey
  sends that one command through the intercepted browser endpoint rather than a
  visible control. Wiring a control belongs to a feature story, not this
  test-and-guide story.
- The two browser contexts share an in-browser stand-in, not a real server. The
  journey proves the screens interpret and present the facts consistently; it
  does not prove cross-replica persistence, server-side entitlement or tenant
  isolation. Those belong to the API, database and integration stories.
- No real clinician, pharmacist or patient participated in this review. Live
  enablement needs market approval, the policy pack, partner readiness and the
  site-specific clinical and G5 sign-offs. The client is never the source system
  authority for identity, release, authorization or the audit record.
