# Arabic interface style guide

- Requirements: UX-01, PRD-05
- Status: descriptive. This page records what the shipped message bundles and the
  design contract already do; it does not create a new contract, approve a
  translation or replace the documents it cites.
- Applies to: `apps/staff-web` and `apps/patient-mobile`.
- Index: [Design language home](README.md) · [RTL and localization](rtl-and-localization.md) · [Accessibility patterns](accessibility-patterns.md) · [Approved screens](approved/README.md)

This is a working guide for anyone who writes or reviews an Arabic string. Every
rule below is taken from an artifact that exists in the repository; the artifact
is named as the evidence. Where the artifacts disagree, the disagreement is
marked `INCONSISTENT:` with the files involved and **no side is chosen** — the
choice belongs to a site Arabic-language reviewer, not to this page. Where a rule
exists only in the design contract and not yet in any shipped string, it is
labelled as a contract rule rather than presented as current practice.

Nothing here is clinical or human approval. A site review with an Arabic-speaking
clinician remains the launch gate for the RTL flows
([Accessibility patterns, end of section 10](accessibility-patterns.md)).

## Evidence base

The rules were derived from these artifacts, all read in full for this page:

| Kind | Files |
|---|---|
| Design contract | [RTL and localization](rtl-and-localization.md), [Accessibility patterns](accessibility-patterns.md), [Token contract](token-contract.md), [Design language home](README.md), [Approved screens](approved/README.md) |
| Staff web message bundles | `apps/staff-web/src/i18n/messages.ts`, `apps/staff-web/src/features/patients/messages.ts`, `apps/staff-web/src/features/admin/messages.ts`, `apps/staff-web/src/features/system/messages.ts`, `apps/staff-web/src/features/chart/messages.ts`, `apps/staff-web/src/features/scheduling/messages.ts`, `apps/staff-web/src/features/duplicates/messages.ts`, `apps/staff-web/src/components/stateViewMessages.ts` |
| Patient app message bundles | `apps/patient-mobile/src/app/messages.ts`, `apps/patient-mobile/src/features/attachments/messages.ts` |
| Formatting implementation | `apps/staff-web/src/i18n/format.ts` |

Line references such as `:line 80` point at the files as read for this task; the
key name (for example `mixedScriptNote`) is the stable reference.

## 1. Tone and form of address

| Rule | Evidence |
|---|---|
| The register is Modern Standard Arabic (فصحى), not a dialect. Formal and neutral by default: system outcomes are often passive (`تم تسجيل…`, `سيتم إضافة…`, `لم يُفصح عنه`, `رُفض الطلب`). | `apps/staff-web/src/features/admin/messages.ts` `eventInvited`, `deactivateDetail`; `apps/staff-web/src/features/duplicates/messages.ts` `refusalSecondReviewer`; `apps/staff-web/src/features/patients/messages.ts` `reasonNotDisclosed`, `registered` |
| Direct second-person address is used when the person must act or when their own work or session is described (`جلستك`, `تغييراتك`, `باسمك`, `تضغط`, `توقفت`). | `apps/staff-web/src/features/system/messages.ts` `signedOutDetail`; `apps/staff-web/src/features/chart/messages.ts` `saveError`; `apps/staff-web/src/features/admin/messages.ts` `approveDetail`; `apps/patient-mobile/src/features/attachments/messages.ts` `sendHint`; `apps/staff-web/src/components/stateViewMessages.ts` `sessionExpiredDetail` |
| Commands to the user are masculine singular imperatives: `أدخل`, `صحّح`, `سجّل`, `اختر`, `راجع`, `افتح`, `احفظ`, `أعد المحاولة`, `علّم`. Keep this form in field errors and hints. | `apps/staff-web/src/features/patients/messages.ts` `errorDisplayName`; `apps/staff-web/src/features/admin/messages.ts` `errorSummary`, `errorStaffRole`, `deactivateReasonRequired`; `apps/staff-web/src/features/duplicates/messages.ts` `confirmHint`, `openFirst`, `refusalGeneric`; `apps/staff-web/src/features/system/messages.ts` `signedOutDetail`; `apps/patient-mobile/src/features/attachments/messages.ts` `pendingFiles`; `apps/staff-web/src/features/chart/messages.ts` `saveBeforeSign` |
| Attestations are first person (`أقرّ ب…`), because the person, not the system, is the signer. | `apps/staff-web/src/features/chart/messages.ts` `attestation`; `signEffect` and `signerAttestation` reinforce the same ownership |
| Pick one register per locale and never mix formal and informal address on one screen. This is a contract rule; the bundles are all MSA, so no dialect register exists to mix today. | [RTL and localization, section 8](rtl-and-localization.md) ("Gender and formality") |
| Errors state what is wrong **and** what to do; they do not blame the user and do not repeat only the field label. | [Accessibility patterns, section 3](accessibility-patterns.md) ("Error text"); observed in `apps/staff-web/src/features/chart/messages.ts` `saveError` and `apps/staff-web/src/components/stateViewMessages.ts` `errorDetail` |

