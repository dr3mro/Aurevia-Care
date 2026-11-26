- [x] DOCS-REFERENCE-SOURCE-LINKS · Published developer reference source links return 404
  - requirements: OPS-06
  - scope: scripts/docs.sh, scripts/link_developer_reference.py, scripts/ownership.json, scripts/tests/test_link_developer_reference.py, docs/reference/maintainer.md, docs/reference/testing-guide.md
  - agent: orca
  - pr: 1754
  - evidence:
    Published-site verification (tool, 2026-11-26 after PR #1754 merged): Pages run 45812962921 `success`; live api-catalog page fetched with pinned `blob/993154c0…` URLs, 0 repo-relative hrefs and the maintainer-guide internal link intact; `md_docs_2reference_2maintainer.html`, `staff-web/index.html`, `patient-mobile/index.html` and a pinned CatalogService.java blob URL all returned HTTP 200. Delivery run 45810894502 `success` on `4103de64…`; PR #1754 merged as `137de805…` (rebased head `db4cd5b3…`) with final PR checks pass on Swarm gate, Policy and tooling, Delivery tooling tests, Java and database verification and Frontend standards; an earlier Swarm gate attempt 45810894492 failed and was superseded by a passing run.
    Tool evidence (worktree `.workspaces/docs-source-links`, commit `4103de64b37d2c15a4aa521da558d3eeb06e048a`, branch `docs/source-links`, PR #1754). Red first: `python3 -I -m unittest discover -s scripts/tests -p 'test_link_developer_reference.py'` failed with `FileNotFoundError: .../scripts/link_developer_reference.py` before implementation. Green: same command `Ran 23 tests in 0.021s`, `OK`. `bash scripts/docs.sh` exit 0 with `Rewrote 1556 link(s) in 15 file(s).` and `No unresolved repository-relative links.`; generated site has 0 root pages with repo-relative hrefs, 1,556 permalinks all pinned to the built SHA, 17 `/tree/` links, Doxygen internal link and TypeDoc sub-sites unchanged; one pinned permalink fetched from GitHub returned `CatalogService.java`. `bash scripts/python-coverage.sh` exit 0, `python coverage: every changed script line is covered` (new files marked `git add -N`). `python3 -I scripts/check-docs.py`=0, `check-ownership.py`=0, `check-constraints.py`=0, `check-names.py`=0, `shellcheck scripts/docs.sh`=0, `git diff --check`=0. Full suite `Ran 2030 tests in 165.693s`, `FAILED (errors=21)`; all 21 are the documented `No module named 'yaml'` worker-image limit, no other failures. Reviewers: plan GO after the initial NO-GO gaps were fixed (mixed depths, directory targets, fail-closed derivation, `--check` wiring, real-output assertion, branch-to-test map); code GO; coverage GO; devsecops GO. QA: local matrix passed, live-site acceptance held pending merge/deploy. No simulated-user or human-participant evidence.
  - details:
    Agent brief. Reproduce before changing production code.

    Observed defect (tool evidence, 2026-11-26): the published page
    https://dr3mro.github.io/Aurevia-Care/md_docs_2reference_2modules_2api-catalog.html
    contains links such as `../../../apps/api/src/main/java/com/aurevia/catalog/CatalogService.java`.
    Doxygen copies the relative links from `docs/reference/modules/*.md` verbatim into
    flat HTML pages at the Pages site root, and `pages.yml` uploads only
    `build/developer-reference/html`; from `/Aurevia-Care/<page>.html` the `../../../`
    prefix resolves above the site root, so every source link is a 404. The same
    pattern affects 1,610 links in 15 files under `docs/reference/`.

    Goal: every source-code link in the published reference resolves to the exact
    repository file at the commit that built the reference, while the Markdown
    sources keep repo-relative links that `scripts/check-docs.py` can still validate.

    Acceptance:
    1. `scripts/docs.sh` rewrites repository-relative links in root-level generated
       Doxygen HTML to stable GitHub blob URLs (`<owner>/<repo>/blob/<commit>/<path>`),
       using the current commit so the link cannot drift with a branch move.
    2. Doxygen's own generated links and the TypeDoc sub-sites are unchanged.
    3. The Markdown files under `docs/reference/` are unchanged; their relative links
       continue to resolve in the checkout and pass `python3 -I scripts/check-docs.py`.
    4. New Python has a docstring, a `--check` mode to report unresolved links, and
       100% line/branch coverage by named tests under `scripts/tests/`.
    5. The build script and the new helper are documented in `docs/reference/maintainer.md`
       and `docs/reference/testing-guide.md`, and declared in `scripts/ownership.json`.
    6. Gates: `bash scripts/docs.sh`, `python3 -I -m unittest discover -s scripts/tests -q`,
       `bash scripts/python-coverage.sh`, `python3 -I scripts/check-docs.py`,
       `python3 -I scripts/check-ownership.py`, `python3 -I scripts/check-constraints.py`,
       `python3 -I scripts/check-names.py`, `git diff --check`.

    Before you start read the "Instructions for every agent" in tasks/_head.md.
    Evidence source: published-page fetch (tool), local source and workflow reads (tool).
    No simulated-user or human-participant evidence is claimed.
