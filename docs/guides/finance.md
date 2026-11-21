# Finance task guide

- Requirements: PRD-01, UX-01
- Role in the staff web: `finance`
- Planned screens: Finance

No finance screen exists in the code today. The steps below describe the work
this role will do. Each step is marked `PLANNED:` with the id of the task that
will add the screen, and the wording comes from the approved design and the
task details, not from a running screen, so button names may change when the
screen is built. If you follow the "Finance" link in the portal now, you see
"Page not found" with "The page you requested does not exist. Go home to
continue." and a "Go home" button. Nothing is loaded or shown.

## What you will do most

1. `PLANNED: READY-30` — **Find an invoice.** Screen: **Invoices**. The list has
   a status filter and paging. Open a row for **Invoice detail**. You should see
   the invoice lines, the balance, the currency and any payments or refunds.

2. `PLANNED: READY-30` — **Issue an invoice.** Screen: **Invoices**. Create the
   invoice and press the issue action. You should see the status move to issued
   and the balance become payable. Amounts are whole minor units with one
   currency; the screen formats money for the active language and never uses
   floating-point arithmetic on amounts.

3. `PLANNED: READY-30` — **Record a payment.** Screen: **Invoice detail**. Enter
   the payment and submit it. You should see the payment in the list and the
   balance reduced. Retrying the same payment reuses the same idempotency key,
   so it is not taken twice.

4. `PLANNED: READY-30` — **Refund a payment.** Screen: **Invoice detail**. Give a
   reason and an independent approver, then submit the refund. You should see
   the refund recorded and the balance updated. A missing reason or approver
   blocks the action.

5. `PLANNED: READY-30` — **Void an invoice.** Screen: **Invoice detail**. Give a
   reason and an independent approver, then void the invoice. You should see the
   status change to voided; the original record stays in the history.

6. `PLANNED: BUILD-51` — **Export tenant data.** Screen: **Data export and
   import**. Request an export and download it from the list. An expired export
   is shown as expired and is not downloadable. An import first shows a dry-run
   report listing unsupported fields; nothing is imported until the sign-off
   step.
