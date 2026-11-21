# UAT scenario: write and send a prescription or an order

This is a scenario for a **person** to run in a synthetic environment. It walks
one clinician through writing a prescription and placing lab and imaging orders,
sending them electronically, by print or both, handling a failed and an
ambiguous send, then cancelling, amending and reading a result. It exercises the
workflow described in the [clinician workflow guide](README.md).

Everything here is **synthetic**. Do not use real patient data, real credentials
or a real partner. Record what you observe; this is a rehearsal, not clinical or
G5 acceptance.

Requirements exercised: RX-02, LAB-01, RAD-01, XCH-02.

## Preconditions

| Item | Synthetic value or setup |
|---|---|
| Environment | A synthetic test clinic with the `synthetic-auth` profile, not a production tenant |
| Clinician | A test account with prescribing and ordering privilege in the clinic (and a second account without it, for the negative check) |
| Patient | Synthetic patient `Synthetic Patient A`, MRN `SYN-MRN-0001`, one active encounter |
| Pharmacy | `Synthetic Pharmacy Partner`, configured as an approved electronic prescribing channel |
| Lab and imaging partners | `Synthetic Lab Partner` and `Synthetic Imaging Partner`, both with an approved electronic interface |
| Print | A print preview / PDF destination available to the test user |
| Partner stub controls | A way to make a send succeed, fail after retries, or return an unknown (ambiguous) outcome on demand |
| Reset | A way to clear synthetic items between attempts so each run starts clean |

## Scenario

Do the steps in order. After each step, note the state shown, the event history
and what the patient would receive.

1. **Sign in and open the chart.** Sign in as the ordering clinician and open the
   chart of `Synthetic Patient A`. Confirm the patient and encounter shown match
   the selection. *Check:* you can see the chart but you cannot yet change any
   patient field by hand.

2. **Check the negative role.** In a second session, sign in as the account
   without ordering privilege and open the same chart. *Check:* that account
   cannot write a prescription or place an order; no send control is available.

3. **Write a prescription draft.** Back as the ordering clinician, choose a
   medicine from the catalog, review the tagged alternatives and the recorded
   allergies, and enter strength, dose, route, frequency and quantity with
   `Synthetic Pharmacy Partner` as the destination. Save the draft. *Check:* the
   draft is editable and is not yet attested.

4. **Attest and confirm market entitlement.** Attest to the draft and confirm the
   entitlement to prescribe. *Check:* attestation is a separate event; the
   prescription is now fixed and cannot be edited in place.

5. **Send the prescription electronically.** Choose electronic delivery and
   confirm. Make the stub report a clean success. *Check:* the recorded channel
   is the electronic prescribing channel; a stable submission reference appears;
   the history shows attestation, submission and delivery confirmation as
   separate events. *Check:* the state never claims pharmacy acceptance or
   dispensing yet.

6. **Produce a printable prescription.** Create a second prescription draft,
   attest it, choose print (the local printable channel) and confirm. *Check:* a
   printable document is produced; the state shows printed; the history has a
   print event and **no** submission or delivery-confirmation event. *Check:* the
   screen does not describe it as electronically delivered or accepted.

7. **Place a lab order electronically.** From the chart, search the catalog,
   select a lab test, set priority and preparation instructions (for example
   fasting), note the specimen type and site, choose **electronic**, and confirm.
   *Check:* the order is placed with one stable revision; the clinical question
   is not required for a lab order; the recorded destination is
   `Synthetic Lab Partner`; the state moves through sent to delivered when the
   stub succeeds.

8. **Place an imaging order with both modes.** Select an imaging study. *Check:*
   the clinical question is required — try to confirm without it and confirm the
   order is rejected with a clear reason. Enter the clinical question, set
   urgency, choose **both** electronic and print, and confirm after reading the
   confirmation wording. *Check:* the history records the electronic send and
   the printout, and the patient would receive both a direct send and a paper
   requisition.

