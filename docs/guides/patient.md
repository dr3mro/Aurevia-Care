# Patient task guide

- Requirements: PRD-01, UX-01
- App: Aurevia Care (iOS and Android)
- Screens you use: Appointments, Reports, Messages, Account

These are the things you do most in the patient app. The app supports English
and Arabic; in Arabic the screens mirror right to left and the tabs read
"المواعيد", "التقارير", "الرسائل" and "الحساب". Appointments, reports and
conversations come from the authenticated service for your account. All names
and values in the product are synthetic.

## What you do most

1. **Open the app and choose a tab.** Screen: **Aurevia Care**. Four tabs sit at
   the bottom: "Appointments", "Reports", "Messages" and "Account". Each has an
   accessibility label such as "Appointments tab". You should see the app title
   and the title of the selected tab.

2. **See your appointments.** Screen: **Appointments**. Each card shows a status
   pill (for example "Please confirm", "Confirmed {time}" or "Completed") with
   the service status in small secondary text, then the date and time in the
   facility time zone. A generic notice appears above the cards. Appointments
   appear in the order returned by the service; the installed screen does not
   sort them. With none, you see "No appointments".

3. **Confirm or inspect an appointment.** Screen: **Appointments**. On a card
   whose status is `booked` and where confirmation is enabled, press "Confirm";
   you should see "Confirmed at" followed by the time. Press "View details" to
   open a detail card for the appointment already loaded on the list; no
   separate detail request is made. On an active device you can press
   "Revoke device" to remove it.

4. **Read a released report.** Screen: **Reports**. Each card shows "Revision",
   the issuing organization and the effective date. A corrected report shows the
   line "Corrected revision {revision} — {reason}"; the display direction follows
   the report's language. Press "Download PDF"; the app asks you to confirm and
   checks the server authorization for that exact revision. In the installed
   build no local copy is saved yet because the document handoff is deferred,
   so no download begins. With nothing released you see "No released reports".

5. **Message a care unit.** Screen: **Messages**. The inbox lists each unit with
   its state and an unread count. You should see your conversations and their
   unread counts. With none, you see "No conversations". Opening a conversation
   to read messages and use the "reply input" and "Send" button is
   `PLANNED: BUILD-71`: today a row press does not open a thread.

6. **Review attachments before sending.** Screen: **Review attachments** under
   Messages. The installed Messages tab supplies no pending files, so the screen
   shows "No pending files." and the "Remove", "Replace", "Download" and
   "Send to unit" controls are placeholders. The scan-state flow is
   `PLANNED: BUILD-71`; nothing can be sent from this tab yet.

7. **Acknowledge an instruction.** Screen: **Instructions** under Messages.
   The installed tab supplies no instruction projections, so no card is shown.
   The card and acknowledgment flow are `PLANNED: BUILD-71`.

8. **Manage your account.** Screen: **Account**. The account menu opens
   "Enrollment", "Recovery", "Support" and "Lockout" screens; use "Back" to
   return to the menu. Enrollment accepts an enrollment code, recovery accepts
   a verification code, and Support takes a category and a message. If your
   session expires, you see "Signed out" and "Your session ended for your
   security. Sign in to continue." with a "Sign in" action.
