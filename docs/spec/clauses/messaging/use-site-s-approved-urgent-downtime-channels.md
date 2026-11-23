# MSG-06 clause 6 — use the site's approved urgent and downtime channels

## Clause

Use the site’s approved urgent and downtime channels for time-sensitive care.

## Status

Proven by tests

## Evidence

Two enabled HTTP-level acceptance tests exercise the clause through the normal
`POST /api/v1/care-units/{unitId}/service-profiles` entry point:

- Test class: `com.aurevia.smartclinic.messaging.clauses.UseSiteSApprovedUrgentDowntimeChannelsTest`
  (`apps/api/src/test/java/com/aurevia/smartclinic/messaging/clauses/UseSiteSApprovedUrgentDowntimeChannelsTest.java`).
  It uses `@Tag("integration")`, `@SpringBootTest` on a random port with one
  per-class PostgreSQL database migrated from `db/migrations`,
  `@ActiveProfiles("synthetic-auth")`, `X-Synthetic-Subject` and
  `X-Active-Organization` headers, and a synthetic tenant whose approved-channel
  policy is seeded through `public.config_entry`/`public.config_approval`.
  Both tests run; neither is skipped.
- `publishApprovesAndServesSiteApprovedUrgentAndDowntimeChannels` — publishes
  with the site-approved urgent and downtime codes, approves with a distinct
  named approver, then asserts through
  `GET /api/v1/care-units/{unitId}/service-profile` that the served profile and
  the committed `service_profile` row carry exactly those approved codes.
- `publishRefusesUnapprovedAndBlankUrgentDowntimeChannels` — a structurally
  valid but unapproved urgent code is refused with a structured 422 carrying
  `UNAPPROVED_CHANNEL`; a blank downtime value is also refused with 422;
  neither unit gains a `service_profile` row; and both refusals are audited as
  `PROFILE_PUBLISH`/`DENIED` with no `SUCCESS` for either unit.

Production classes the tests exercise through that entry point:

- `com.aurevia.smartclinic.messaging.service.ServiceProfileController` — thin
  adapter for `GET /api/v1/care-units/{unitId}/service-profile` and
  `POST /api/v1/care-units/{unitId}/service-profiles`.
- `com.aurevia.smartclinic.messaging.service.ServiceProfileService` — `publish`
  now requires both channels to be members of the tenant's effective,
  named-approved policy before storing a draft. A blank, absent or unapproved
  code is refused through the existing audited `deny` path with a stable
  `UNAPPROVED_CHANNEL` code, so no row is written and a `DENIED` audit is kept.
- `com.aurevia.smartclinic.messaging.service.ServiceProfileStore` and
  `com.aurevia.smartclinic.messaging.service.JdbcServiceProfileStore` — the new
  `approvedChannels` read resolves the comma-separated codes from
  `public.effective_config(key)` inside the tenant-guarded transaction, with an
  explicit organization predicate as defence in depth. An empty or unapproved
  policy yields an empty set and fails closed.
- `com.aurevia.smartclinic.messaging.service.ServiceProfileInputs` — continues
  to bound and de-identify the free-text fields; the channel membership check
  runs before it so a blank channel also reaches the audited denial path.
- `com.aurevia.smartclinic.messaging.service.ServiceProfileContract` —
  `ServiceProfile` and `PublishProfileRequest` carry the two channel fields.
- `db/migrations/V5__tenant_configuration.sql` supplies the immutable, approved
  version fold; `db/migrations/V92__service_profiles.sql` persists the channels
  as tenant-forced-RLS `text`. No migration was needed for this clause.

Policy keys `msg06.approved_urgent_channels` and
`msg06.approved_downtime_channels`, and the `UNAPPROVED_CHANNEL` wire code,
follow the established approved-channel convention in
`com.aurevia.smartclinic.prescription.rx.RxService`.

Entry-point-to-test trace:
`POST /api/v1/care-units/{unitId}/service-profiles` →
`ServiceProfileController.publish` → `ServiceProfileService.publish` →
`ServiceProfileStore.approvedChannels` / `ServiceProfileStore.insertProfile` →
`JdbcServiceProfileStore` → `service_profile` and `service_profile_audit` rows
asserted by the test; `GET /api/v1/care-units/{unitId}/service-profile` →
`ServiceProfileController.view` → `ServiceProfileService.viewProfile` →
`JdbcServiceProfileStore.effectiveProfile`.

## Gap

None for the publish-time enforcement above. The proof covers accepting and
serving site-approved codes and refusing unapproved or blank codes. Actual
dispatch of time-sensitive care over a channel is not performed by this module
and is outside this proof.
