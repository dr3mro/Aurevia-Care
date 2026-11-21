# Laboratory task guide

- Requirements: PRD-01, UX-01
- Role in the staff web: `lab`
- Planned screens: Orders, Specimen accessions

No laboratory screen exists in the code today. The steps below describe the work
this role will do. Each step is marked `PLANNED:` with the id of the task that
will add the screen, and the wording comes from the approved design and the
task details, not from a running screen, so button names may change when the
screen is built. If you follow a lab link in the portal now, you see "Page not
found" with "The page you requested does not exist. Go home to continue." and a
"Go home" button. Nothing is loaded or shown.

## What you will do most

1. `PLANNED: READY-26` — **See incoming orders.** Screen: **Orders**. A table
   lists orders with a status filter and paging. Open a row to see **Order
   detail**, the order fields and a task state timeline. You should see the
   order you can act on and the actions your role is allowed to use.

2. `PLANNED: READY-26` — **Accept an order.** Screen: **Order detail**. Press the
   accept action. You should see the task state move forward in the timeline.

3. `PLANNED: READY-26` — **Reject or ask for clarification.** Screen: **Order
   detail**. Press reject or clarify and enter a reason in the form. You should
   see the reason recorded and the state updated. A missing reason blocks the
   action; a stale version shows the conflict state and keeps what you typed.

4. `PLANNED: READY-28` — **Create an accession.** Screen: **Specimen accessions**
   (`/lab/accessions`). Press the button to accession a requisition and fill the
   requisition id, specimen type and site. You should see the issuer-scoped
   accession id returned after creation.

5. `PLANNED: READY-28` — **Move a specimen through its states.** Screen:
   **Accession detail**. Use only the actions the current state allows:
   collect, receive, reject or recollect. Reject asks for a reason and offers
   recollect. You should see the new state and its event in the history. An
   action the state does not allow is not offered.

6. `PLANNED: BUILD-69` — **Review results.** Screen: **Results**. The inbox shows
   reports needing review and separates review state from delivery state. Press
   the acknowledge action on a report. You should see the report marked as
   reviewed; the critical queue, when present, orders items by their due time
   and marks overdue items with words as well as colour.

Use the [Reception](reception.md) and [Clinician](clinician.md) guides for the
screens that are available today.
