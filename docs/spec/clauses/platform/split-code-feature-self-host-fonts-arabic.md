# WEB-08 clause 4 — split code per feature, self-host fonts with Arabic coverage, serve hashed assets as immutable

## Clause

Split code per feature, self-host fonts with Arabic coverage, serve hashed assets as immutable, and load no third-party scripts or trackers.

## Status

Partly proven

## Evidence

Test file: [`split-code-feature-self-host-fonts-arabic.test.ts`](../../../../apps/staff-web/tests/spec/platform/split-code-feature-self-host-fonts-arabic.test.ts).
It is a read-only Vitest specification over the staff-web source and build configuration with synthetic HTML input; no database, network, patient record or credential is involved. Evidence source for the executed commands is the tool output; the code survey statements come from reading the linked files. No human, clinical or partner evidence is held here.

Test methods (one positive rule proof, one refusal, two active gap tripwires):

- `provesPerFeatureCodeSplittingHashedAssetsAndNoThirdPartyScripts` asserts that each of the 16 `src/features/*/routes.ts` modules with screens registers a `lazy()` screen using a dynamic `import()`, that the one screen-less feature (`print`) declares an explicit empty route table and claims no pattern, that the shared registry discovers modules with `import.meta.glob`, that the real `index.html` is not refused by the forbidden-script matcher, and that `vite.config.ts` sets no rollup filename override so Vite's default content-hashed `dist/assets` naming applies. The built directory is deliberately not read: it does not exist when this suite runs alone.
- `refusesAnInjectedThirdPartyTrackerScript` feeds the same matcher four synthetic forbidden markers — a remote `https://` script source on a reserved `.test` domain, `googletagmanager`, `google-analytics` and `analytics` — each on its own, and asserts each is refused. This is the negative case for the no-third-party-script half.
- `GAP: self-hosted Arabic-coverage fonts are absent` pins the current absence of font files, `@font-face` rules and an Arabic `unicode-range`. It is an active, green test because the repository forbids skipped tests.
- `GAP: immutable cache headers for hashed assets are absent` pins the current absence of a `Cache-Control: immutable` policy in the deployment configuration.

Production configuration exercised by these tests:

- [`routes.ts`](../../../../apps/staff-web/src/features/patients/routes.ts) (one per business feature, 17 in this snapshot) — each owns its route table and lazy-loads its screens; the patients file is cited as the representative example.
- [`routes.tsx`](../../../../apps/staff-web/src/app/routes.tsx) — the shared registry that discovers feature modules with `import.meta.glob` and composes them with the app's own lazy pages.
- [`index.html`](../../../../apps/staff-web/index.html) — the production document; its only script is the local `/src/main.tsx` entry module.
- [`vite.config.ts`](../../../../apps/staff-web/vite.config.ts) — the Vite configuration; with no `build.rollupOptions.output` override the default hashed asset names are used.
- [`check-bundle-size.mjs`](../../../../apps/staff-web/scripts/check-bundle-size.mjs) and [`check.sh`](../../../../apps/staff-web/check.sh) — the 250 KB gzip budget gate that runs after `npm run build` against `dist/assets`.

Pre-existing assertions that corroborate the built halves, not duplicated here:
[`tests/indexability.test.ts`](../../../../apps/staff-web/tests/indexability.test.ts) already asserts that the document loads no analytics, tracker or external script; [`screenLoading.test.tsx`](../../../../apps/staff-web/src/app/screenLoading.test.tsx) asserts that the lazy route fallback resolves for every feature screen; and [`check-bundle-size.test.mjs`](../../../../apps/staff-web/scripts/check-bundle-size.test.mjs) asserts the 250 KB gate is wired after the build. This task adds one clause-focused proof and changes no production code, existing test, contract or migration.

## Gap

Two of the four sub-rules are not built in this snapshot, so the status is `Partly proven` rather than `Proven by tests`:

1. **Self-hosted fonts with Arabic coverage.** No `@font-face` rule, `woff2`/`ttf` font file, `public/fonts/` directory or Arabic `unicode-range` declaration exists anywhere in `apps/staff-web`. Arabic text is supported by the i18n and RTL stylesheets, but the typeface itself is not self-hosted and carries no Arabic coverage declaration.
2. **Hashed assets served as immutable.** Vite emits hashed names by default, but no deployment configuration marks those assets `Cache-Control: public, max-age=…, immutable`. [`infra/app/nginx.conf`](../../../../infra/app/nginx.conf) has only an `/api/` proxy and a catch-all `/` location; there is no `/assets` location and no `add_header Cache-Control` directive. The hashed names are therefore an unconfigured default, not a tested caching contract.

Closing both halves needs **at least two separate tasks**:

- A staff-web presentation task: add the licensed font binaries under `apps/staff-web/public/fonts/`, declare an `@font-face` with an Arabic `unicode-range` in a theme stylesheet under `apps/staff-web/src/theme/`, and link the stylesheet from `apps/staff-web/index.html` (or import it from `apps/staff-web/src/main.tsx`). It also replaces the font tripwire with the positive proof below.
- A deployment-configuration task, outside this worker's scope: add an `/assets` location with `Cache-Control: public, max-age=<long>, immutable` to the production web server configuration (for example `infra/app/nginx.conf`) while keeping the HTML document revalidated, and replace the cache tripwire with the positive proof below.

Neither task is assumed here, and no file outside the two planned scope paths was created or changed. The maintainer confirmation asked for by the task brief was not available in this worker context; the brief and the story name these exact paths, so they were treated as the intended planned paths and no other path was created.

The complete tests that would prove the missing behaviour are preserved here verbatim for those tasks. They are deliberately **not** added as active or `it.skip("GAP: ...")` tests to the TypeScript spec: `scripts/check-constraints.py:52-53` rejects any added `it.skip(` in a `.ts` file, and AGENTS.md forbids skipped tests. Markdown is exempt from that constraint, so the literal shape is quoted here instead.

```ts
it.skip("GAP: self-hosted fonts with Arabic coverage are served", () => {
  // A font task adds a theme stylesheet declaring a local, Arabic-capable face.
  const theme = read('src/theme/fonts.css');
  expect(theme).toMatch(/@font-face\b/);
  expect(theme).toMatch(/url\(["']?\/fonts\/[\w-]+\.woff2/i);
  // The Arabic block begins at U+0600; a Latin-only face must not satisfy it.
  expect(theme).toMatch(/unicode-range\s*:[^;}]*U\+06/i);

  // The declared font binary is checked in and the stylesheet is loaded.
  expect(listFiles('public/fonts').filter((path) => /\.woff2$/i.test(path)).length).toBeGreaterThan(0);
  const loaded = [...listFiles('src'), 'index.html'].some((path) => /fonts\.css/.test(read(path)));
  expect(loaded).toBe(true);
});
```

```ts
it.skip("GAP: hashed assets are served with an immutable Cache-Control header", () => {
  // A deployment task adds an /assets location to the production web server config.
  const nginx = read('../../infra/app/nginx.conf');
  expect(nginx).toMatch(/location\s+~?\s*\/assets\b/);
  expect(nginx).toMatch(/add_header\s+Cache-Control\s+["']public,\s*max-age=\d+,\s*immutable/i);
  // The document itself must stay revalidated so a new deploy is picked up.
  expect(nginx).toMatch(/add_header\s+Cache-Control\s+["']no-cache/i);
});
```

Both quoted tests rely on the `read()` and `listFiles()` helpers already defined in the spec file above. They must replace the corresponding active tripwires, not be added beside them: an active absence assertion and a positive proof of the same rule cannot both hold.