9. **Fail a send, then switch to print.** Place another lab order sent
   electronically and set the stub to fail after the retry limit. *Check:* the
   order is marked as needing action, the failed attempts remain in the history,
   and the system has not silently created a second order. Choose **print
   instead** and confirm. *Check:* there is still exactly **one** order for this
   request, the printout is produced, and the earlier failed attempts are still
   visible.

10. **Ambiguous send: do not resend blindly.** Place an order and set the stub
    to return an unknown outcome. *Check:* the item enters reconciliation and the
    retry action is **blocked**; there is no enabled resend button. Ask for the
    partner status to be checked. Repeat for each of the three stub answers:
    - partner **has** the item: it reconciles to submitted and you continue;
    - partner **does not have** the item: retry becomes allowed and reuses the
      **same** submission reference; confirm the reference did not change;
    - partner status **unknown**: the item stays in reconciliation with retry
      still blocked.

11. **Manual delivery, if applicable.** If a destination has no approved
    electronic interface, record a manual delivery. *Check:* the record names
    who delivered it and when, and is labelled manual — not electronic delivery
    and not clinical acknowledgment.

12. **Read the three state families.** Open an order that has reached acceptance.
    *Check:* the workflow state (for example accepted/processing), the
    transmission state (delivered) and the clinical-review state (still
    unreviewed or acknowledged as appropriate) are shown separately. *Check:* a
    transmission acknowledgment did not mark the clinical task resolved.

13. **Cancel before acceptance.** Place a lab order sent electronically, leave it
    unaccepted, and cancel it with a reason. *Check:* the state becomes cancelled,
    the reason is in the history, and the order cannot move forward again.

14. **Cancel after acceptance.** Place another order and let the partner accept
    it. Try to cancel. *Check:* the action is a **cancel request to the partner**,
    not an outright cancellation, and the order waits for the partner's response.

15. **Amend an order.** Place and accept an order, then amend it. *Check:* a new
    revision is created, the prior revision is still visible, and a partner
    notification is recorded. *Check:* the original placed item was not edited
    in place.

16. **See the result.** For the lab order, have the synthetic lab post a result.
    *Check:* the result links back to the order and appears in the order status.
    For an imaging order, have the synthetic imaging partner post a signed report
    and an addendum. *Check:* the report and addendum link to the order. For a
    critical result, *check* that it enters the separate critical-result flow
    with an owner, a due time and a required clinician acknowledgment, and that
    delivery alone does not satisfy that acknowledgment.

17. **Check the language.** Repeat steps 3–9 with the interface in Arabic.
    *Check:* the delivery-mode wording in the confirmation is stated in words,
    is understandable, and no clinical detail leaks into the page or tab title.

18. **Reset.** Clear the synthetic items and the stub settings so the next run
    starts from the preconditions.

## Pass criteria

- [ ] Every numbered step completes with the stated check observed.
- [ ] A printed item is never shown as electronically delivered or accepted.
- [ ] A failed send keeps one order and a clear "needs action" state; no duplicate is created.
- [ ] An ambiguous send is reconciled before any retry, and a permitted retry reuses the same submission reference.
- [ ] Workflow, transmission and clinical-review states are distinct; a transmission acknowledgment never closes a clinical task.
- [ ] Cancel before acceptance needs a reason; cancel after acceptance is a request to the partner; amend creates a visible new revision.
- [ ] Lab results and imaging reports link back to their order; a critical result follows its own acknowledgment flow.
- [ ] All data used is synthetic; no real identifier or credential appears in any note or screenshot.

## Evidence to capture

For each run, record only synthetic identifiers and observations: the test
patient (`SYN-MRN-0001`), the prescription/order IDs, the recorded channel and
submission reference, the state and event history at the decision points, and
screenshots with synthetic values. Note the environment, the date, the tester and
which stub answer was used at each step. Do not paste raw logs containing real
tokens or patient data.

## Limitations

This scenario can show that the steps are executable and that the states and
events behave as specified. It cannot approve a clinical policy, a channel or a
partner, and a synthetic pass is not real clinical or G5 acceptance. Those
sign-offs are recorded in the specification's decision table and remain with the
accountable owners.