`INCONSISTENT:` the same "you have signed out" state uses two different
grammatical voices. `apps/staff-web/src/features/system/messages.ts`
`signedOutTitle` = `لقد سجّلت الخروج` (direct second person), while
`apps/patient-mobile/src/app/messages.ts` `signedOutTitle` = `تم تسجيل الخروج`
(passive). Both use direct address in their detail text. Do not copy either as a
canonical phrasing until an Arabic reviewer chooses.

## 2. Numbers, dates, times and units

### 2.1 Which numerals the product uses today

There are two numeral behaviours in the product, and both must be stated.

| Context | What happens today | Evidence |
|---|---|---|
| Formatted numbers, instants and civil dates | **Latin digits `0-9` in both locales.** Every `Intl` constructor passes `numberingSystem: NUMERAL_SYSTEM`, where `NUMERAL_SYSTEM = 'latn'`. | `apps/staff-web/src/i18n/format.ts` `:35`, `formatInstant` `:70-77`, `formatCivilDate` `:80-88`, `formatMeasurement` `:91-97` |
| Hard-coded numbers inside Arabic UI strings | **Mixed.** Validation and limit messages use Arabic-Indic digits (`١٢٠`, `٢`, `٦٤`, `٣٢`, `١٠`), while browser/technical facts use Latin digits (`768`, `2026-03-08T06:30:00Z`, `SHA-256`, `YYYY-MM-DD`). | Arabic-Indic: `apps/staff-web/src/features/patients/messages.ts` `errorDisplayName`, `errorMrn`, `errorIssuer`; `apps/staff-web/src/features/admin/messages.ts` `errorFacilityCode`, `errorStaffSubject`, `invalidConfigKey`; `apps/staff-web/src/features/duplicates/messages.ts` `alternateHint`; `apps/patient-mobile/src/features/attachments/messages.ts` `sizeOutOfRange`. Latin: `apps/staff-web/src/features/system/messages.ts` `unsupportedDetail`; `apps/staff-web/src/features/admin/messages.ts` `invalidAuditInstant`, `utcLabel`; `apps/patient-mobile/src/features/attachments/messages.ts` `checksum` |

The design contract's decision is Latin digits by default in both locales, with
an approved site policy able to switch **every** value on a screen to
Arabic-Indic; a screen that mixes the two systems for values is a defect. It also
requires `numberingSystem: 'latn'` to be passed explicitly to every `Intl`
constructor so that locale `ar` does not silently render Arabic-Indic digits
beside Latin identifiers. See [Design home decision D4](README.md),
[RTL and localization, sections 3 and 9](rtl-and-localization.md), and
[Token contract](token-contract.md) (`font.family.numeric` uses
tabular figures so digits align).

`INCONSISTENT:` the design documents and the implementation disagree about
whether the numerals are pinned. [Design home, known deviation 11](README.md)
and [RTL and localization, section 3](rtl-and-localization.md) say that
`apps/staff-web/src/i18n/format.ts` does **not** pass `numberingSystem` and that
locale `ar` therefore renders Arabic-Indic digits. The current `format.ts` does
pass `numberingSystem: 'latn'` in all three constructors. The documents were not
updated. Do not rely on the deviation text to describe current behaviour.

