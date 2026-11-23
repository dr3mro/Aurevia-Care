# Runtime UI verification

A passing component test proves the code under the test's assumptions, not that the
screen works in a browser. Use this checklist for staff-web and patient-mobile UI
changes. Where a browser automation tool is configured, use it; where it is not,
state that explicitly and compensate with deterministic async assertions in the
test suite. "It looked fine in jsdom" is not evidence of runtime behavior.

## When a browser is available

- [ ] Load the screen with synthetic data at compact and expanded widths.
- [ ] Inspect the live DOM for the element the test targets, not just its text: a
      region can exist while still showing a loading state.
- [ ] Read console output; a warning about an update outside `act`, a failed request
      or an accessibility warning is a finding, not noise.
- [ ] Watch the network requests for the route under test: correct path, headers,
      pagination and tenant context.
- [ ] Check the accessibility tree for the labelled region, button names and focus
      order (the design contract is in `docs/design/accessibility-patterns.md`).
- [ ] Verify the right-to-left layout when the change touches text or controls.
- [ ] Never attach browser tooling to a personal, logged-in profile. Use an isolated
      profile; a page with untrusted content plus an authenticated browser is the
      worst case for prompt injection.

## When no browser is available

- [ ] Use `findByRole`/`findByTestId` or `waitFor` after an awaited API call. A
      synchronous `getBy*` sees "Loading" or an empty state and produces a false
      failure — or a false pass when the assertion happens to match the loader.
- [ ] Assert the resolved value itself, not only that a container exists.
- [ ] Seed both a populated and an empty fixture so the "Nothing to show" state is
      intentional rather than accidental.
- [ ] Run the module's canonical check and record that no browser was available.

## Verification

- [ ] The UI claim in the notes names the observation (screenshot, DOM, console,
      network, accessibility tree, or the specific async assertion) that proves it.
- [ ] `bash scripts/frontend-check.sh` and the touched app's `check.sh` pass.
- [ ] No credential, patient identifier or authenticated session was captured in a
      screenshot, log or fixture.

## Red flags

- A UI fix closed with only a green unit test and no runtime observation or async
  assertion.
- A screenshot or log containing a real name, identifier or token.
- An accessibility or console warning dismissed as "pre-existing" without evidence.
