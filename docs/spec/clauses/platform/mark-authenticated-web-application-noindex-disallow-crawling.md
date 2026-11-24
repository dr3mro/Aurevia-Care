# WEB-13 clause 4 — mark every authenticated web application noindex and disallow crawling

## Clause

Mark every authenticated web application `noindex` and disallow crawling in `robots.txt`.

## Status

Proven by tests

## Evidence

Test file:
[`mark-authenticated-web-application-noindex-disallow-crawling.test.ts`](../../../../apps/staff-web/tests/spec/platform/mark-authenticated-web-application-noindex-disallow-crawling.test.ts).
It is a read-only Vitest specification over the served document and crawler
policy, with synthetic string inputs; no database, network, patient record or
credential is involved. Evidence source for the executed commands is the tool
output recorded in the worker notes; the file and configuration survey
statements come from reading the linked files. No human, clinical or partner
evidence is held here.

Test methods (one positive rule proof and one active refusal case):

- `marksAuthenticatedPortalNoindexInHeadAndDisallowsAllCrawling` guards against
  empty reads, then asserts the document head contains exactly one robots meta
  equal to `<meta name="robots" content="noindex, nofollow" />`, that its content
  is `noindex, nofollow`, that no head meta permits indexing, that the parsed
  `robots.txt` rules equal `User-agent: *` followed by `Disallow: /`, and that no
  rule allows crawling. An added conflicting `index, follow` meta or an `Allow:`
  directive fails this test.
- `refusesSyntheticIndexingAndCrawlAllowingSignals` feeds synthetic indexing
  metas (`index, follow`) to robots and googlebot and an `Allow: /` directive through the same
  `hasIndexingSignal`, `allowsCrawling`, `headMetas`, `metaContent` and
  `robotsRules` matchers used by the positive test, and asserts each is detected.
  It then asserts that a body-level indexing tag is not treated as a
  document-level signal, that the real `noindex` directive is not flagged (the
  `index` token is word-bounded, so the letters inside `noindex` do not count),
  and that a genuine `Disallow: /` body is not treated as an allowance. This
  proves the tripwire can fail and cannot produce the word-boundary false
  positive.

Production configuration and files exercised by these tests (all read-only):

- [`apps/staff-web/index.html`](../../../../apps/staff-web/index.html) — the
  authenticated portal document served by Vite; its head at line 7 carries the
  single exact `noindex, nofollow` robots directive and its only script is the
  local `/src/main.tsx` entry module.
- [`apps/staff-web/public/robots.txt`](../../../../apps/staff-web/public/robots.txt)
  — the crawler policy served from `/robots.txt`; it disallows every user agent
  from every path with exactly two rules.
- [`apps/staff-web/tests/indexability.test.ts`](../../../../apps/staff-web/tests/indexability.test.ts)
  — the pre-existing authoritative test that already asserts the same `noindex`
  meta and `robots.txt` rules with exact equality; the new spec re-asserts a
  bounded tripwire for this clause and leaves the stricter checks unchanged.

Entry-point-to-test trace: Vite serves `index.html` as the authenticated staff
document and `public/robots.txt` as the site's crawler policy. The spec's read
helper resolves from the staff-web module root, reads those two served files and
asserts their exact directives; the refusal case reuses the identical matchers
on synthetic strings, so the positive assertion is demonstrably capable of
failing. Only configuration already shipped in this repository is read.

Evidence boundary: `Proven by tests` means that the authenticated portal's
document and crawler policy carry the two required directives in this snapshot
and that the tripwire fails if either is weakened. It does not assert the live
deployed host's headers or robots policy; verifying the production deployment
remains an operational gate recorded in the production-readiness evidence,
outside a worker's scope. The proof is a syntax-aware tripwire, not a general
crawling-proof: it detects a conflicting head meta or an `Allow:` directive in
the parsed rules, but a novel meta declaration style outside the matcher would
not be recognised.

## Gap

None. The clause is fully met in this snapshot, so no skipped or pending test is
needed. The story's step 3 suggested an `it.skip("GAP: ...")` test for an unbuilt
clause; that is untrusted story text, conflicts with `scripts/check-constraints.py`,
and does not apply here because there is no missing behaviour. Maintainer
confirmation of the two planned scope paths was not available in this worker;
the brief and task name them explicitly, so they were treated as the intended
paths and no other file was created.