`INCONSISTENT:` the message bundles themselves mix numeral systems for Arabic
text, as shown in the table above. This page records both; it does not choose
whether validation limits should be `١٢٠` or `120`.

### 2.2 Dates and times

| Rule | Evidence |
|---|---|
| Instants are stored as UTC and rendered with an explicit IANA zone; the facility zone is used for a visit. Use `formatInstant`, not hand-built strings. | `apps/staff-web/src/i18n/format.ts` `formatInstant`; [RTL and localization, section 5](rtl-and-localization.md); [Approved screens](approved/README.md) (appointment cards show the facility and its time zone) |
| Civil dates (date of birth, clinical effective date) are formatted at UTC midnight so the day never shifts. Use `formatCivilDate`. | `apps/staff-web/src/i18n/format.ts` `formatCivilDate`; [RTL and localization, section 5](rtl-and-localization.md) |
| ISO patterns and UTC markers stay literal in labels: `YYYY-MM-DD`, `2026-03-08T06:30:00Z`, `(UTC)`, `بتوقيت UTC`. | `apps/staff-web/src/features/patients/messages.ts` `fieldDob`; `apps/staff-web/src/features/admin/messages.ts` `invalidDraft`, `invalidAuditInstant`, `effectiveAtUtc`, `occurredAt`, `utcLabel`; `apps/staff-web/src/features/chart/messages.ts` `signedAt`; `apps/staff-web/src/features/scheduling/messages.ts` `start`; `apps/patient-mobile/src/features/attachments/messages.ts` `uploadedAt` |
| Clock style follows the locale's convention; a site may override it. Daylight-saving gap and overlap are tested with two instants per zone against runtime tzdata. This is a contract rule — no DST assertion lives in the message bundles. | [RTL and localization, section 5](rtl-and-localization.md) |
| Relative time is stated alongside the absolute time for clinical events. Contract rule; no shipped string uses `Intl.RelativeTimeFormat` yet. | [RTL and localization, section 5](rtl-and-localization.md) |

### 2.3 Units and clinical values

| Rule | Evidence |
|---|---|
| A value and its unit are one run; the unit symbol is not translated. | [RTL and localization, section 3](rtl-and-localization.md); `apps/staff-web/src/features/chart/messages.ts` `vitals` = `القياسات الحيوية بالوحدات` ("vitals with units") |
| Qualifiers (`<`, `>`), ranges (en dash without spaces) and signs stay inside the same isolated run; no unit conversion in the interface. | [RTL and localization, section 3](rtl-and-localization.md) |
| Unknown / not reported values use an explicit word, never an empty cell or a bare dash. | [RTL and localization, section 3](rtl-and-localization.md) ("Unknown, none, not reported"); `apps/staff-web/src/features/admin/messages.ts` `unknown` = `غير مسجل`; `reasonUnknown` in `patients/messages.ts` and `dobUnknown` in `duplicates/messages.ts` |
| Units are part of the field label or hint and persist after entry; a value with an unknown unit is refused. Contract rule. | [Accessibility patterns, section 3](accessibility-patterns.md) ("Clinical values") |

`INCONSISTENT:` the design contract requires a non-breaking space (`U+00A0`)
between a number and its unit, but `apps/staff-web/src/i18n/format.ts`
`formatMeasurement` joins them with a plain space (`${number} ${unit}`). The same
gap is recorded as deviation 11 in [Design home](README.md). Do not describe the
product as using a non-breaking space until the formatter is changed.

## 3. Mixed Arabic and Latin text

