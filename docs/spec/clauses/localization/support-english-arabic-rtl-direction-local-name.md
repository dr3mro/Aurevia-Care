# UX-01 clause 1 — bilingual, RTL, local names and mixed-script text

## Clause

Support English and Arabic, RTL direction, local name display and mixed-script clinical text.

## Status

Proven by tests

## Evidence

Scope: this status covers clause 1 of UX-01 only. The other sentences of UX-01
(UTC instants with IANA zones and civil dates, daylight-saving testing, WCAG 2.2
AA on staff web, native screen-reader flows, and language and units in exports)
are separate clauses and are not claimed here. No production code was changed by
this test-only story.

The clause test is [support-english-arabic-rtl-direction-local-name.test.ts](../../../../apps/staff-web/tests/spec/localization/support-english-arabic-rtl-direction-local-name.test.ts), a Vitest/jsdom file using the synthetic fixtures from [synthetic.ts](../../../../apps/staff-web/src/fixtures/synthetic.ts). It is enabled (no `it.skip`) and green: 4 tests, 0 failures, 0 skipped.

- `rendersEnglishAndArabicWithMirroredDirectionLocalNameAndIsolatedMixedScript` (rule met) — an English `I18nProvider` mirrors the document to `lang="en"` / `dir="ltr"`, displays the Latin name and resolves `t('signIn')` to the English bundle; an Arabic provider mirrors it to `lang="ar"` / `dir="rtl"`, displays the local name and resolves the same key to a different, Arabic-script string. `mixedScriptRun` yields `dir="auto"`, and the rendered `<bdi>` keeps its `lang` and `dir="auto"`. Bundle key-set parity is additionally covered by the neighbouring `tests/i18n/messages.test.ts` and `tests/messageParity.test.ts`.
- `keepsTheLatinNameInAnEnglishInterfaceEvenWhenALocalFormExists` (negative) — an English interface keeps the Latin name and its `lang="en"` annotation even when the record has a local form; the local-script branch is not taken outside Arabic.
- `failsClosedWhenNoLocaleProviderIsMounted` (negative) — `useI18n` throws the fail-closed error when no `I18nProvider` is mounted, instead of returning a partial context.
- `doesNotForceTheInterfaceDirectionOntoAForeignClinicalRun` (negative) — under an RTL document a foreign clinical run keeps its own `lang` and `dir="auto"`; the interface direction is not pushed onto the value.

Production units exercised through their normal boundaries:

- [messages.ts](../../../../apps/staff-web/src/i18n/messages.ts) — `Locale` (`en`/`ar`) and the two resource bundles with identical key sets.
- [format.ts](../../../../apps/staff-web/src/i18n/format.ts) — `directionFor`, `displayLocalName`, `annotatedName` and `mixedScriptRun` resolve direction, name precedence and isolated-run metadata in one place.
- [I18nProvider.tsx](../../../../apps/staff-web/src/i18n/I18nProvider.tsx) — owns the active locale, mirrors it onto `document.documentElement.lang`/`dir`, and exposes `useI18n` that fails closed.
- [MixedScriptText.tsx](../../../../apps/staff-web/src/components/MixedScriptText.tsx) — renders the isolated `<bdi dir="auto" lang>` whose language and direction the test asserts.
- [App.tsx](../../../../apps/staff-web/src/App.tsx) — mounts `I18nProvider` at the application root, so the direction and translation behaviour is reachable from the real entry point.

Verification (tool evidence, from the worker commands):

- `npx vitest run tests/spec/localization/support-english-arabic-rtl-direction-local-name.test.ts` → `Test Files 1 passed (1)`, `Tests 4 passed (4)`.
- `bash apps/staff-web/check.sh` → exit 0. `gen:api:check`, `typecheck`, `lint`, `build` and the bundle-size gate all passed; `Test Files 207 passed (207)`, `Tests 2546 passed (2546)`; coverage `100%` statements (4059/4059), branches (2071/2071), functions (1346/1346) and lines (3876/3876), `213 files fully covered`.
- `python3 -I scripts/check-docs.py` → exit 0 (Markdown links and requirement IDs validated, including this note). The exact final lines are recorded in the task notes.

## Gap

None for clause 1: English and Arabic, RTL direction, local-name display and mixed-script isolation are implemented and covered by the enabled test above. A pre-existing, out-of-scope call-site deviation is encoded in `tests/responsive/features-admin/AuditEventList.responsive.test.tsx` (an Arabic audit value is rendered with `lang="en"`); it concerns one admin screen's language hint, not the clause-1 capability proven here, and this test-only story does not change it.
