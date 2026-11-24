# 0004. Responsive staff web

- Status: Proposed
- Date: 2026-11-21
- Requirement IDs: WEB-07, UX-01
- Owner: Product

## Context

WEB-07 currently says the staff web supports desktop browsers at 1280 x 720 and above with a compact layout down to 768 px, and that unsupported browsers see a plain message with no data. `docs/design/screens-and-navigation.md` section 1 adds width to that rule: below 768 px the staff web shows a plain "use a larger screen" notice with no data. The `isSupportedBrowser(userAgent, width)` guard in `apps/staff-web/src/features/system/browserSupport.ts` (READY-32) implements that 768 px floor today. Clinical staff work at the point of care on phones and tablets, so a notice with no data blocks the work rather than helping it.

UX-01 requires WCAG 2.2 AA on the staff web. `docs/design/accessibility-patterns.md` requires text to scale to 200% without loss of content or function, reflow at 320 CSS px without two-dimensional scrolling except for the data table itself (WCAG 1.4.10), and that below the reflow breakpoint a table becomes a labelled scroll region with no clinical column hidden with `display: none`. The token contract sets the general web target floor (`size.target.min`) at 24 x 24 px and the comfortable target at 44 x 44 px.

Two product boundaries constrain any offline thinking: PRD-06 excludes offline clinical editing, and the spirit of PAT-02 ("Avoid persistent clinical caches") rules out a service worker cache of clinical data. WEB-02 also requires authenticated responses to carry `Cache-Control: no-store`.

## Decision

The staff web is fully usable, with all clinical data and actions available to the signed-in role, at every width from 320 px upward:

- **Phones:** 320 to 767 px.
- **Tablets:** 768 to 1279 px, in both portrait and landscape orientations.
- **Desktop:** 1280 px and above.

The layout uses three breakpoints, expressed as `min-width` media queries: **compact 0**, **medium 768**, and **expanded 1280**. These are layout breakpoints only; they do not change what the server authorizes.

Navigation and tables adapt per breakpoint:

- **Navigation:** an off-canvas drawer on phones (compact), an icon rail on tablets (medium), and a full labelled rail on desktop (expanded). As with every shell region, placement and the drawer's open direction mirror under right-to-left (UX-01).
- **Tables and worklists:** on phones the default reflow treatment is a labelled scroll region (`tabindex="0"`, `role="region"`) that preserves real `<table>`, `<caption>` and `<th scope>` semantics. A labelled card rendering is an allowed alternative only when it preserves those same table semantics and the full column set, because a grid of `div`s without table semantics is not an option for tabular clinical data. No clinical column is hidden with `display: none`; every column the API returns stays reachable. Row actions keep accessible names that include the patient identity, so a narrow layout cannot cause a wrong-patient read.
- **Touch targets:** for coarse pointers (`@media (pointer: coarse)`) every interactive target is at least 44 x 44 CSS px. The 24 x 24 px `size.target.min` remains the general web floor; the 44 x 44 px requirement above supersedes it for coarse pointers.
- **Text and zoom:** text works at 200 percent zoom and reflows at 320 CSS px (WCAG 1.4.10), with no two-dimensional scrolling except the labelled table scroll region. No clinical value is truncated to make it fit; values, units, identifiers and due times wrap instead.
- **Offline and caching:** there is no offline clinical editing and no service worker cache of clinical data. A service worker, if used at all, is limited to non-clinical static assets and never stores authenticated responses; clinical data is always fetched after authentication under the WEB-02 `no-store` rule.

Supported browser matrix:

| Client | Supported versions | Notes |
|---|---|---|
| Staff web — desktop and tablet | Latest two major versions of Chrome, Edge, Firefox and Safari | Unchanged from WEB-07 |
| Staff web — phone | Latest two major versions of iOS Safari and Chrome on Android | Android browser and Chromium-based Android WebViews follow the Chrome Android version |
| Samsung Internet | Out of scope | Not in the supported phone matrix |
| Patient app | Latest two major iOS and Android versions | Unchanged from WEB-07 |

Unsupported browsers still see a plain message and no data (WEB-07), but viewport width below 768 px is no longer a reason to show it: the compact layout is a supported mode down to 320 px.

## Alternatives considered

- **Keep the "use a larger screen" notice below 768 px** — rejected: it shows no data and blocks point-of-care work on phones and tablets; it is a usability failure, not a safe default.
- **Scale the desktop layout down and hide low-priority clinical columns on narrow screens** — rejected: hiding a clinical column is a wrong-patient and wrong-data risk, and `docs/design/accessibility-patterns.md` forbids `display: none` on clinical columns below the reflow breakpoint.
- **Add a service worker that caches clinical data for offline editing** — rejected: PRD-06 excludes offline clinical editing and the spirit of PAT-02 forbids persistent clinical caches; WEB-02 additionally requires `no-store` on authenticated responses.
- **Lower only the desktop compact floor and provide no phone layout** — rejected: tablets begin at 768 px and phones reach 767 px, so a floor at 768 px still excludes every phone. Three explicit breakpoints make each target testable.

## Consequences and evidence

- WEB-07 in `PROJECT_V1_SPEC.md` still states the 1280/768 px layout floors and the plain unsupported-browser message, while `docs/design/screens-and-navigation.md` section 1 still states the sub-768 px "use a larger screen" notice with no data. **A person must amend the specification**; the swarm may not edit it. Until that amendment lands, the compact layout floor in code follows this decision: the staff web must render and work down to 320 px, and width alone must not trigger the unsupported-browser page. This record is `Proposed` and awaits the owner (Product) before the spec change is made.
- Implementing this decision is cross-boundary work for a later story: it touches the staff-web shell, navigation, table components, the browser-support guard in `apps/staff-web/src/features/system/browserSupport.ts`, the design contract in `docs/design/screens-and-navigation.md`, and the browser matrix in the end-to-end suite (WEB-07), which is verified with the real-browser accessibility and visual checks (WEB-10). Each of those changes needs its own tests, including negative and reflow tests.
- Accessibility is not proven by this document. The 200% zoom, 320 px reflow, coarse-pointer target size and right-to-left layout claims must be observed in the owning module and in a real browser before acceptance; a simulated or synthetic review is not clinical or human acceptance.

Evidence source: tool reads of `PROJECT_V1_SPEC.md` (WEB-07, UX-01, PRD-06, PAT-02, WEB-02, WEB-10), `docs/design/screens-and-navigation.md`, `docs/design/accessibility-patterns.md`, `docs/design/token-contract.md` and `docs/decisions/template.md`, plus the existing decision records 0001-0003 for format. `python3 -I scripts/check-docs.py` exit 0 (see `.agent/pr-notes/RESP-DECISION-20261121-152816.md`). Evidence is from executed tools and a simulated UX reviewer; no human, clinical or patient evidence. No patient data or credentials appear in this record.