| Rule | Evidence |
|---|---|
| Translate the surrounding sentence; keep names, codes and technical tokens exactly as recorded. The canonical example keeps the Arabic given name `ليلى`, the Latin family name `Haddad` and the code `MAIN` unchanged in **both** bundles, separated by `·`. | `apps/staff-web/src/i18n/messages.ts` `mixedScriptNote` (English and Arabic) |
| A Latin technical term may appear inside an Arabic sentence unchanged when it is a product/protocol term: `Identity provider`. | `apps/staff-web/src/i18n/messages.ts` `signInNote` (Arabic) |
| Machine codes stay Latin and are resolved to a translated label in code; response text is never interpolated into a message. | `apps/staff-web/src/features/patients/messages.ts` `dobReasonKey` (`not_known`, `not_disclosed`, `unable_to_collect`); `apps/staff-web/src/features/duplicates/messages.ts` `refusalKeyForCode`, `dependencyKindKey`, `dobReasonKey` and the bundle comment |
| The foreign run is isolated with the right `lang` and `dir="auto"`; a clinical value, a value with its unit, an identifier and a code with its text are each one atomic run. A parenthesised acronym stays with its parenthesis. | `apps/staff-web/src/i18n/format.ts` `annotatedName`, `mixedScriptRun`, `ScriptRun`; [RTL and localization, section 7](rtl-and-localization.md) |
| Diagnosis and procedure codes show both the code and its display text; the code is monospaced and isolated and is never replaced by the mapping. | [RTL and localization, section 4](rtl-and-localization.md); [Token contract](token-contract.md) `font.family.mono` |
| Arabic takes letter spacing 0 so the cursive joins stay intact. | [Design home decision D7](README.md); [Token contract](token-contract.md) `font.tracking.arabic` |
| A language switch inside the patient app needs an explicit restart-to-apply-direction step; RTL is fixed at app start. | [RTL and localization, section 1](rtl-and-localization.md); [Accessibility patterns, section 9](accessibility-patterns.md) |

**Drug names.** Design consensus, not observed translation practice: a drug name
is clinical text recorded in the source script (commonly Latin) and is treated
like any other foreign run — preserved verbatim, isolated with its `lang`, never
transliterated or translated by the interface. No current message bundle contains
a drug-name literal, so there is no shipped example to cite; the rule comes from
the mixed-script clinical text and identifier rules in
[RTL and localization, sections 4 and 7](rtl-and-localization.md). Do not invent
a translated drug name in a future string.

`INCONSISTENT:` the decimal/punctuation around mixed runs is not uniform.
`apps/staff-web/src/i18n/messages.ts` `mixedScriptNote` uses a Latin colon `:` and
a middle dot `·`, while `apps/staff-web/src/features/patients/messages.ts`
`errorLanguage` uses a Latin colon inside Arabic (`…مدعومًا: en أو ar.`) and most
other Arabic strings use Arabic punctuation (section 4). No side is chosen here.

## 4. Punctuation and quotation

| Rule | Evidence |
|---|---|
| Arabic sentences use Arabic punctuation: comma `،`, semicolon `؛` and question mark `؟`. | Comma: `apps/staff-web/src/features/patients/messages.ts` `errorDobExclusive`; semicolon: `apps/staff-web/src/features/patients/messages.ts` `duplicateWarningDetail`, and `syntheticNotice` in `patients/messages.ts`, `admin/messages.ts`, `chart/messages.ts`, `duplicates/messages.ts`, `scheduling/messages.ts` and `apps/patient-mobile/src/features/attachments/messages.ts`; question mark: `apps/staff-web/src/features/admin/messages.ts` `deactivateTitle`, `approveTitle` |
| The "synthetic data only" notice is one clause, `بيانات توضيحية فقط`, separated from the explanation by `؛`. | All six `syntheticNotice` values in the bundles listed above |
| An ellipsis is a single character `…` (U+2026), matching the English string. | `apps/staff-web/src/components/stateViewMessages.ts` `loading`; `apps/staff-web/src/features/chart/messages.ts` `saving`; `apps/patient-mobile/src/app/messages.ts` `loading` |
| An em dash `—` attaches a short qualifier to a label. | `apps/patient-mobile/src/features/attachments/messages.ts` `pendingFiles` = `الملفات المعلّقة — راجعها قبل الإرسال`; English uses the same dash |
| Latin punctuation is retained when the adjacent token is Latin: `(UTC)`, `(YYYY-MM-DD)`, `SHA-256`, `PDF`, `JPEG`, `PNG`. | `apps/staff-web/src/features/chart/messages.ts` `signedAt`; `apps/staff-web/src/features/patients/messages.ts` `fieldDob`; `apps/patient-mobile/src/features/attachments/messages.ts` `checksum`, `unsupportedType` |
| Quotation marks and blockquote edges mirror under RTL; keep a parenthesised acronym inside the same isolated run as the acronym. | [RTL and localization, sections 2 and 7](rtl-and-localization.md) |

