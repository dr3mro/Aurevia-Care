# UX-01 clause 5 — keep presentation styling flexible

## Clause

Keep presentation styling flexible.

## Status

Partly proven

## Evidence

Test class: `UX-01 clause 5 — keep presentation styling flexible` (Vitest `describe`
block) in `apps/staff-web/tests/spec/localization/keep-presentation-styling-flexible.test.ts`.
The suite uses the same Vitest + jsdom configuration, synthetic fixtures and
`tests/setup.ts` cleanup as the neighbouring `tests/theme/` tests. Staff web has no
database or server in its test path, so the suite contacts no endpoint and sets up
no database. All four tests are active (none skipped) and pass.

Test methods (one rule proof, one parameterisation proof, one state proof, one
fail-closed refusal):

- `resolves distinct light and dark backgrounds from the same token API` — calls
  `themeTokens('light')` and `themeTokens('dark')` through the single public
  resolver and asserts the two documented light/dark backgrounds, proving both
  branches of the mode parameterisation rather than two hard-coded per-mode
  layouts.
- `keeps spacing and focus-ring values stable across modes` — asserts that the
  spacing scale is the same set of values in both modes and that both modes expose
  a focus-ring presentation value, then passes sentinel tokens through
  `logicalContentOffset` to prove the layout reads whatever token set it is given.
  The property names stay logical and stable while the values follow the token
  source. It deliberately does **not** assert that the two ring values are equal:
  the existing `tests/theme/tokens.test.ts` does, and §8.1 requires that assertion
  to be replaced when the contract lands, so copying it here would create new debt.
  The test names overlap existing coverage in `tests/theme/`; this file adds the
  sentinel-token parameterisation proof, the `spacing.sm/md/lg` scale proof and the
  token re-resolution-on-toggle proof, so a §8.1 migration updates the right copy.
- `mirrors the toggled mode onto the document root and re-resolves tokens` — mounts
  `ThemeProvider`, asserts the initial `data-theme` and resolved background, toggles
  to dark and back, and asserts that both the root attribute and the tokens handed
  to the consumer follow the mode.
- `refuses theme reads outside a provider` — renders a consumer without a provider
  and asserts that `useTheme` throws the fail-closed error. No role, tenant, version
  or idempotency rule applies to this presentation-only clause; the refusal is the
  fail-closed boundary for a missing provider.

Production modules exercised through their normal entry points:

- `themeTokens`, `ThemeMode` and `ThemeTokens` in `apps/staff-web/src/theme/tokens.ts`
  — the six-field token API and its light/dark resolution.
- `ThemeProvider` and `useTheme` in `apps/staff-web/src/theme/ThemeProvider.tsx` —
  the context owner, the `data-theme` effect, `toggleMode` and the fail-closed read.
- `logicalContentOffset` in `apps/staff-web/src/theme/logicalLayout.ts` — the
  token-derived, logical-only directional offset used by RTL layout.
- The generated contract-token fallbacks in `apps/staff-web/src/theme/responsive.css`
  are already covered by `tests/theme/responsiveStylesheet.test.ts`; this clause
  does not restate that coverage.

Entry-point-to-test trace: `apps/staff-web/src/App.tsx` mounts `<ThemeProvider>`,
`apps/staff-web/src/app/AppShell.tsx` reads `mode`, `tokens` and `toggleMode` through
`useTheme()`, and shared components style themselves from the same tokens. The new
test mounts that provider directly and drives it through the public `useTheme` read
and `toggleMode` action, so the assertions run through the same entry point the
shell uses.

## Gap

The theme foundation is flexible, but the normative token contract is not adopted.
`docs/design/token-contract.md` §8.1 records that `ThemeTokens` is only a six-field
subset: the contract additionally requires role-named surface, text, border, action,
status and focus tokens, a generated token source with a contract version, role-correct
radius and control-height tokens, and a derivation/hex-literal test suite. Three
gaps remain in the running app:

1. `apps/staff-web/src/theme/tokens.ts` still stores `focusRing` as a whole
   `0 0 0 3px #ffbf47` box-shadow string, identical in both modes. The contract
   replaces it with `color.focus.ring` plus `border.width.focus` and a 2 px offset;
   the v0 amber value measures 1.64:1 on the light page (§3.4, §5.3, §8.1).
2. `apps/staff-web/index.html` still hard-codes
   `outline: 3px solid #ffbf47; outline-offset: 2px`, so the ring that actually
   paints is a document-level literal, not a token. `tests/theme/tokens.test.ts`
   also still asserts the old ring shape and its equality across modes, and §8.1
   requires those assertions to be replaced rather than adjusted.
3. The pre-paint theme script, active-mode `color-scheme`, stored per-user preference
   and mode-switch focus/scroll/form preservation from §7 are not implemented;
   `ThemeProvider` writes `data-theme` only after paint and defaults to light.

Files that would change: `apps/staff-web/src/theme/tokens.ts` (or a generated
replacement), `apps/staff-web/index.html`, `apps/staff-web/src/theme/ThemeProvider.tsx`,
the consuming components listed in §8.1, and the theme tests. This is at least three
separate tasks: (1) generate and adopt the contract token source with derivation and
hex-literal tests, (2) replace the painted focus ring in the document stylesheet and
update the stale ring assertions, and (3) implement the §7 pre-paint, `color-scheme`,
persistence and focus-preservation rules. It is not a single rename. This task added
proof only; it did not change production code, an existing test, the contract, a
migration or a configuration file.
