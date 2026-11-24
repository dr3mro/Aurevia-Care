- [x] FIX-PATIENT-APPOINTMENT-ACTIONS · Wire appointment details and device revocation in the patient app
  - evidence: PR #1093 merged to main (reconciled 2026-11-23)
  - pr: #1093
  - attempts: 1
  - requirements: PAT-01
  - scope: apps/patient-mobile/src/app, apps/patient-mobile/src/features/appointments
  - depends: QA-SOURCE-01
  - details:
    Historical brief. Use the recorded evidence and PR to locate the delivered change before starting a follow-up.
    Current reference points to verify against that delivery are `apps/patient-mobile/src/features/appointments/DeviceRevocationConfirm.tsx`, `apps/patient-mobile/src/features/appointments/devices.ts`.

    AppointmentListPage uses placeholder handlers for details and device revocation. Use the existing navigation and authenticated device-management client/contract.
    Acceptance: opening details selects the intended appointment; cancelling device revocation sends no request; confirming sends one request and refreshes devices; handle unauthorized, already revoked and transport errors. Render the installed signed-in page in tests and run the patient app checks.