`INCONSISTENT:` the active-progressive label `جارٍ …` is spelled with a tanween in
some bundles and without it in others. `apps/patient-mobile/src/app/messages.ts`
`loading` = `جارٍ التحميل…` and `apps/staff-web/src/features/chart/messages.ts`
`saving` = `جارٍ الحفظ…`, but `apps/staff-web/src/components/stateViewMessages.ts`
`loading` = `جار التحميل…`. Pick one spelling only after Arabic reviewer sign-off.

## 5. Buttons, errors and confirmations

### Buttons and actions

| Rule | Evidence |
|---|---|
| Button labels are short verb phrases, overwhelmingly a verbal noun (مصدر) alone or with its object: `حفظ الملاحظة`, `حفظ المسودة`, `إلغاء`, `بحث`, `حجز`, `تسجيل الدخول`, `إعادة المحاولة`, `تحميل المزيد`, `إرسال إلى الوحدة`, `تنزيل`, `حذف`, `استبدال`. | `apps/staff-web/src/i18n/messages.ts` `saveNote`, `signIn`; `apps/staff-web/src/features/chart/messages.ts` `saveDraft`; `apps/staff-web/src/features/patients/messages.ts` `registerSubmit`, `cancel`, `search`; `apps/staff-web/src/features/scheduling/messages.ts` `book`, `loadMore`; `apps/staff-web/src/components/stateViewMessages.ts` `retry`; `apps/patient-mobile/src/features/attachments/messages.ts` `send`, `remove`, `replace`, `download` |
| One primary action per page; a help entry point stays in the same place on every screen. | [Screens and navigation, section 1](screens-and-navigation.md); [Accessibility patterns, section 3](accessibility-patterns.md) ("Consistent help") |
| Submit-in-progress is announced once and stays in the accessibility tree; a transient toast is never the only record of a clinical outcome. | [Accessibility patterns, sections 3 and 7](accessibility-patterns.md); `apps/staff-web/src/features/chart/messages.ts` `saving` |

`INCONSISTENT:` the "Go home" control is an imperative in English and a noun
phrase in Arabic. `apps/staff-web/src/features/system/messages.ts` `goHome`:
English `Go home`, Arabic `الصفحة الرئيسية` ("the home page"). Compare `back`
(`رجوع`) and `retry` (`إعادة المحاولة`), which stay verbal. Do not treat this as
an approved pattern.

### Errors

| Rule | Evidence |
|---|---|
| One imperative summary line followed by field-level imperative instructions: `صحّح الحقول التالية`, then `أدخل …`. | `apps/staff-web/src/features/patients/messages.ts` `errorSummary` and `error*` keys; `apps/staff-web/src/features/admin/messages.ts` `errorSummary`, `errorFacilityCode`, `errorStaffRole` |
| A failed state names the failure, reassures the user about their data and offers retry: `تعذّر إكمال الطلب. يمكنك إعادة المحاولة.` / `تعذّر حفظ المسودة. تغييراتك ما زالت محفوظة هنا.` | `apps/staff-web/src/components/stateViewMessages.ts` `errorDetail`; `apps/staff-web/src/features/chart/messages.ts` `saveError` |
| A server refusal is machine-readable, translated by code and never built from response text; it names the reason (`رُفض الطلب: يجب أن يوافق مراجع مخول آخر…`). | `apps/staff-web/src/features/duplicates/messages.ts` `refusalSecondReviewer`, `refusalUnresolved`, `refusalGeneric` and `refusalKeyForCode`; [RTL and localization, section 8](rtl-and-localization.md) |
| An error carries a copyable correlation ID labelled in Arabic (`معرّف الارتباط`), with an explicit unavailable state (`غير متاح`). | `apps/staff-web/src/components/stateViewMessages.ts` `correlation`, `correlationUnavailable`; [RTL and localization, section 8](rtl-and-localization.md) |
| A conflict/stale-version message says the record changed and that the user's work was kept: `تغيّر هذا السجل` / `تم تحديث المعلومات منذ فتحها. تم الاحتفاظ بتغييراتك.` | `apps/staff-web/src/components/stateViewMessages.ts` `conflict`, `conflictDetail`; `apps/staff-web/src/features/chart/messages.ts` `compareTitle` |

