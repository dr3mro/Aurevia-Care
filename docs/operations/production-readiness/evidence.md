# WEB-01 to WEB-13 evidence register

This table maps each requirement in section 8.1 ("Production platform and web
delivery") of the [Orevia Cloud V1 specification](../../../.opencode/config/PROJECT_V1_SPEC.md)
to the repository evidence that proves it, for the G5 launch-readiness review.
It is a snapshot of this checkout: a `proven by test` row means a committed
test asserts the named behavior, not that a production deployment has been
accepted or signed off. Paths are relative to the repository root. The adjacent
[evidence register](README.md) and [launch checklist](launch-checklist.md) carry
the owner and sign-off state that this table does not.

Status words:

- `proven by test` — a test class or test file in this repository asserts the requirement's behavior.
- `proven by document` — only a document exists; no test asserts the behavior.
- `needs infrastructure owner` — the specification assigns verification to a person outside worker scope, such as a TLS scan, WAF test, signed artifact or penetration test.
- `missing` — no repository evidence and no named owner path was found.

| Requirement | Repository evidence | Status |
|---|---|---|
| WEB-01 | `docs/operations/production-readiness/launch-checklist.md` (TLS/WAF row); `apps/api/src/main/java/com/aurevia/web/SecurityHeadersFilter.java` and `apps/api/src/test/java/com/aurevia/web/SecurityHeadersFilterTest.java` (HSTS on secure requests); `apps/api/src/main/resources/application-production.yml` (forwarded headers from the TLS edge) | needs infrastructure owner |
| WEB-02 | `apps/api/src/test/java/com/aurevia/web/SecurityHeadersFilterTest.java` (CSP, frame-ancestors, nosniff, no-referrer, Permissions-Policy, `Cache-Control: no-store`, HSTS); `apps/api/src/main/java/com/aurevia/web/SecurityHeadersFilter.java`; `apps/api/src/test/java/com/aurevia/web/BrowserSessionHttpTest.java` (session cookies). Not yet covered: cookie Secure or SameSite attributes and the no-patient-data-in-URLs or client-storage rule | proven by test |
| WEB-03 | `apps/api/src/test/java/com/aurevia/web/AbsoluteSessionTimeoutFilterTest.java`; `apps/api/src/test/java/com/aurevia/web/BrowserSessionHttpTest.java`; `apps/api/src/test/java/com/aurevia/web/StaffWebLoginConfigurationTest.java`; `apps/api/src/test/java/com/aurevia/web/AudienceValidationHttpTest.java`; `apps/api/src/test/java/com/aurevia/web/SecurityFilterChainHttpTest.java`. Not yet covered: session list and revoke, MFA, lockout and mobile PKCE | proven by test |
| WEB-04 | `apps/api/src/test/java/com/aurevia/web/RateLimiterTest.java`; `apps/api/src/test/java/com/aurevia/web/RateLimitInterceptorTest.java`; `apps/api/src/test/java/com/aurevia/contract/OpenApiErrorRulesTest.java`; `docs/operations/production-readiness/launch-checklist.md` (WAF/DDoS row). CI-producible test gaps remain for exact-origin CORS, header/body/time caps and error shape | needs infrastructure owner |
| WEB-05 | `apps/api/src/test/java/com/aurevia/web/HealthControllerTest.java`; `apps/api/src/test/java/com/aurevia/web/HealthChainHttpTest.java`; `apps/api/src/test/java/com/aurevia/web/HealthReadyIntegrationTest.java`; `apps/api/src/test/java/com/aurevia/config/ProductionSafetyCheckTest.java`. No rolling-deploy rehearsal artifact found | proven by test |
| WEB-06 | `apps/api/src/test/java/com/aurevia/observability/RequestLogFormatterTest.java`; `apps/api/src/test/java/com/aurevia/web/LogRedactionTest.java`; `apps/api/src/test/java/com/aurevia/web/MetricsControllerTest.java`; `apps/api/src/test/java/com/aurevia/web/MetricsHttpTest.java` | proven by test |
| WEB-07 | `apps/staff-web/tests/features/system/browserSupport.test.ts`; `apps/staff-web/tests/theme/responsiveStylesheet.test.ts`; `apps/staff-web/tests/main.test.tsx`; `apps/staff-web/src/features/system/UnsupportedBrowserPage.tsx` | proven by test |
| WEB-08 | `apps/staff-web/scripts/check-bundle-size.test.mjs`; `apps/staff-web/scripts/check-bundle-size.mjs`; `apps/staff-web/check.sh` (250 KB gzip budget gate); `docs/reference/testing-guide.md`. LCP, INP and CLS field thresholds not measured | proven by test |
| WEB-09 | `apps/staff-web/src/components/StateViews.test.tsx`; `apps/staff-web/src/app/ErrorBoundary.tsx`; `apps/patient-mobile/src/app/PatientResource.test.tsx`; `apps/patient-mobile/src/app/screenStates.test.tsx` | proven by test |
| WEB-10 | `apps/staff-web/e2e/smoke.spec.ts` (real-browser axe accessibility scan, serious and critical findings fail); `apps/staff-web/e2e/playwright.config.ts`; `apps/staff-web/e2e/labflow/lab-journey.spec.ts`. No protected CI job runs the suite on every release candidate with a retained report | proven by test |
| WEB-11 | `docs/operations/delivery-safety/build-and-migration-gates.md`; `docs/operations/delivery-safety/rollout-and-production-access.md`; `docs/operations/production-readiness/launch-checklist.md` (signed artifact and rollout rows) | needs infrastructure owner |
| WEB-12 | `apps/api/pom.xml` (OWASP dependency-check); `scripts/check-secrets.py` and `scripts/check.sh` (secret scan); `scripts/frontend-check.sh`; `docs/operations/production-readiness/launch-checklist.md` (pentest, secrets and pinning rows) | needs infrastructure owner |
| WEB-13 | `apps/staff-web/tests/publicPages.test.ts`; `apps/staff-web/tests/indexability.test.ts` (`noindex`, `robots.txt`); `apps/public-pages/` (status, maintenance, support, privacy, accessibility and terms pages) | proven by test |

## Smallest task to close a gap

No row is marked `missing`: every requirement has either a committed test that
asserts it or a named owner path in the launch checklist. The four rows marked
`needs infrastructure owner` are not `missing` — section 8.1 assigns their
verification to a person outside worker scope, so a worker cannot manufacture
the evidence. For completeness, the smallest task that would close each one is
listed here; the Product Owner decides whether to schedule it.

- WEB-01: attach an external TLS scan (TLS 1.2 or newer, HSTS max-age at least one year with includeSubDomains, HTTP-to-HTTPS redirect) and one managed-WAF rule test, plus the certificate auto-renewal and 21-day expiry-alert configuration.
- WEB-04: attach the managed-WAF and provider-DDoS rule test, and add an exact-origin CORS allowlist plus header, body and request-time caps with a rejection test for each.
- WEB-11: attach the signed-artifact SBOM and matching digest promoted unchanged, the 5/25/100% rollout record with a rehearsed automatic rollback, and a release checklist naming its approvers.
- WEB-12: attach an independent penetration-test report with a finding register closed to the owner's satisfaction, and a secret-manager plus pinned-action and pinned-base-image-digest inventory.

## Residual gaps behind `proven by test` rows

A `proven by test` row means a committed test asserts part of the requirement;
it does not mean the whole requirement is verified or signed off. The following
behaviors remain unverified in this checkout and are recorded on the
[launch checklist](launch-checklist.md), not asserted as done here:

- WEB-02: cookie `Secure` and `SameSite` attributes and the rule that no patient data reaches URLs, local or session storage, analytics or logs have no test.
- WEB-03: session list and server-side revoke, MFA, lockout of authentication endpoints and the mobile authorization-code-with-PKCE flow have no automated test; the provider and PKCE client configuration is owner-owned.
- WEB-04: alongside the infrastructure-owned WAF evidence, worker-side tests for an exact-origin CORS allowlist, header, body and request-time caps, and error-shape rejection are still open.
- WEB-05: no rolling-deploy rehearsal artifact was found in this repository.
- WEB-08: the LCP, INP and CLS field thresholds are not measured; only the 250 KB gzip chunk budget is CI-enforced.
- WEB-10: there is no protected CI job that runs the browser suite on every release candidate and retains the report.
- WEB-13: the pages need separate hosting and owner-approved legal wording before publication.
