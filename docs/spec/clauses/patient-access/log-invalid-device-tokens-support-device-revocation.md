# PAT-01 clause 4 — Log invalid device tokens and support device revocation

## Clause

Log invalid device tokens and support device revocation.

## Status

Proven by tests

## Evidence

Test class: `log-invalid-device-tokens-support-device-revocation.test.ts`
(path: `apps/patient-mobile/src/spec/patient-access/log-invalid-device-tokens-support-device-revocation.test.ts`),
a focused patient-mobile Jest suite using synthetic fixtures and injected plain-object
boundaries (no jest mocks, no timers). All three tests are active (none skipped) and pass
with `bash apps/patient-mobile/check.sh`, which also enforces 100% coverage.

- `logsInvalidTokenWithoutTheProviderTokenAndStopsEligibility` — rule met. A provider
  rejection disposition (`kind: 'invalid'`, reason `provider_rejected`) is dispatched through
  `dispatchNotice`; the emitted `InvalidTokenEvent` carries `deviceId`, `patientId`, `reason`
  and the `at` instant, `JSON.stringify` of the logged events never contains the provider
  token value, the stored device becomes `invalid`, `eligibleTokens` then returns no device,
  and the authenticated inbox is unchanged.
- `signOutRevocationStopsDeliveryOnlyForTheOwnedDevice` — rule met and negative boundary.
  `commandForSession` maps a `signed_out` session event to an `unregister` command and
  `applyRegistration` revokes the owned device; the device is no longer eligible, the source
  registry is unchanged, the other patient's device is untouched, and a subsequent
  `dispatchNotice` sends nothing and logs nothing for that patient.
- `refusesToRevokeOrInvalidateAPeerTenantOrAnotherPatientsDevice` — negative proof of
  isolation. `withRevokedDevice` and `withInvalidatedDevice` leave a peer-tenant device, a
  device owned by another patient in the same tenant, and a missing device all unchanged, so
  neither operation can cross a tenant or verified-patient boundary.

Production symbols exercised (no production file was changed by this task):

- `apps/patient-mobile/src/features/notifications/delivery.ts` — `dispatchNotice` drives the
  invalid-token branch and delegates eligibility and invalidation to the appointments domain.
- `apps/patient-mobile/src/features/notifications/registration.ts` — `commandForSession` and
  `applyRegistration` translate the signed-out transition into a tenant- and patient-scoped
  revocation.
- `apps/patient-mobile/src/features/appointments/devices.ts` — `eligibleTokens`,
  `invalidateToken`, `withInvalidatedDevice` and `withRevokedDevice` implement the
  non-sensitive event, the delivery state and the tenant/patient-scoped persistence.
- `apps/patient-mobile/src/features/appointments/tenancy.ts` and `types.ts` — `tenantKey` and
  the `DeviceToken`, `InvalidTokenEvent`, `InvalidTokenReason` and `GenericNotice` types.
- `apps/patient-mobile/src/features/notifications/types.ts` — the `DeliveryDisposition`,
  `SessionEvent`, `RegistrationCommand`, `NoticeLogger` and `PushGateway` types carried by the
  injected boundaries.

## Gap

None for the clause rule: it is built and proven by active tests. This task added proof only;
it did not change production code, an existing test, the contract or a migration.

One adjacent, non-blocking debt is recorded for completeness and is deliberately *not* a
skipped test: the rule is implemented in the domain and its unit tests, but the app has not
wired it to a production entry point. In `apps/patient-mobile/src/app/patientPages.tsx` the
appointment screen receives placeholder devices and `onRevokeDevice={placeholderAction}`, and
`dispatchNotice` has no production caller outside its tests. Connecting a real revocation
handler and a real push-dispatch caller (and their integration tests) is a separate slice
outside this test-only scope and is not required to prove that invalid tokens are logged
without their value and that revocation stops delivery.
