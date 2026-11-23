# MSG-06 clause 6 — use the site's approved urgent and downtime channels

## Clause

Use the site’s approved urgent and downtime channels for time-sensitive care.

## Status

Not built

## Evidence

Complete-but-disabled HTTP-level proof:

- Test class: `com.aurevia.smartclinic.messaging.clauses.UseSiteSApprovedUrgentDowntimeChannelsTest`
  (`apps/api/src/test/java/com/aurevia/smartclinic/messaging/clauses/UseSiteSApprovedUrgentDowntimeChannelsTest.java`).
  It mirrors the four existing GAP clause tests: `@Tag("integration")`,
  `@SpringBootTest` on a random port with one per-class PostgreSQL database migrated
  from `db/migrations`, `@ActiveProfiles("synthetic-auth")`, `X-Synthetic-Subject`
  and `X-Active-Organization` headers, and a synthetic tenant seeded through
  `public.config_entry`/`public.config_approval`. The site-channel policy keys used
  are `msg06.approved_urgent_channels` and `msg06.approved_downtime_channels`
  (comma-separated codes), following the `rx02.approved_channels` convention.
- `publishApprovesAndServesSiteApprovedUrgentAndDowntimeChannels` — anchor for the
  rule being met. Publishes with the site-approved urgent and downtime codes,
  approves with a distinct named approver, then asserts through
  `GET /api/v1/care-units/{unitId}/service-profile` that the served profile and the
  committed `service_profile` row carry exactly those approved codes. This half
  passes today because publish already stores any bounded free text; it is not
  evidence of enforcement on its own.
- `publishRefusesUnapprovedAndBlankUrgentDowntimeChannels` — the assertion that
  actually distinguishes approved from unapproved. A structurally valid but
  unapproved urgent code must be refused with a structured 422 carrying
  `UNAPPROVED_CHANNEL`; a blank downtime value must also be refused with 422;
  neither unit may gain a `service_profile` row; and both refusals must be audited
  as `PROFILE_PUBLISH`/`DENIED` with no `SUCCESS` for either unit. These assertions
  fail today: publish returns 201 for the unapproved code, and a blank value is
  rejected inside the input parser without reaching the service's audit path.
- Both methods are covered by one class-level `@Disabled("GAP: …")`, as the story
  requires for an unbuilt clause. A targeted JUnit run therefore executes zero
  tests; the evidence for "Not built" is the survey below plus a complete test
  that compiles against today's contract and would fail if enabled.

Production classes the enabled tests would exercise through the normal HTTP entry
point, none of which implements this clause today:

- `com.aurevia.smartclinic.messaging.service.ServiceProfileController` — thin
  adapter for `GET /api/v1/care-units/{unitId}/service-profile` and
  `POST /api/v1/care-units/{unitId}/service-profiles`.
- `com.aurevia.smartclinic.messaging.service.ServiceProfileService` — `publish`
  captures `urgentChannel` and `downtimeChannel` through `safeText`, and `deny`
  audits refusals; it never consults an approved-channel policy.
- `com.aurevia.smartclinic.messaging.service.ServiceProfileInputs` — `safeText`
  checks only non-blank, at most 128 characters and no eight-digit PHI-shaped run;
  it does not check membership in an approved set.
- `com.aurevia.smartclinic.messaging.service.ServiceProfileContract` —
  `ServiceProfile` and `PublishProfileRequest` carry the two channel fields as
  free text.
- `com.aurevia.smartclinic.messaging.service.JdbcServiceProfileStore` and
  `db/migrations/V92__service_profiles.sql` — persist `urgent_channel` and
  `downtime_channel` as unconstrained `text` with tenant-forced row level security.

Survey result: no class under `apps/api/src/main/java/com/aurevia/smartclinic/messaging`
or its neighbours checks an urgent or downtime channel against a site-approved
registry, and no class dispatches time-sensitive care over those channels.
Approved-channel enforcement exists only in other domains and is not wired here:
`com.aurevia.smartclinic.prescription.rx.RxService.requireApprovedChannel`
(`rx02.approved_channels` via `public.effective_config`),
`com.aurevia.smartclinic.exchange.critical.CriticalResultService` (approved
primary and backup escalation channels), and
`com.aurevia.smartclinic.orderentry.delivery.ApprovedChannelRegistry`. Their
fixtures prove other requirements and were deliberately not reused.

## Gap

The site-approved-channel rule for time-sensitive care is not built. Publish
captures and displays the two channel fields, but nothing constrains them to a
site-approved set, nothing refuses an unapproved channel, and nothing dispatches
urgent or downtime care over an approved channel. Closing the gap needs **one**
bounded task in the messaging domain: add a tenant-owned approved-channel policy
(or reuse the versioned `config_entry`/`config_approval` fold), enforce membership
for both urgent and downtime channels inside the tenant-guarded publish
transaction, route the refusal through the service's audited `deny` path with a
stable `UNAPPROVED_CHANNEL` code, and enable the two tests above. Files that task
would change (none changed here): a new policy/registry class and its test under
`apps/api/src/main/java/com/aurevia/smartclinic/messaging/service` and
`apps/api/src/test/java/com/aurevia/smartclinic/messaging/service`,
`ServiceProfileService.java`, `ServiceProfileInputs.java` (or the new policy
boundary), their tests, and the OpenAPI entry for the publish route. No migration
is required if the versioned configuration fold is reused; if a dedicated
approved-channel catalog is introduced, it needs its own Flyway migration with
`organization_id`, RLS and FORCE RLS. The implementing task should keep the two
proposed policy key names and the `UNAPPROVED_CHANNEL` wire code used by the
disabled test so the proof need not be rewritten.
