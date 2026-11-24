# WEB-13 clause 1 — Public presence

## Clause

Public presence.

## Status

Partly proven

## Evidence

Test file: [`public-presence.test.ts`](../../../../apps/staff-web/tests/spec/platform/public-presence.test.ts).
It is a read-only Vitest specification over the staff-web document, its public assets and its feature route declarations, with synthetic string input for the refusal case. No database, network, patient record or credential is involved. Evidence source for the executed commands is the tool output; the file survey statements below come from reading the linked files. No human, clinical or partner evidence is held here.

Test methods (one positive boundary proof, one refusal, one active gap tripwire):

- `provesTheStaffPortalExposesNoPublicPresenceSurface` asserts that the served document head holds exactly one robots meta with the content `noindex, nofollow`, that no robots or googlebot meta grants a bare `index` permission, that [`public/robots.txt`](../../../../apps/staff-web/public/robots.txt) has exactly the two rules `User-agent: *` and `Disallow: /` with no `Allow`, and that the portal's own `public/` directory holds no presence-named page, no served page extension and no patient-data marker in any file. The noindex and robots assertions corroborate clause 4 and `tests/indexability.test.ts`, which owns that sub-rule's authoritative proof; C01 uses them here only as part of the whole-boundary check.
- `refusesASyntheticPublicIndexingOrPublicPageSignal` feeds the same pure matchers synthetic signals one at a time: an `index, follow` robots meta, an `index,follow` googlebot meta, an `Allow: /` robots rule, presence-named fake page paths and a fake status page containing patient-data markers. It asserts each is refused, and that the real `noindex` directive is not mistaken for an indexing permission because the matcher uses a word boundary. This is the negative case: the rule must reject what the boundary exists to keep out.
- `GAP: separately hosted status, maintenance and support pages are absent` is an ACTIVE, green absence tripwire over `apps/staff-web/public/` and every `routes.ts` table under `src`. "Absent" is scoped to the authenticated staff portal: the pages themselves exist in the separate `apps/public-pages/` module and are C03's evidence. The tripwire fails the day a presence page is added to this portal, which would break the "host separately" boundary. It is not a skipped test: `scripts/check-constraints.py:52-53` rejects an added `it.skip(` in a `.ts` file and AGENTS.md forbids skipped tests.

No production class is exercised: this is a configuration and asset-level proof over files that already ship. The production surface is the served document and its public directory, not a Java or React class.

Production configuration exercised by these tests (no production source was changed):

- [`index.html`](../../../../apps/staff-web/index.html) — the production document; its `<head>` carries a single `noindex, nofollow` meta.
- [`public/robots.txt`](../../../../apps/staff-web/public/robots.txt) — the served crawl directive, exactly two rules.
- [`public/manifest.webmanifest`](../../../../apps/staff-web/public/manifest.webmanifest) — the only other file in the portal's public directory; it declares the staff portal app, not a public presence surface.
- `src/features/*/routes.ts` — the authenticated feature route tables; none declares a top-level `/status`, `/maintenance`, `/support`, `/marketing` or `/public` page.
- [`README.md`](../../../../apps/public-pages/README.md) in the separate `apps/public-pages/` module — the six presence pages are hosted there, outside the authenticated portal, which is what "host separately" requires.

Pre-existing assertions that corroborate the boundary, not duplicated here: [`tests/indexability.test.ts`](../../../../apps/staff-web/tests/indexability.test.ts) already asserts the exact noindex meta and robots rules (clause 4, task C04); [`tests/publicPages.test.ts`](../../../../apps/staff-web/tests/publicPages.test.ts) already proves the six separately hosted pages, their legal links, no external resources and no patient data (clause 3, task C03); the neighbouring [`split-code-feature-self-host-fonts-arabic.test.ts`](../../../../apps/staff-web/tests/spec/platform/split-code-feature-self-host-fonts-arabic.test.ts) establishes the read-only spec style this file reuses. This task adds one clause-focused boundary proof and changes no production code, existing test, contract or migration.

## Gap

Clause 1 is the umbrella title of WEB-13, not a fourth rule. Its three concrete sub-rules are owned by sibling tasks and are deliberately not reproduced here:

- C02 — "V1 has no public marketing site." ([task 00769000](../../../../tasks/00769000-SPEC-WEB-13-C02.md)).
- C03 — the separately hosted status, maintenance and support pages with privacy notice, accessibility statement, terms and support contact ([task 00770000](../../../../tasks/00770000-SPEC-WEB-13-C03.md)). The pages already exist in `apps/public-pages/` from an earlier delivery; C03 records their evidence.
- C04 — `noindex` on every authenticated web application and `Disallow: /` in `robots.txt` ([task 00771000](../../../../tasks/00771000-SPEC-WEB-13-C04.md)).

C01 proves only that the authenticated portal keeps its own surface clean of public presence; it does not re-prove each sub-rule, so the status is `Partly proven` rather than `Proven by tests`. The maintainer confirmation asked for by the task brief was not available in this worker context. The brief and the story name these exact scope paths, so they were treated as the intended planned paths and no other path was created. Closing the umbrella to `Proven by tests` needs no production change: it needs the three sibling clause documents and their evidence to land on the same branch, which is one integration step rather than a new build task.

The complete positive test that would prove the presence pages are hosted separately and safely is preserved here verbatim. It is deliberately not added as an active or `it.skip("GAP: ...")` test to the TypeScript spec: the active absence tripwire above and a positive proof of the same boundary cannot both hold, and Markdown is exempt from the constraint that rejects added skipped tests in `.ts` files. Its natural owner is C03; if a future task adds presence pages inside the staff portal, this test must replace the active tripwire rather than sit beside it.

```ts
it.skip("GAP: status, maintenance and support pages are hosted separately without patient data", () => {
  // moduleRoot is apps/staff-web; the separately hosted module is a sibling.
  const presenceRoot = resolve(moduleRoot, '../public-pages');
  const pages = [
    'status.html', 'maintenance.html', 'support.html',
    'privacy.html', 'accessibility.html', 'terms.html',
  ];
  for (const page of pages) {
    expect(existsSync(resolve(presenceRoot, page)), `${page} exists`).toBe(true);
    const html = readFileSync(resolve(presenceRoot, page), 'utf8');
    // No clinical identifiers and no client script on a public page.
    expect(html).not.toMatch(/\bpatients?\b|\bmrns?\b|\bcharts?\b/i);
    expect(html).not.toMatch(/<script\b/i);
  }
  // Contact addresses use reserved invalid domains, never a real address.
  expect(readFileSync(resolve(presenceRoot, 'README.md'), 'utf8')).toMatch(/host/i);
});
```
