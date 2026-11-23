# PAT-02 clause 5 — confirm user intent for saving a local copy

## Clause

Confirm user intent for saving a local copy.

## Status

Partly proven

## Evidence

Test file: `apps/patient-mobile/src/spec/patient-access/confirm-user-intent-saving-local-copy.test.ts`
(new, active Jest tests only, synthetic fixtures). Both tests drive the real prompt and the real
confirmation kernel together, so the button the patient presses is what decides whether the exact
revision is authorized:

- `pressing Download in the save-copy prompt authorizes only the exact report revision` — the
  native alert is shown and the Download button is pressed; the flow resolves to the synthetic
  server authorization and the authorizer is called exactly once with `['report-1', 2]`.
- `pressing Cancel in the save-copy prompt produces no authorization and never calls the server`
  — the Cancel button is pressed; the flow resolves to `null` and the authorizer records zero calls,
  so no document reference can be produced without an affirmative choice.

Production code exercised through the prompt-to-authorization chain:

- `confirmAndAuthorizeDownload` in `apps/patient-mobile/src/features/reports/downloadFlow.ts` —
  prompts for intent, returns `null` on cancel and otherwise requests authorization for the exact
  report id and revision the patient tapped. Both sides of its cancel branch are taken.
- `createAlertConfirmer` in the same file — builds the native `Save a local copy` alert; both the
  `Cancel` and `Download` button callbacks are invoked by the two tests above.
- `DownloadAuthorizer` in `apps/patient-mobile/src/features/reports/reportClient.ts` — the narrow
  capability the flow uses; the test double records whether the server was called at all.
- `ReleasedReport` and `DownloadAuthorization` in `apps/patient-mobile/src/features/reports/types.ts`
  — the synthetic patient-visible projection and the server-issued authorization; neither type
  carries clinical bytes or credentials.

Supporting evidence already on the branch, not duplicated here:
`apps/patient-mobile/src/features/reports/report-core.test.ts:280-311` covers the same cancel and
exact-revision branches of `confirmAndAuthorizeDownload` with a synthetic confirmer, and
`apps/patient-mobile/src/features/reports/reports-screen.test.tsx:172-205` covers the generic alert
title and message and both button callbacks of `createAlertConfirmer`. This task adds one clause-focused proof
and changes no production code, existing test, contract or migration.

## Gap

The intent-confirmation kernel is built and unit-tested, but no production screen calls it.
`ReleasedReportsScreen` (`apps/patient-mobile/src/features/reports/ReleasedReportsScreen.tsx:187`)
only reports the tap through its `onDownload(report)` callback. The host that supplies that callback,
`ReleasedReportsPage` in `apps/patient-mobile/src/app/patientPages.tsx:270`, passes `placeholderAction`,
so a patient tapping Download in the running app never sees the intent prompt and no authorization
is requested. `confirmAndAuthorizeDownload` and `createAlertConfirmer` are imported only by tests.
The screen-wiring path end to end is therefore not proven, and the status is `Partly proven` rather
than `Proven by tests`.

Closing this needs **one** bounded code task: build the authenticated report client in the page and
wire its `onDownload` to `confirmAndAuthorizeDownload(client, selected, createAlertConfirmer())`,
treating a `null` result as a no-op, then add a screen-level test through the real page. Files that
task would change (none changed here): `apps/patient-mobile/src/app/patientPages.tsx` and a new
screen-level test under `apps/patient-mobile/src/spec/patient-access/`. No contract or migration
change is needed.

The complete screen-level test that would prove the missing behaviour is preserved here verbatim
for that task. It is deliberately **not** added as an active or `it.skip("GAP: ...")` test to the
TypeScript spec: `scripts/check-constraints.py:52-53` rejects any added `it.skip(` in a `.ts` file,
and AGENTS.md forbids skipped tests. Markdown is exempt from that constraint, so the literal shape
is quoted here and in the worker notes instead:

```tsx
it.skip("GAP: the reports page confirms intent before authorizing a local copy", async () => {
  const originalAlert = Alert.alert;
  const alerts: AlertButton[][] = [];
  Alert.alert = (_title, _message, buttons) => {
    if (buttons) alerts.push(buttons);
  };
  try {
    const authorize = recorder(async (reportId: string, revision: number): Promise<DownloadAuthorization> => {
      expect([reportId, revision]).toEqual(['report-1', 2]);
      return authorization;
    });
    // The wiring task adds the authorizer capability to the page host; this prop does not
    // exist yet, which is why this proof cannot be enabled today.
    const view = await render(
      <ReleasedReportsPage
        transport={{ get: async () => [report], post: async () => ({}) }}
        locale="en"
        organizationId="org-1"
        patientId="patient-1"
        onSessionExpired={() => undefined}
        authorizer={{ authorizeDownload: authorize.fn }}
      />,
    );
    const download = await view.findByTestId('download-report-1');
    await fireEvent.press(download);

    expect(alerts).toHaveLength(1); // fails today: the host passes placeholderAction
    expect(authorize.calls).toEqual([]); // no server call before an affirmative choice

    alerts[0]?.[1]?.onPress?.(); // Download
    await waitFor(() => expect(authorize.calls).toEqual([['report-1', 2]]));
  } finally {
    Alert.alert = originalAlert;
  }
});
```
