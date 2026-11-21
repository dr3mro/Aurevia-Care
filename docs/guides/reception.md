# Reception task guide

- Requirements: PRD-01, UX-01
- Role in the staff web: `reception`
- Screens you use: Scheduling, Patients

These are the things you do most at the front desk. Open the staff portal and
switch to the right active organization before you start; every screen reads
and writes data for that organization only. Names and identifiers in the
product are synthetic.

## What you do most

1. **See today's appointments.** Screen: **Scheduling**. Under "Worklist range"
   choose "Day" or "Week". You should see the "Appointments by scheduled time"
   table with the columns "Patient", "Time", "Protected resource", "Visit
   state", "Assign to" and "Actions". Each row shows a state word: "Booked",
   "Checked in", "In progress", "Completed", "Cancelled" or "No-show". An empty
   range shows "No appointments in this range."

2. **Check a patient in.** Screen: **Scheduling**. In the row's "Actions", press
   "Check in". The button is only available while the state is "Booked". You
   should see "Command sent to the appointment API" in the "Scheduling status"
   line; after the list reloads the badge reads "Checked in".

3. **Assign an appointment.** Screen: **Scheduling**. In the "Assign to" column
   of a row, choose "Unassigned", "Synthetic Reception" or "Synthetic Nurse".
   You should see the chosen name in that cell. A row in a completed state
   cannot be reassigned.

4. **Book an appointment.** Screen: **Scheduling**. In "Book an appointment",
   fill "Facility code", "Patient identifier", "Visit type", "Protected
   resource" and "Start instant (UTC)", then press "Book". All five fields are
   needed. "Enter the clinician resource code. The room is not selected in this
   screen." explains the resource field. You should see "Command sent to the
   appointment API" and a new row in the worklist. If the resource is already
   taken, you see "Protected resource is already occupied in this organization
   at this time." and the booking is not sent.

5. **Load more appointments.** Screen: **Scheduling**. Press "Load more" when
   the worklist is paginated. You should see more rows added below the existing
   ones.

6. **Find a patient.** Screen: **Patients**. Enter at least two characters in
   "Search by name" and press "Search". You should see the "Patient search
   results" table with "Name", "MRN", "Issuer" and "Date of birth". Open a name
   to see "Patient overview". Fewer than two characters shows "Enter at least
   two characters."

7. **Register a patient.** Screen: **Patients**. Press "Register". The "Register
   patient" form has the fields "Display name", "MRN", "MRN issuer", "Date of
   birth (YYYY-MM-DD)", "Unknown-date reason (code)" and "Language code".
   Press "Save registration". You should see "Registered {name}" and the new
   row in the results. Invalid fields are listed under "Fix the following
   fields". Press "Cancel" to close the form without saving.

8. **Check for a duplicate before using the chart.** Screen: **Patient
   overview**. If the system finds a possible match you see the alert "Likely
   duplicate records" with the candidate names and MRNs; nothing is merged.
   Press "Open chart" to continue to the clinician's chart. The dedicated
   duplicate review worklist is `PLANNED: BUILD-48`, and the separate "Check-in"
   navigation page is `PLANNED: BUILD-67`; today check-in is done from the
   Scheduling worklist in step 2.
