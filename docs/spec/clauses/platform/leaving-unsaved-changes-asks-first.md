# WEB-09 clause 4 — leaving with unsaved changes asks first

## Clause

Leaving with unsaved changes asks first.

## Status

Partly proven

## Evidence

Test file: [`leaving-unsaved-changes-asks-first.test.ts`](../../../../apps/staff-web/tests/spec/platform/leaving-unsaved-changes-asks-first.test.ts).
It renders the real notification-settings page in jsdom with synthetic
preferences and an injected resolved save callback; no database, network,
patient record or credential is involved. Evidence for the executed commands
below is the tool output; the code survey statements come from reading the
linked files. No human, clinical or partner evidence is held here. The leave
guard is a browser-navigation mechanism, so the wrong-role, other-tenant,
stale-version and forbidden-state refusal cases that apply to server commands
do not apply; the negative case is a clean or just-saved form that stops asking.

Test methods (one positive rule proof, one refusal, one active gap tripwire):

- `asks before leaving while edits are unsaved` changes the automatic-reply
  field, asserts the fixture is dirty, dispatches a cancelable `beforeunload`
  and asserts `defaultPrevented === true`.
- `does not ask before leaving a clean or just-saved form` dispatches the same
  event on the untouched page (default not prevented), then types, saves, and
  once the saved state paints dispatches again (default not prevented).
- `GAP: only some screens ask before leaving with unsaved changes` pins the
  exact set of screens that install the browser guard and eight representative
  editable forms that install none. It is an active, green test for the reason
  in `## Gap`.

Production symbols exercised by these tests:

- [`SettingsPage.tsx`](../../../../apps/staff-web/src/features/staffmsg/settings/SettingsPage.tsx)
  — `useUnsavedGuard` (listener installed only while the form is dirty and
  removed on cleanup) and `handleSave` clearing dirty after a resolved save.
- [`NotePage.tsx`](../../../../apps/staff-web/src/features/chart/NotePage.tsx)
  — `useUnsavedGuard` for unsaved draft text.
- [`PharmacyItemPage.tsx`](../../../../apps/staff-web/src/features/pharmacyinbox/PharmacyItemPage.tsx)
  — the equivalent inline `beforeunload` effect for typed command text.
- [`InboxScreen.tsx`](../../../../apps/staff-web/src/features/messages/InboxScreen.tsx)
  — `WorkDialogs` Stay/Leave confirmation for an unsaved reply.

Pre-existing assertions corroborate the guard on each of those four screens:
[`SettingsPage.test.tsx`](../../../../apps/staff-web/src/features/staffmsg/settings/SettingsPage.test.tsx)
asks before leaving and stops after a save or a conflict reload;
[`NotePage.test.tsx`](../../../../apps/staff-web/src/features/chart/NotePage.test.tsx),
[`PharmacyItemPage.test.tsx`](../../../../apps/staff-web/src/features/pharmacyinbox/PharmacyItemPage.test.tsx)
and [`InboxScreen.test.tsx`](../../../../apps/staff-web/src/features/messages/InboxScreen.test.tsx)
each assert their own screen's guard. This note adds one cross-cutting proof
rather than duplicating them.

## Gap

WEB-09 asks that EVERY screen ask before leaving with unsaved work. The survey
finds the browser-level `addEventListener('beforeunload'` guard in only three
screens — NotePage, SettingsPage and PharmacyItemPage — and an in-app
Stay/Leave confirmation in InboxScreen. Editable forms including patient
registration (`RegisterPatientForm`), booking (`BookingForm`), lab collection
and rejection (`CollectionForm`, `RejectionForm`), configuration
(`ConfigEditor`) and the scheduling and radiology forms hold typed work with
no leave guard at all. The clause is therefore partly proven, not proven end
to end.

Closing the gap means giving each remaining editable screen a guard, most
cheaply by extracting the repeated `useUnsavedGuard` into one shared hook under
`apps/staff-web/src` and wiring every form to its dirty state, then replacing
the tripwire with positive proofs. That is one staff-web engineering task for
the shared hook and screen wiring, plus a task for the component proofs if the
team keeps a proof per screen group. No such task is assumed here.

The story for this note asked for a deliberately skipped gap test when a clause
is unbuilt or partly built. AGENTS.md Coverage rules and
`scripts/check-constraints.py` reject an added skipped test, so the gap is
recorded as an active, green `GAP:` tripwire that pins the current absence and
fails when a guard is added; this is the conflict being recorded, not a
suppression of it. The tripwire pins the literal string
`addEventListener('beforeunload'` inside those three files and the
`discardTitle` token in InboxScreen: extracting the repeated hook or changing
quote style also breaks the tripwire without closing the gap, so the next
worker must update it deliberately rather than treat that failure as a
regression.
