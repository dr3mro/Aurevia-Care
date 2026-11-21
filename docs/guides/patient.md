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

2. **See your appointments.** Screen: **Appointments**. Each card shows the
   status word from the service — `booked`, `checked_in`, `in_progress`,
   `completed`, `cancelled` or `no_show` — followed by the date and time with the
   facility time zone and the visit line. A generic notice appears above the
   cards. You should see your appointments in time order. With none, you see
   "No appointments".

3. **Confirm or inspect an appointment.** Screen: **Appointments**. On a card
   whose status is `booked` and where confirmation is enabled, press "Confirm";
   you should see "Confirmed at" followed by the time. Press "View details" to
   request the authenticated detail view. On an active device you can press
   "Revoke device" to remove it.

4. **Read a released report.** Screen: **Reports**. Each card shows "Revision",
   the issuing organization and the effective date. A corrected report shows the
   line "Corrected revision {revision} — {reason}"; the display direction follows
   the report's language. Press "Download" to save that exact revision. You
   should see the download begin for the revision you chose. With nothing
   released you see "No released reports".

5. **Message a care unit.** Screens: **Messages** → conversation inbox →
   conversation thread. The inbox lists each unit with its state and an unread
   count; press a row to open it. In the thread, read the messages in order, type
   in the "reply input" and press "Send". You should see the reply in the
   conversation. A closed thread or revoked connection shows "This conversation
   is read only." and has no send button. With no conversations you see "No
   conversations".

6. **Review attachments before sending.** Screen: **Review attachments** under
   Messages. Each pending file shows its "Scan state": "In quarantine — not
   accessible yet", "Clean — ready after authorized send" or "Scan failed —
   remove or replace". Use "Remove", "Replace" or "Download", then "Send to
   unit". You should see "Nothing is sent until you press Send." before you
   act; nothing leaves the app until you press the button.

7. **Acknowledge an instruction.** Screen: **Instructions** under Messages. A
   card that needs action shows "Acknowledgment requested" (or "Acknowledgment
   requested - overdue" when late) and a button such as "Acknowledge receipt of
   instruction revision {revision}". Press it. You should see "Receipt
   acknowledged". A withdrawn instruction shows "Instruction withdrawn" and no
   button.

8. **Manage your account.** Screen: **Account**. The account menu opens
   "Enrollment", "Recovery", "Support" and "Lockout" screens; use "Back" to
   return to the menu. Enrollment accepts an enrollment code, recovery accepts
   a verification code, and Support takes a category and a message. If your
   session expires, you see "Signed out" and "Your session ended for your
   security. Sign in to continue." with a "Sign in" action.
