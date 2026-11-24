# Prescriptions and orders: clinician workflow guide

- Requirements: RX-02, LAB-01, RAD-01, XCH-02
- Status: this is the operational workflow for the prescribing and order-entry
  screens. It describes what a clinician does and what the system records; it is
  not clinical approval. The clinical policy, preferred channels and site
  configuration are approved separately by the accountable owners in the
  specification's decision table.
- Audience: prescribing clinicians, clinicians who place lab or imaging orders,
  and the staff who support them.
- Scenario data is synthetic. No real patient appears in this guide or in the
  [UAT scenario](uat.md).

This is one workflow with two entry points. You either **write a prescription**
(medicine for a patient to obtain) or **place an order** (a lab test or an imaging
study for a patient to undergo). From the moment the item is ready to send, both
follow the same sending rules: choose **electronic**, **print**, or **both**, see
what the patient will receive, and watch what happens until the result comes back.

## Requirement mapping

| Requirement | What it guarantees | Where the clinician sees it |
|---|---|---|
| RX-02 | Prescriber attestation, an approved channel or a locally approved printable prescription, a recorded actual channel, separate submission/delivery/acceptance/clarification/renewal/cancellation events, stable submission IDs and partner-status verification before retry | [Write a prescription](#2a-write-a-prescription) and [When a send fails](#5-when-a-send-fails-or-is-ambiguous) |
| LAB-01 | An issuer-scoped accession ID, specimen/site/collector/times and patient matching, with rejected specimens and linked recollection | [Place an order](#2b-place-a-lab-or-imaging-order) and [See the result](#7-see-the-result) |
| RAD-01 | An imaging order with urgency and clinical question, scheduling, accession/modality, patient verification before association and a signed interpretation | [Place an order](#2b-place-a-lab-or-imaging-order) and [See the result](#7-see-the-result) |
| XCH-02 | Separate workflow, transmission and clinical-review states; acknowledgment by an authorized human; a transmission acknowledgment never closes a clinical task | [Follow the item](#6-follow-the-item) and [When a send fails](#5-when-a-send-fails-or-is-ambiguous) |

The approved clauses are in the [product specification](../../../.opencode/config/PROJECT_V1_SPEC.md).
No new requirement ID or threshold is introduced by this guide.

## The workflow

### 1. Start from the patient chart

Open the patient's chart and confirm you are on the correct patient and the
correct encounter. The patient and encounter are taken from this context; do not
type them by hand. Everything you create is attached to this chart, and the
receiving partner confirms the patient again before acting on a lab or imaging
order. If the chart context is wrong, stop and correct it before writing anything.

You must be a clinician with ordering or prescribing privilege in this facility
to continue. A clinician without that privilege can view but cannot place or send.

### 2a. Write a prescription

1. Choose the medicine from the approved, versioned drug catalog. Check the
   tagged alternatives and the patient's verified allergies; if an ingredient is
   unknown or looks like a mismatch, review it and record an override reason
   before continuing. (This draft work is RX-01.)
2. Enter strength, formulation, dose, route, frequency, quantity and refills
   where supported, plus any instructions and the destination pharmacy. The
   draft can be saved and edited until you attest it.
3. **Attest** to the exact draft. Attestation records that you, as the
   prescriber, stand behind this revision; it is a distinct, permanent event.
   After attestation the prescription is fixed and can only change through
   cancel or renew.
4. Confirm your market entitlement to prescribe this medicine. If you are not
   entitled, the prescription cannot be routed and you must not work around it.

### 2b. Place a lab or imaging order

1. Search the facility's orderable catalog and select the test (lab) or study
   (imaging). Items are versioned and effective on a date: an older order keeps
   the version it was placed with, and a retired item cannot be selected for a
   new order.
2. Enter the priority and the preparation instructions, which you may edit (for
   example, fasting). For an **imaging** order the clinical question is required
   — the study cannot be placed without it. For a lab order record the specimen
   type and site.
3. Review allergies and the patient's active orders so a duplicate is noticed
   before you place this one.
4. You may save a **draft** and come back to it. **Placing** the order makes it
   immutable except through cancel or amend.

### 3. Choose how it goes out: electronic, print, or both

| Choice | What it means | What the patient receives |
|---|---|---|
| **Electronic** | The item is sent through the approved channel to the receiving partner — the pharmacy for a prescription, the lab or imaging partner for an order. A stable submission reference is recorded so the same send can be traced and retried safely. | Nothing to carry. The pharmacy or performing site receives it directly; the patient is told it has been sent. |
| **Print** | A locally approved printable document is produced. For a prescription this is a printable prescription; for a lab or imaging order it is a printed requisition the patient carries. Printing produces the document only — it does **not** mean the item was sent electronically or accepted by a partner. | A paper document to take to the pharmacy or performing site. |
| **Both** | The item is sent electronically and a printout is also produced, for example as a backup if the patient cannot reach the partner. | Both the electronic send and a paper document. |

For a prescription the approved channel is one at a time: an electronic
prescribing channel **or** a locally approved printable prescription. For a lab
or imaging order you can select electronic, print, or both. The screen states the
chosen mode in words before you confirm — read it and correct it if it is not
what you intended.

### 4. Place or send, and read the confirmation

Confirm the choice. The channel actually used is recorded on the item; this is
the "actual channel", which may differ from what was available. Check the
confirmation states what was sent and through which route. If you printed, hand
the document to the patient and explain that it is a printout, not proof of an
electronic send or of pharmacy acceptance.

### 5. When a send fails or is ambiguous

A send does not always complete. Two different things can happen, and they are
handled differently:

- **Failed send.** If the electronic send fails after the approved retry limit,
  the item is marked as **needing action** and the failed attempt is kept in the
  history. Nothing is silently resent, and no duplicate order is created. You
  have two choices: retry, but only when the system says it is safe, or **switch
  to print** and hand the patient a printout. Switching to print reuses the same
  order or prescription — it does not create a second one. The failed attempt
  stays visible in the history for later review.
- **Ambiguous send.** If the outcome of a send is unknown, do **not** simply
  send again — the partner may already have the item. The system checks the
  partner's status first. One of three results follows: the partner has it
  (reconcile and continue), the partner does not have it (a retry is now allowed
  and reuses the original submission reference), or the partner's status cannot
  be determined (the item stays in reconciliation and retry stays blocked until
  a person resolves it). A retry that is allowed reuses the same stable
  submission reference so the partner cannot receive two different sends for
  the same request.

If the partner has no approved electronic interface, the delivery is recorded as
**manual**, with who delivered it and when. This is evidence of delivery, not a
clinical acknowledgment.

### 6. Follow the item

The item shows three kinds of state. Keep them separate in your mind; one does
not imply the next.

| State family | What it tracks | Typical values |
|---|---|---|
| **Workflow** | Where the request is in the care process | requested → accepted → processing → completed, with rejected and cancelled as side branches |
| **Transmission** | Whether the message reached the partner | pending → sent → delivered, or failed |
| **Clinical review** | Whether a clinician has acted on it | unreviewed → acknowledged → action pending → resolved |

Each clinically significant fact is a separate, permanent event: attestation,
submission, delivery confirmation, partner-status verification, printing,
pharmacy acceptance or rejection, clarification, renewal, cancellation and
dispensing evidence. Acceptance is **not** dispensing, delivery is **not**
acceptance, and a printed prescription is **never** reported as electronically
delivered. A transmission acknowledgment (the message arrived) never closes the
clinical task (a person still has to act). Acknowledgment is recorded only by
an authorized human, with a timestamp and the revision they acknowledged.

### 7. Cancel or amend

- **Cancel** is allowed while the partner has not accepted the item and needs a
  reason. After the partner has accepted, you cannot cancel it outright: a
  **cancel request** is sent to the partner and you wait for their response.
  Once a prescription is dispensed, or a lab specimen is collected, or an item
  is already cancelled, it cannot be moved back to a positive state.
- **Amend** makes a new revision of the item and tells the partner about it. The
  prior revision is kept, so the history shows what changed. You cannot edit a
  placed item in place — amending is the only way to change it after placing.
- A **renewal** is linked to the original submission as its own event; it does
  not rewrite the original prescription.

### 8. See the result

Open the order or prescription from the chart or the orders list to see its
current state, the delivery evidence and the full event history.

- A **lab** result links back to its order and appears in the order status. At
  collection and receipt the specimen is matched to the patient and accession;
  a rejected specimen keeps its reason and notification, and a recollection gets
  a linked new specimen ID.
- An **imaging** report links back to its order. The performing site produces a
  signed interpretation; addenda and critical communications are linked to it.
- A **critical result** does not stop at "result arrived". It follows the
  separate critical-result workflow: verification, an owner, a due time,
  escalation, read-back and acknowledgment by a responsible clinician. Delivery
  of a report or a patient release never counts as that acknowledgment.

## Synthetic data and boundaries

- All names, MRNs, order IDs and partner names in the
  [UAT scenario](uat.md) are invented. Never use real patient data in a test,
  a screenshot or a note.
- This guide documents workflow and recorded evidence. It does not approve a
  clinical policy, a channel or a partner; those are site and market decisions
  recorded in the specification's decision table.
- Electronic delivery, clinical review and patient release are separate
  authorizations. A clinician seeing a "delivered" or "accepted" state must not
  treat it as a completed clinical task.

## Open items

| Item | Why it is open | Who can close it |
|---|---|---|
| Site-approved channels, pharmacies and lab/imaging partners | Channel choice is configuration, not a documentation decision | Operations with the accountable clinical and market owners |
| Real clinical acceptance of this workflow | A synthetic UAT run proves the steps can be executed, not that clinicians accept them | G5 acceptance owners with authorized clinical participants |

Evidence source for this guide: the approved clauses in
[PROJECT_V1_SPEC.md](../../../.opencode/config/PROJECT_V1_SPEC.md), the existing
`prescription.rx` domain model (channel families, states and event vocabulary),
and the ordering tasks in `tasks/`. No human or clinical evidence is claimed.
