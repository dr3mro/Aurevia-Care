# WEB-09 clause 3 — staff web conflict evidence

## Clause

A conflict never loses typed work; the user can compare, reload or copy.

## Status

Proven by tests

## Evidence

Test file: `apps/staff-web/tests/spec/platform/conflict-never-loses-typed-work-user-compare.test.ts`
(TypeScript/Vitest, plain `.ts` with `createElement`, jsdom, synthetic fixtures only).
It is an active, ungated component test suite; no test is skipped. It mounts the real
staff-web components under the production `ThemeProvider` and `I18nProvider` (English)
and proves the clause at the note-editor boundary:

- `keepsTypedWorkAndOffersCompareReloadCopyOnConflict` — positive proof. An editable
  synthetic draft is typed into through `onContentChange`, then rerendered with the
  typed SOAP text still in `revision.content` and `conflict: true` (the state a stale
  save produces). The same textbox still holds the typed text; the conflict panel is
  present inside the note card with the `conflict` heading and `conflictDetail`
  assurance, and all three recovery buttons — named by `stateViewText` keys `compare`,
  `reload` and `copyMyChanges` — are enabled, clicked and asserted to call their
  respective `onCompare`, `onReload` and `onCopy` handlers exactly once. This fails if
  a conflict clears the draft or drops any of the three actions.
- `doesNotOfferConflictRecoveryForANonConflictFailure` — negative proof. A
  `TransportError('server')` routed through `StateView.forTransportError` with all four
  recovery handlers supplied renders the error state and Retry only: the conflict
  heading and all three recovery buttons are absent, Retry fires once and the conflict
  handlers are never called. The same test renders the note editor with `conflict`
  false and asserts no conflict surface or recovery button appears while typed text is
  still displayed.
- `keepsConflictRecoveryButtonsInertButReachableWithoutHandlers` — negative/default
  proof. With `conflict: true` and no recovery callbacks supplied, the three actions
  are still present, enabled and clickable without throwing (the shared view's inert
  default), and typed text remains in the field.

Production classes exercised through the real component tree:

- `NoteEditor` (`apps/staff-web/src/features/chart/NoteEditor.tsx`) — the typed SOAP
  textareas and the `conflict === true` branch that keeps the recovery surface inside
  the note card; `NoteEditorProps.conflict`, `onCompare`, `onReload` and `onCopy`.
- `ConflictState` and `StateView.forTransportError`
  (`apps/staff-web/src/components/StateViews.tsx`) — the alert panel, the three
  recovery actions, their inert no-op defaults, and the kind-to-view mapping that
  sends a `server` failure to the error state rather than the conflict surface.
- `TransportError` (`apps/staff-web/src/api/transport.ts`) with kind `server` — the
  typed failure used for the negative case; the `conflict` kind is the live input to
  this clause and is mapped by the same `StateView` switch.
- `stateViewText` (`apps/staff-web/src/components/stateViewMessages.ts`) and
  `chartText` (`apps/staff-web/src/features/chart/messages.ts`) — the bilingual labels
  the assertions select buttons and headings by.
- `NotePage` (`apps/staff-web/src/features/chart/NotePage.tsx`) — the owning screen
  whose conflict/recovery handlers feed `NoteEditor`. The route-level entry point is
  `/patients/:id/notes/:noteId`; the existing end-to-end component tests
  `NotePage.test.tsx` methods `keeps typed text and shows compare, reload and copy on a
  409 conflict` (lines 235-253) and `keeps the conflict recovery surface inside the note
  card and keeps typed text` (lines 255-273) already drive a real 409 through that
  screen and assert typed text survives with all three actions inside the card.

This proof-only change added one test file and this document. No production code,
contract, migration, route or dependency changed, so no OpenAPI or generated-client
update was required. Verified locally with `bash apps/staff-web/check.sh` (includes
`tsc --noEmit`, lint, build, bundle budget and 100% per-file coverage) and
`python3 -I scripts/check-docs.py`; see `.agent/pr-notes/SPEC-WEB-09-C03-20261123-222751.md`
for the exact final lines.

Scope note: the wrong-role, other-tenant, duplicate-event and mismatched-patient
negatives named in the generic task instructions are server-side authorization and
integration concerns, not behaviours of this client-side conflict surface; they do not
apply to this UI clause. The sibling `precondition-required` transport kind shares the
same conflict branch and is already covered by `StateViews.test.tsx` and
`NotePage.test.tsx`; it is not repeated here.

## Gap

None.
