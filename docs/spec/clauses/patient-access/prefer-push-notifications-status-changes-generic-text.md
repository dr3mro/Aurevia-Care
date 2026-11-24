## Clause

Prefer push notifications for status changes with generic text.

## Status

Partly proven

## Evidence

All links are relative to this file; the repository root is four levels up. Only repository files are cited. No workflow-run output, pilot observation, partner confirmation or signed decision is stored in the repository, so none is claimed here.

**Patient app (synthetic fixtures only).** The enabled acceptance test [prefer-push-notifications-status-changes-generic-text.test.ts](../../../../apps/patient-mobile/src/spec/patient-access/prefer-push-notifications-status-changes-generic-text.test.ts) proves only this clause with three named tests:

- `uses only generic text when an appointment status changes` — `buildPushPayload` on a synthetic cancelled appointment returns the fixed generic notice; the approved title and body are asserted as exact literals and none of the patient name, facility, status, result, start instant or local id appears in the notice text, while the opaque detail reference is preserved. The test also drives the authenticated `notificationContent` path with that clinical fixture available and confirms the notice still carries no clinical detail.
- `withholds clinical detail and keeps the notice generic before sign-in` — `notificationContent` with an unauthenticated state never calls the detail fetcher, returns the authentication-required marker, and still emits only the generic notice text.
- `refuses to deliver a status notice to another tenant` — in the negative dispatch, `dispatchNotice` with a registry holding only a peer-organization device and another patient's device in the active organization calls no gateway, returns no outcomes, and leaves the inbox and device registry unchanged. A positive control then adds one eligible device for the verified patient and confirms exactly one delivery of the approved generic notice with an accepted outcome.

**Production helpers exercised (no production file was changed).**

- [notifications.ts](../../../../apps/patient-mobile/src/features/appointments/notifications.ts) owns `GENERIC_NOTICE`, `buildGenericNotice` and the authentication-gated `detailAfterAuth`.
- [payload.ts](../../../../apps/patient-mobile/src/features/notifications/payload.ts) exposes `buildPushPayload` and `notificationContent`.
- [delivery.ts](../../../../apps/patient-mobile/src/features/notifications/delivery.ts) exposes `dispatchNotice`, which delivers only to eligible devices and never filters the authenticated inbox.
- [tenancy.ts](../../../../apps/patient-mobile/src/features/appointments/tenancy.ts) and [devices.ts](../../../../apps/patient-mobile/src/features/appointments/devices.ts) supply `tenantKey` and `eligibleTokens`, the tenant and patient isolation boundary.
- [types.ts](../../../../apps/patient-mobile/src/features/appointments/types.ts) supplies the non-clinical `GenericNotice`, `AuthState` and `DeviceToken` types.

The same helpers are independently covered by [notifications.test.ts](../../../../apps/patient-mobile/src/features/appointments/notifications.test.ts), [payload.test.ts](../../../../apps/patient-mobile/src/features/notifications/payload.test.ts) and [delivery.test.ts](../../../../apps/patient-mobile/src/features/notifications/delivery.test.ts).

**API context (read-only, not exercised by the patient-app tests here).** [PatientAppointmentService.java](../../../../apps/api/src/main/java/com/aurevia/messaging/appointments/PatientAppointmentService.java) sends `GenericPushNotice.appointmentUpdate()` from `notifyStatusChange` and its private `dispatch`, recording the new status only in an audit key; [PatientAppointmentController.java](../../../../apps/api/src/main/java/com/aurevia/messaging/appointments/PatientAppointmentController.java) exposes `POST /api/v1/patient/appointments/{appointmentId}/notify`.

## Gap

The app proves that a generic, content-free notice is built and gated correctly, but no app path triggers it when a status changes and no real provider delivers it. Three separate tasks are needed:

1. **App-level status-change to push trigger (one code task).** No screen, provider or API client calls `notificationContent` or `dispatchNotice` on an appointment status change. The app shell still passes placeholder actions in `apps/patient-mobile/src/app/patientPages.tsx` and `apps/patient-mobile/src/app/RootNavigator.tsx`. A trigger in the notifications feature, wired from the status-change path with a test, would change those files and add a new module and its test.
2. **Real push gateway (one infrastructure/partner task).** The API push boundary is synthetic and always reports delivery; `SyntheticPatientAppointmentPorts` still lives under `src/main`. A real APNs/FCM adapter, cloud configuration and DevSecOps review are required before a notice reaches a device.
3. **Real-device evidence (one human/partner-gated task).** Confirming that generic notices reveal no clinical content on a real device and recording site acceptance cannot be created from repository code; it belongs to the G5 gate and the launch checklist.
