# READY-06 — Staff web API transport with CSRF, timeouts and typed errors

- Story: READY-06
- Branch: `swarm/READY-06-260`
- Scope: `apps/staff-web/src/api` only. No generated types, trackers, workflows or shared files changed.
- Evidence source: executed tool commands (tool evidence) and a simulated read-only specialist review. No human, clinical or patient evidence.

## Requirements

- WEB-03 — Browser security/sessions: CSRF token on state-changing requests, generic error content, no stored tokens.
- WEB-09 — Resilient screens: typed failure kinds that let the UI present the required states (session-expired, forbidden, conflict, invalid, offline, timeout, etc.) with a correlation id.
- ARC-03 (context) — correlation id and machine-readable error codes on API responses.

## Acceptance mapping

| Story item | Implementation | Test |
|---|---|---|
| `send(descriptor, options?)` calls `fetch(descriptor.url, …)` | `transport.ts` `send` → `attempt` → `fetch(url, init)` | `send — request construction` (all) |
| `credentials: 'include'` | `init.credentials = 'include'` | GET construction test |
| `X-Correlation-Id` = fresh `crypto.randomUUID()` unless descriptor has one | `descriptor.headers[CORRELATION_HEADER] ?? crypto.randomUUID()`, computed once per `send` call and reused across a single retry | generated-id, reuse-supplied-id, fresh-per-call tests |
| JSON body | `JSON.stringify(descriptor.body)` plus `Content-Type: application/json` when a body exists | POST header/body test; GET has no body |
| `AbortController` timeout 15000 ms | `setTimeout(() => controller.abort(), timeoutMs)`, default `15_000`, cleared in `finally`; `SendOptions.timeoutMs` override only for deterministic tests | abort→timeout test (15_000), timeout-retry test (25 ms) |
| non-GET adds `X-XSRF-TOKEN` from `XSRF-TOKEN` cookie | `csrfToken()` parses `document.cookie` and sets `CSRF_HEADER` | POST header test |
| missing cookie → `TransportError` kind `csrf-missing` before sending | guard in `attempt` before the timer/fetch | csrf-missing test asserts `fetch` not called |
| 401→session-expired, 403→forbidden, 404→not-found, 409/412→conflict, 422→invalid, 428→precondition-required, 429→rate-limited, other/non-ok→server | `kindForStatus` + default | `describe.each` status matrix (401,403,404,409,412,422,428,500,400) |
| 429 `retryAfterSeconds` from `Retry-After` | numeric parse in `retryAfterSeconds` | 3 tests: numeric, absent, non-numeric |
| network failure→offline, abort→timeout | `attempt` catch: `controller.signal.aborted` first, then rethrow `TransportError`, else offline | offline POST test, timeout POST test |
| keep response `correlationId` and server `code` | `toTransportError` reads `X-Correlation-Id` header (falls back to request id) and body `.code` | status matrix, fallback test, no-code tests |
| never put response body text in the message | `TransportError` message is `Request failed: <kind>` only | "never includes response body text" test |
| retry only GET once on offline/timeout; POST never | `send` catch retries `attempt` once for GET+offline/timeout | GET-offline-once, GET-timeout-once, GET-twice-fails, GET-no-retry-401, POST-offline, POST-timeout |
| no token/response data in localStorage/sessionStorage; no dependency | code review — none used; `package.json` unchanged | — (static: no storage calls, no new package) |

## Commands run and results

All commands run from `apps/staff-web` unless stated.

1. `npm ci --no-audit --no-fund` — added 312 packages, exit 0 (dependencies were not installed in the checkout).
2. `npx vitest run src/api/transport.test.ts` (before implementation) — RED: `Failed to resolve import "./transport"`, 1 test file failed, no tests.
3. `npx vitest run src/api/transport.test.ts` (after first implementation) — 28 passed but Vitest reported 1 unhandled rejection in the fake-timer timeout test. Defect D1 below; fixed in the test by attaching the rejection handler synchronously before advancing timers.
4. `npx vitest run src/api/transport.test.ts` (after D1 fix) — `Test Files 1 passed (1)`, `Tests 28 passed (28)`, no errors. After the F-1/D3 fix the focused file has 29 tests.
5. `bash check.sh` — applicable staff-web gate (the story's `worker-check.sh` alternative is for API tasks). Final lines:
   - `gen:api: generated client matches the contract`
   - typecheck and lint: exit 0 (no diagnostics)
   - `Test Files 31 passed (31)`, `Tests 228 passed (228)`
   - `No files with missing coverage.` / `46 files fully covered.`
   - `Statements 100% (622/622)`, `Branches 100% (334/334)`, `Functions 100% (226/226)`, `Lines 100% (591/591)`
   - The console stack traces in the output are expected `console.error` assertions from the existing `tests/theme/ThemeProvider.test.tsx` negative test; the suite result is all-passed.

## Defects found

- D1 (test harness, resolved): a fake-timer timeout test created `send(...)` and only attached its rejection assertion after `advanceTimersByTimeAsync`, producing a `PromiseRejectionHandledWarning` and an unhandled-rejection error that would fail the gate. Fixed by converting the promise to a resolved value with `.catch((reason) => reason)` before advancing timers, then asserting on the resolved value.
- D2 (type gate, resolved): `exactOptionalPropertyTypes` rejected passing `body: string | undefined` to `fetch`'s `RequestInit`, and `crypto.randomUUID` has a UUID template-literal return type. Fixed by building `RequestInit` and assigning `body` only when defined, and by typing the test's UUID mock as `typeof crypto.randomUUID` with UUID-shaped values.

- D3 (review finding F-1, resolved): `csrfToken()` returned `''` for a present-but-blank `XSRF-TOKEN=` cookie, so a non-GET request would be sent with an empty `X-XSRF-TOKEN` header instead of failing closed. Fixed by trimming the cookie value and mapping blank to `undefined`; added a regression test asserting `csrf-missing` and zero `fetch` calls for a blank cookie.

## Decisions

- One correlation id is generated per `send` call and reused across the single GET retry, so a logical request (including its retry) has one trace id; a fresh id is generated on the next call.
- Correlation id for an HTTP error is taken from the response `X-Correlation-Id` header, falling back to the request id when the header is absent; offline/timeout errors carry the request id.
- `Retry-After` is parsed as numeric seconds; HTTP-date form is intentionally ignored (`retryAfterSeconds` undefined).
- Any non-ok status without a specific mapping (including 4xx other than the listed ones) maps to `server`, keeping the `TransportErrorKind` union closed so READY-07 can map all kinds exhaustively.
- The 15 s deadline applies per attempt, so a retried GET has up to 30 s total.
- `SendOptions.timeoutMs` exists only to make timeout tests deterministic; the production default remains 15000 ms.
- The success path returns parsed JSON as `T`; no response data is persisted to browser storage.

## Review

A read-only bounded review was returned by the Senior TypeScript specialist (simulated reviewer, not human or clinical). It confirmed every other acceptance criterion is met and raised one finding, F-1 (low severity, security relevant): a blank `XSRF-TOKEN=` cookie produced an empty CSRF header instead of the fail-closed `csrf-missing` error. F-1 was fixed in `csrfToken()` with a blank-value regression test (defect D3). All other observations were non-blocking.

## Blockers / missing evidence

None. All acceptance items were implemented and the applicable gate (`bash apps/staff-web/check.sh`) passed locally. Database and integration tests are the integrator's responsibility per the swarm workflow and are not applicable to this staff-web-only change.