### Confirmations

| Rule | Evidence |
|---|---|
| An irreversible action uses a confirmation that names the record and its effect and states that it cannot be undone here. | `apps/staff-web/src/features/admin/messages.ts` `deactivateTitle`/`deactivateDetail`, `approveTitle`/`approveDetail`; [Screens and navigation, section 5](screens-and-navigation.md); [Accessibility patterns, section 3](accessibility-patterns.md) ("Destructive actions") |
| The consequence is stated in words before the user commits: signing makes the revision read-only (`التوقيع يجعل هذه المراجعة للقراءة فقط…`); nothing is sent until the button is pressed (`لا يُرسل أي شيء حتى تضغط زر الإرسال.`). | `apps/staff-web/src/features/chart/messages.ts` `signEffect`; `apps/patient-mobile/src/features/attachments/messages.ts` `sendHint` and [Approved screens](approved/README.md) (patient Arabic screen) |
| A confirmation dialog gives the safe choice initial focus and is reachable in RTL; a critical result is never shown only in a dialog. | [Accessibility patterns, section 5](accessibility-patterns.md) |

`INCONSISTENT:` confirmation titles use three different wordings. The nominal
question form `إلغاء تنشيط {name}؟` and `اعتماد الإصدار {version} من {key}؟`
(`apps/staff-web/src/features/admin/messages.ts`), the bare nominal
`توقيع ملاحظة سريرية` with no question mark
(`apps/staff-web/src/features/chart/messages.ts` `signConfirmTitle`), and the
imperative `أكّد الهويتين` (`apps/staff-web/src/features/duplicates/messages.ts`
`confirmTitle`). No single form is approved here.

## 6. What must never be translated

| Never translate | Evidence |
|---|---|
| Identifiers and codes: MRN, accession, requisition, specimen, study UID, order/invoice/receipt numbers, correlation/event IDs, checksums. Show them in a monospaced, isolated LTR run with a copy action. | [RTL and localization, section 4](rtl-and-localization.md); `apps/staff-web/src/components/stateViewMessages.ts` `correlation`; `apps/staff-web/src/features/admin/messages.ts` `correlationId`; `apps/patient-mobile/src/features/attachments/messages.ts` `checksum` |
| Machine codes and code values: `tenant_admin`, `reception`, `privacy_auditor`, `en`, `ar`, coded DOB reasons (`not_known`, `not_disclosed`, `unable_to_collect`), `SECOND_REVIEWER_REQUIRED`, `UNRESOLVED_DEPENDENCY`. | `apps/staff-web/src/features/admin/messages.ts` `errorStaffRole`; `apps/staff-web/src/features/patients/messages.ts` `errorLanguage`, `dobReasonKey`; `apps/staff-web/src/features/duplicates/messages.ts` `refusalKeyForCode` |
| Technical formats and units/symbols: `YYYY-MM-DD`, ISO instants such as `2026-03-08T06:30:00Z`, `UTC`, `SHA-256`, `PDF`, `JPEG`, `PNG`, `mg`, `mmol/L`, `%`, `°C`. | `apps/staff-web/src/features/admin/messages.ts` `invalidDraft`, `invalidAuditInstant`, `utcLabel`; `apps/patient-mobile/src/features/attachments/messages.ts` `checksum`, `unsupportedType`; [RTL and localization, sections 2 and 3](rtl-and-localization.md) |
| Proper names and recorded clinical text, including drug names: do not invent an Arabic or Latin spelling, transliterate, re-spell, case-fold or machine-translate for display. | `apps/staff-web/src/i18n/messages.ts` `mixedScriptNote` (`Haddad`, `ليلى`, `MAIN`); `apps/staff-web/src/i18n/format.ts` `displayLocalName`, `annotatedName`; [RTL and localization, sections 6 and 7](rtl-and-localization.md) |
| Field **labels** for these values may be translated (`MRN` → `رقم الملف`, `Code` → `الرمز`); the **values** are never translated. | `apps/staff-web/src/features/patients/messages.ts` `colMrn` / `fieldMrn`; `apps/staff-web/src/i18n/messages.ts` `code`; [RTL and localization, section 4](rtl-and-localization.md) |

