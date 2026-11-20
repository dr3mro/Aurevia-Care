# READY-09 — Mark the staff web non-indexable (WEB-13)

## Requirement IDs

- **WEB-13** (SMARTCLINIC_V1_SPEC.md §8.1): the staff web is an authenticated web application, so it must be `noindex` and crawling must be disallowed in `robots.txt`. No public marketing site exists in V1.

## Files changed (all inside the declared scope)

| Path | Change |
|---|---|
| `apps/staff-web/index.html` | Added `<meta name="robots" content="noindex, nofollow">` inside `<head>` |
| `apps/staff-web/public/robots.txt` | New — exactly two lines: `User-agent: *` and `Disallow: /` |
| `apps/staff-web/tests/indexability.test.ts` | New — test-first, reads both files and fails when either rule is missing |
| `.agent/pr-notes/READY-09.md` | This record (PR description) |

No analytics, tracking or external script tag was added. No file outside the scope (no trackers, no workflows, no migrations) was edited.

## Acceptance mapping

| Story step | Implementation | Test assertion |
|---|---|---|
| 1. Exact robots meta inside `<head>` | Line 7 of `index.html`, between `<head>` and `</head>` | `indexability.test.ts` extracts the `<head>…</head>` block, collects all `name="robots"` metas and asserts the array equals exactly `['<meta name="robots" content="noindex, nofollow">']` — so a later conflicting `index, follow` meta also fails |
| 2. `robots.txt` exactly two lines | New `public/robots.txt` | Test splits on newlines, trims, drops empty lines and asserts `['User-agent: *', 'Disallow: /']` |
| 3. Test in `apps/staff-web/tests` fails when a rule is missing | New `tests/indexability.test.ts` | Red run confirmed both rule tests failing before implementation; green after |
| Constraint: no analytics/tracking/external script | No such tags added | Defensive test asserts no absolute-URL `<script src>` and no analytics/tracker keywords in `index.html` |

## Entry-point-to-test trace

Vite serves `apps/staff-web/public/` as the static site root, so `public/robots.txt` is published as `/robots.txt`, and `index.html` is the SPA document shell returned for the staff portal. `tests/indexability.test.ts` reads both files from disk relative to the test module (`fileURLToPath(import.meta.url)` → module root) and asserts the two WEB-13 rules directly at their source. A missing file reads as empty text so a missing rule fails as a clear assertion rather than an uncaught `ENOENT`.

## Test-first (TDD) evidence

Red — `node_modules/.bin/vitest run tests/indexability.test.ts` on the unmodified app: **2 failed | 1 passed (3)** — the head/meta test and the robots.txt rules test failed; the no-external-script test already passed. Green after implementation: **3 passed (3)**.

## Commands run and results (tool evidence)

All commands run locally in this checkout on branch `swarm/READY-09-247`, HEAD `2d1f072`.

| Command | Result |
|---|---|
| `npm ci --no-audit --no-fund` (apps/staff-web) | `added 312 packages in 3s` (deprecation warnings only) |
| `node_modules/.bin/vitest run tests/indexability.test.ts` (before implementation) | EXIT 1 — 2 failed / 1 passed (expected red) |
| `node_modules/.bin/vitest run tests/indexability.test.ts` (after implementation) | EXIT 0 — 3 passed / 3 |
| `bash apps/staff-web/check.sh` (repo root) | EXIT 0 — gen:api:check, typecheck, lint, test:coverage all pass; 30 test files / 199 tests passed; coverage 100% statements 551/551, branches 286/286, functions 215/215, lines 521/521, 45 files fully covered |
| `npm run build` (apps/staff-web) then inspect `dist/` | EXIT 0 — `dist/robots.txt` contains the two rules and `dist/index.html` line 7 contains the exact robots meta; no external `<script src>` in the built HTML (Vite copies `public/` to the site root as expected) |
| `python3 -I scripts/check-names.py` | EXIT 0 — no ID-based path/package names |
| `bash scripts/check.sh` (repo-wide) | EXIT 1 for an **environmental, pre-existing reason unrelated to this change**: `check-pack: opencode.json is stale`. `git ls-files -v opencode.json` reports flag `S` (skip-worktree), so the worktree holds the CI profile while the committed file is the local profile. Regenerating in a clean temp copy and comparing with `git show HEAD:opencode.json` confirmed the committed profile **matches** the generator output (known false positive OPS-CW-003-01). `opencode.json` was not touched by this story. |
| `git status --short` | Only the four scoped paths above; no build/coverage artifacts tracked |

The stack traces printed by `check.sh` are expected console output from the existing negative test `tests/theme/ThemeProvider.test.tsx` (asserting `useTheme` outside a provider); the suite still reports all tests passing with exit 0.

## Evidence sources

- **Tool evidence (executed commands):** all results above, including the red/green targeted runs and the full staff-web gate.
- **Reviewer evidence:** smartclinic-senior-react read-only bounded review — see "Specialist review" below.
- **Simulated user / human participant evidence:** none. This is a static configuration/test change; no clinical or patient flow is involved.

## Defects found and decisions

- No production defect found. One self-correction during the red/green loop: the first edit used an XHTML-style self-closing `<meta … />`; the targeted test caught the mismatch and it was corrected to the exact required `<meta name="robots" content="noindex, nofollow">`.
- Decision: the test file is named `indexability.test.ts` after the business domain (crawler indexability), not after the story/gate ID, per the naming rule.
- Decision: `robots.txt` ends with a conventional trailing newline; the test ignores blank lines, so the content is still exactly the two required rules.
- Decision: a defensive third assertion guards the explicit "no analytics/tracking/external script" constraint even though the story only required a missing-rule test.
- Decision (from review): the head-scoped meta assertion collects all `robots` meta tags and asserts equality to a one-element array, so a future conflicting `index, follow` directive fails the test instead of being masked by `toContain`.
- Limitation (honest scope): the test reads the source files rather than exercising a live HTTP server; `public/` → site root is standard Vite behaviour, additionally confirmed by inspecting the built `dist/` output; no application endpoint was contacted (synthetic/static only).

## Specialist review

`smartclinic-senior-react` — bounded **read-only** review (simulated reviewer; files tool-inspected by the reviewer, gates not re-run by the reviewer). Verdict: **accept as-is, no blocking findings**. All four acceptance criteria pass; the reviewer confirmed Vite's default `public/` → `dist/` root mapping, the head-scoped extraction, CWD-independent path resolution, strict-TS/coverage scoping, and no scope diffusion. One low-severity informational hardening note (conflicting `robots` meta was not detected by a `toContain` assertion) was **folded into the test** in this change; the remaining notes (literal blank-line count, byte-coupled exact string, partial tracker regex) were assessed as harmless for this file and left unchanged. No defect was filed in BUGS.md because no observed failure occurred.

## Human / clinical evidence

None. This is a static configuration and test change; no clinical or patient flow is involved, so no simulated-user or real-participant evidence was applicable.
