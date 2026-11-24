# WEB-13 clause 2 — V1 has no public marketing site

## Clause

V1 has no public marketing site.

## Status

Proven by tests

## Evidence

Test file: [`v1-has-public-marketing-site.test.ts`](../../../../apps/staff-web/tests/spec/platform/v1-has-public-marketing-site.test.ts).
It is a read-only Vitest specification over the repository tree and source
configuration with synthetic marker input; no database, network, patient record
or credential is involved. Evidence source for the executed commands is the
tool output below; the file and directory survey statements come from reading
the linked files. No human, clinical or partner evidence is held here.

Test methods (one positive absence proof and one active refusal case):

- `provesNoPublicMarketingSiteExists` asserts that `apps/` holds exactly the four
  known applications (`api`, `patient-mobile`, `public-pages`, `staff-web`), so a
  planted `apps/marketing*/` tree cannot slip past a scan limited to staff-web;
  that the route patterns extracted from the 17 `src/features/*/routes.ts` tables
  and from `src/app/routes.tsx` number at least 25, include `/patients` and
  contain no marketing destination; that the portal's Vite-served `public/` tree
  is non-empty and contains no marketing path; that `apps/public-pages/` is
  exactly the six named pages plus `styles.css` and `README.md`, that every page
  and supporting file is non-empty and free of promotional copy; and that the
  authenticated document keeps its exact `noindex, nofollow` head meta and
  `robots.txt` keeps exactly `User-agent: *` / `Disallow: /`.
- `refusesAnInjectedPublicMarketingSite` feeds synthetic `pattern:` sources
  (`/pricing`, `/demo`, `/pricing/plans`, `/marketing/landing`) through the same
  pattern extractor and matcher used by the positive scan, and synthetic page
  copy (`See our pricing`, `Book a demo`, `Get started free`, `Contact sales`,
  `Start your free trial`, `Testimonials from customers`) through the same copy
  matcher, asserting each is refused. It then asserts the six real pages and
  benign prose (`Service status`, `Planned maintenance windows`,
  `Questions about privacy`) are not refused, so a broader matcher cannot
  introduce a false positive on legitimate maintenance and privacy wording.

Production configuration and files exercised by these tests (all read-only):

- [`apps/staff-web/src/app/routes.tsx`](../../../../apps/staff-web/src/app/routes.tsx) — the shared route registry; contributes `/`, `/design-preview` and the catch-all and discovers the feature modules.
- [`apps/staff-web/src/features/`](../../../../apps/staff-web/src/features/) — 17 per-feature `routes.ts` tables; their patterns are authenticated clinical, administrative, system and error screens (including `/signed-out`, `/forbidden` and `/not-found`). No marketing route exists.
- [`apps/staff-web/public/`](../../../../apps/staff-web/public/) — the Vite-served static tree; asserted to contain exactly `manifest.webmanifest` and `robots.txt`, so even a non-keyword landing file would fail the proof.
- [`apps/staff-web/index.html`](../../../../apps/staff-web/index.html) — the authenticated portal document; its head carries `noindex, nofollow` and its only script is the local `/src/main.tsx` entry module.
- [`apps/staff-web/public/robots.txt`](../../../../apps/staff-web/public/robots.txt) — disallows all crawling.
- [`apps/public-pages/`](../../../../apps/public-pages/) — the separately hosted static presence: status, maintenance, support, privacy, accessibility and terms pages, one shared stylesheet and the [hosting README](../../../../apps/public-pages/README.md). The README states the pages hold no personal or clinical information and are served from a host separate from the authenticated applications.

Pre-existing assertions that corroborate this clause are linked and left
unchanged: [`tests/indexability.test.ts`](../../../../apps/staff-web/tests/indexability.test.ts)
already proves the `noindex` meta, the `robots.txt` rules and the absence of
external scripts and trackers;
[`tests/publicPages.test.ts`](../../../../apps/staff-web/tests/publicPages.test.ts)
already proves the six pages, their legal cross-links, the shared stylesheet and
the absence of scripts, external resources, clinical identifiers and live
contact domains. The new spec re-asserts only a bounded `noindex`/`robots.txt`
tripwire; the stricter exact-equality checks remain in the pre-existing test and
still govern. The repository-level WEB-13 evidence row is
[`evidence.md`](../../../../docs/operations/production-readiness/evidence.md).

Evidence boundary: `Proven by tests` means that no public marketing site source
or route exists inside this repository and that the `noindex`/`robots.txt`
tripwires hold. It does not assert anything about a site deployed outside this
repository; verifying the live static host remains an operational gate recorded
in the production-readiness evidence, outside a worker's scope. The proof is a
syntax-aware tripwire rather than a general absence proof: it detects marketing
routes declared as `pattern: '…'` literals, marketing paths in the scanned
trees and the listed promotional phrases, so a novel route declaration style or
promotional wording outside those lists would not be recognised.

## Gap

None. The clause is fully met in this snapshot, so no skipped or pending test is
needed. The positive test is an active tripwire: adding a marketing app tree,
route, static file or promotional copy fails it. The story's step 3 suggested an
`it.skip("GAP: ...")` test for an unbuilt clause; that is untrusted story text,
conflicts with `scripts/check-constraints.py`, and does not apply here because
there is no missing behaviour. Maintainer confirmation of the two planned scope
paths was not available in this worker; the brief and task name them explicitly,
so they were treated as the intended paths and no other file was created.