A copy control must copy the raw value with no direction marks or formatting
characters and announce success in the interface language
([RTL and localization, section 4](rtl-and-localization.md)).

`INCONSISTENT:` the product/brand name is handled differently in the two apps.
`apps/patient-mobile/src/app/messages.ts` `appTitle` translates the brand to
`أوريفيا للرعاية`, whereas `apps/staff-web/src/i18n/messages.ts` `appTitle`
renders a functional title (`Staff Portal` → `بوابة الموظفين`) rather than the
brand. Choose a single brand policy only with the product owner.

## 7. Inconsistency register

Collected here so a reviewer can find them; none is resolved by this page.

| # | Topic | Files |
|---|---|---|
| 1 | Signed-out voice (second person vs passive) | `apps/staff-web/src/features/system/messages.ts`, `apps/patient-mobile/src/app/messages.ts` |
| 2 | Numeral system pinned in code but not in the design docs | `apps/staff-web/src/i18n/format.ts`, `docs/design/README.md`, `docs/design/rtl-and-localization.md` |
| 3 | Arabic-Indic vs Latin digits inside Arabic strings | `apps/staff-web/src/features/patients/messages.ts`, `apps/staff-web/src/features/admin/messages.ts`, `apps/staff-web/src/features/duplicates/messages.ts`, `apps/patient-mobile/src/features/attachments/messages.ts`, `apps/staff-web/src/features/system/messages.ts` |
| 4 | Value/unit separator (plain space vs required non-breaking space) | `apps/staff-web/src/i18n/format.ts`, `docs/design/rtl-and-localization.md`, `docs/design/README.md` |
| 5 | Latin vs Arabic punctuation around mixed runs | `apps/staff-web/src/i18n/messages.ts`, `apps/staff-web/src/features/admin/messages.ts` |
| 6 | Spelling of `جارٍ` / `جار` | `apps/patient-mobile/src/app/messages.ts`, `apps/staff-web/src/features/chart/messages.ts`, `apps/staff-web/src/components/stateViewMessages.ts` |
| 7 | `Go home` imperative vs noun phrase | `apps/staff-web/src/features/system/messages.ts` |
| 8 | Confirmation-title wording | `apps/staff-web/src/features/admin/messages.ts`, `apps/staff-web/src/features/chart/messages.ts`, `apps/staff-web/src/features/duplicates/messages.ts` |
| 9 | Brand name translated vs functional title | `apps/patient-mobile/src/app/messages.ts`, `apps/staff-web/src/i18n/messages.ts` |

## 8. How to use this page in review

1. Find the requirement IDs the screen serves (UX-01 for English/Arabic, RTL and
   mixed script; PRD-05 for the single-market policy pack).
2. Check the string against sections 1–6 above and the cited artifact. A rule
   with no shipped example is a contract rule, not an observed habit.
3. If the string falls under an `INCONSISTENT:` item, do not silently pick a
   side; raise it with the Arabic-language reviewer and leave this page's entry
   unchanged.
4. Run the module checks and `python3 -I scripts/check-docs.py`; record the
   output with the change. Runtime RTL behaviour is verified in the owning
   module, not here ([Design home, "How this is verified"](README.md)).
