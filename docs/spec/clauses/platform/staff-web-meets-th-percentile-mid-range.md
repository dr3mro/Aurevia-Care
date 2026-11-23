# WEB-08 clause 2 — laptop p75 Core Web Vitals (evidence and gap)

## Clause

Staff web meets, at the 75th percentile on a mid-range laptop over broadband, largest contentful paint of 2.5 s or less, interaction to next paint of 200 ms or less and layout shift of 0.1 or less.

The authoritative wording is in [SMARTCLINIC_V1_SPEC.md](../../../../SMARTCLINIC_V1_SPEC.md), requirement WEB-08.

## Status

Not built

## Evidence

Scope: this status covers clause 2 of WEB-08 only. It does not claim the other
sentences of WEB-08 (250 KB gzip route chunks in CI, per-feature code splitting,
self-hosted Arabic-capable fonts, immutable hashed assets and no third-party
scripts). No production code, dependency, contract, migration or existing test
was changed by this evidence task.

**The enabled tests are structural contract evidence only.** They prove that the
approved budgets and qualifiers are recorded in one place and that this document
reports the rule honestly. They do **not** measure browser paint, interaction or
layout timing: jsdom has no rendering pipeline and no usable `PerformanceObserver`
for LCP, INP or CLS, so a green run here is not capability proof. Status is
therefore `Not built`, not `Proven by tests`.

The clause test is [staff-web-meets-th-percentile-mid-range.test.ts](../../../../apps/staff-web/tests/spec/platform/staff-web-meets-th-percentile-mid-range.test.ts),
an enabled (no skipped or focused cases) Vitest/jsdom `.ts` file using the
synthetic identity from [synthetic.ts](../../../../apps/staff-web/src/fixtures/synthetic.ts):
`synthetic.reception` at the synthetic `MAIN` facility. It is green: 2 tests,
0 failures, 0 skipped.

- `recordsP75LaptopThresholdsAsTheClauseContract` (rule recorded) — asserts the
  single approved budget (LCP 2500 ms, INP 200 ms, CLS 0.1), that the mobile LCP
  target (4000 ms) is a different, larger number, that the approved synthetic
  profile is accepted by the local validator, and that this document contains
  the word-for-word clause, all three qualifiers (75th percentile, mid-range
  laptop, broadband), all three thresholds, the `Not built` status and the
  structural-only label.
- `rejectsRelaxingALaptopThresholdOrSubstitutingMobileTargets` (negative) — a
  local, deterministic validator refuses a changed percentile, a phone viewport,
  a phone device class, a cellular network, fewer than 20 samples, LCP above
  2500 ms, INP above 200 ms and CLS above 0.1, each with an asserted refusal
  reason. It also refuses the mobile 4000 ms LCP target and a full mobile
  substitute, while accepting the approved synthetic laptop profile.

Production units exercised: **none**. There is no LCP/INP/CLS collector, no
`PerformanceObserver` binding and no percentile aggregation in the staff web;
the only WEB-08 implementation found on `main` belongs to other clauses:

- [vite.config.ts](../../../../apps/staff-web/vite.config.ts) — jsdom test
  environment and Vitest coverage thresholds; no performance measurement.
- [check-bundle-size.mjs](../../../../apps/staff-web/scripts/check-bundle-size.mjs)
  — `MAX_CHUNK_GZIP_BYTES` and `assertWithinBudget` enforce the 250 KB gzip
  budget of a later WEB-08 clause, invoked by `check.sh`; not a paint-timing gate.
- [playwright.config.ts](../../../../apps/staff-web/e2e/playwright.config.ts)
  and [smoke.spec.ts](../../../../apps/staff-web/e2e/smoke.spec.ts) — a browser
  matrix with a `laptop-1280x720` project and accessibility smoke checks, but no
  throttling, no metrics collector and no percentile assertion.

Verification recorded in the worker notes (tool evidence): the targeted Vitest
run, `bash apps/staff-web/check.sh`, `python3 -I scripts/check-docs.py` and
`python3 -I scripts/check-constraints.py`. No real patient data, credential or
third-party script is used anywhere in this evidence. The `web-vitals` package
is deliberately **not** a dependency of this task.

## Gap

The rule is absent. To make Status `Proven by tests` a later task must add a
real-browser measurement harness and a gate, then enable an active version of
the test below. Nothing in this task's scope may implement it (no production
code, no dependency and no new browser suite are allowed here).

Missing pieces, in order:

1. **A collector.** Install a `PerformanceObserver`-based LCP/INP/CLS collector
   before navigation and publish the last value of each metric. The `web-vitals`
   package (a new dependency, deliberately not added by this evidence task) or a
   hand-written observer can do this. No source file under `apps/staff-web/src`
   currently exposes such a collector.
2. **A reproducible mid-range-laptop/broadband profile.** Use the existing
   `laptop-1280x720` Playwright project in
   `apps/staff-web/e2e/playwright.config.ts`, apply fourfold CPU throttling and a
   5 Mbps / 40 ms broadband profile through a Chromium CDP session, disable the
   cache and take cold samples. Pin the CI runner class so the 75th percentile
   is measured on a stable host rather than a shared cloud worker. Register the
   spec only in that Chromium project (or run `npm run e2e -- --project=laptop-1280x720`);
   do not add `test.skip` or `test.only` to guard the other matrix projects.
3. **Enough samples and a p75 gate.** Collect at least 20 (the sketch below uses
   24) cold navigations, compute a true nearest-rank 75th percentile per metric,
   and fail when LCP > 2500 ms, INP > 200 ms or CLS > 0.1. Assert the qualifiers
   explicitly so the mobile 4000 ms LCP target from task
   `RESP-E2E-MOBILE-PERFORMANCE` can never silently replace the laptop budget.
4. **CI wiring and documentation.** Run the perf spec in the release-candidate
   pipeline, keep the report as WEB-08 evidence, and change this document's
   Status to `Proven by tests` only after the gate is green on real hardware.

Files a later implementation would change (proposed, not created here): a new
`apps/staff-web/e2e/performance/web-vitals.spec.ts`, a collector module under
`apps/staff-web/e2e/`, `apps/staff-web/e2e/playwright.config.ts`, the staff-web
dependency manifest and lockfile, the release-candidate workflow, and this
document. This is **one** cross-boundary implementation task for the harness and
gate; a **second**, separate remediation task is needed only if the measured
p75 breaches a budget and the application itself must be optimized.

Measurement caveats the implementation must record alongside any result: the
CPU rate 4 / 5 Mbps / 40 ms figures are a declared emulation profile, not proof
of a particular physical laptop; the CDP session is Chromium-only and matches
the `laptop-1280x720` Chromium project, not the WebKit or Firefox rows; INP is
approximated from `event` entries keyed by `interactionId`, which is
Chromium-dependent and whose `duration` is rounded, so the field metric and the
lab metric are not identical; buffered LCP must be read only after the page has
settled; each sample uses a fresh page with the cache disabled to keep it cold;
and the p75 is a nearest-rank value over raw samples (index 17 of 24).

The complete test that would prove clause 2, to be enabled verbatim by that
implementation task (Playwright, not jsdom):

```ts
import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';

/** One real-Chromium paint sample; never synthesised or inferred in jsdom. */
interface VitalSample {
  readonly lcpMs: number;
  readonly inpMs: number;
  readonly cls: number;
}

/** WEB-08 clause 2 contract. These values are never relaxed by this suite. */
interface LaptopProfile {
  readonly percentile: 0.75;
  readonly width: 1280;
  readonly height: 720;
  readonly isMobile: false;
  readonly deviceClass: 'mid-range laptop';
  readonly connection: 'broadband';
  readonly minimumSamples: 20;
  readonly lcpMs: 2500;
  readonly inpMs: 200;
  readonly cls: 0.1;
}

/** The approved laptop p75 profile, bound to the existing Playwright project. */
const laptop: LaptopProfile = {
  percentile: 0.75,
  width: 1280,
  height: 720,
  isMobile: false,
  deviceClass: 'mid-range laptop',
  connection: 'broadband',
  minimumSamples: 20,
  lcpMs: 2500,
  inpMs: 200,
  cls: 0.1,
};

/** Nearest-rank percentile over raw samples, failing closed on an empty sample. */
function nearestRank(values: readonly number[], fraction: number): number {
  expect(values.length).toBeGreaterThan(0);
  const sorted = [...values].sort((left, right) => left - right);
  const value = sorted[Math.ceil(fraction * sorted.length) - 1];
  if (value === undefined) {
    throw new Error('percentile index out of range');
  }
  return value;
}

/** Install buffered paint observers before any application code runs. */
async function installCollector(page: Page): Promise<void> {
  await page.addInitScript(() => {
    // Mutable inside the serialized collector; the published snapshot is read-only.
    const sample: { lcpMs: number; inpMs: number; cls: number } = {
      lcpMs: 0,
      inpMs: 0,
      cls: 0,
    };
    const interactions = new Map<number, number>();

    new PerformanceObserver((list) => {
      for (const entry of list.getEntries()) {
        sample.lcpMs = Math.max(sample.lcpMs, entry.startTime);
      }
    }).observe({ type: 'largest-contentful-paint', buffered: true });

    new PerformanceObserver((list) => {
      for (const entry of list.getEntries()) {
        const shift = entry as PerformanceEntry & { hadRecentInput: boolean; value: number };
        // Input-adjacent shifts are excluded by the CLS definition.
        if (!shift.hadRecentInput) {
          sample.cls += shift.value;
        }
      }
    }).observe({ type: 'layout-shift', buffered: true });

    new PerformanceObserver((list) => {
      for (const entry of list.getEntries()) {
        const event = entry as PerformanceEntry & {
          interactionId?: number;
          duration: number;
        };
        if (event.interactionId === undefined || event.interactionId === 0) {
          continue;
        }
        const prior = interactions.get(event.interactionId) ?? 0;
        interactions.set(event.interactionId, Math.max(prior, event.duration));
      }
      sample.inpMs = Math.max(sample.inpMs, ...interactions.values());
    }).observe({ type: 'event', buffered: true });

    Object.defineProperty(window, '__webVitalSample', { value: () => ({ ...sample }) });
  });
}

/** Apply the documented mid-range-laptop / broadband profile over Chromium CDP. */
async function throttle(page: Page): Promise<void> {
  const client = await page.context().newCDPSession(page);
  await client.send('Emulation.setCPUThrottlingRate', { rate: 4 });
  await client.send('Network.emulateNetworkConditions', {
    offline: false,
    latency: 40,
    downloadThroughput: (5 * 1024 * 1024) / 8,
    uploadThroughput: (1 * 1024 * 1024) / 8,
  });
  await client.send('Network.setCacheDisabled', { cacheDisabled: true });
}

test('p75 paint vitals on a throttled mid-range laptop meet WEB-08 clause 2', async ({
  browser,
}) => {
  expect(test.info().project.name).toBe('laptop-1280x720');
  const context = await browser.newContext({
    viewport: { width: laptop.width, height: laptop.height },
    isMobile: laptop.isMobile,
    hasTouch: laptop.isMobile,
  });
  const samples: VitalSample[] = [];
  try {
    // 24 cold samples; p75 is nearest-rank index 17. At least 20 are required.
    for (let index = 0; index < 24; index += 1) {
      const page = await context.newPage();
      await installCollector(page);
      await throttle(page);
      await page.goto('/design-preview', { waitUntil: 'networkidle' });
      await page.getByRole('button', { name: 'Language' }).click();
      await page.waitForTimeout(500);
      const sample = await page.evaluate(() => {
        const read = (window as unknown as { __webVitalSample: () => VitalSample })
          .__webVitalSample;
        return read();
      });
      samples.push({ lcpMs: sample.lcpMs, inpMs: sample.inpMs, cls: sample.cls });
      await page.close();
    }
  } finally {
    await context.close();
  }

  expect(samples.length).toBeGreaterThanOrEqual(laptop.minimumSamples);
  expect(samples.every((sample) => Number.isFinite(sample.lcpMs) && sample.lcpMs > 0)).toBe(true);
  expect(nearestRank(samples.map((sample) => sample.lcpMs), laptop.percentile)).toBeLessThanOrEqual(
    laptop.lcpMs,
  );
  expect(nearestRank(samples.map((sample) => sample.inpMs), laptop.percentile)).toBeLessThanOrEqual(
    laptop.inpMs,
  );
  expect(nearestRank(samples.map((sample) => sample.cls), laptop.percentile)).toBeLessThanOrEqual(
    laptop.cls,
  );
});

/** Pure refusal check: any relaxation or device substitution is a hard failure. */
interface CandidateProfile {
  readonly percentile: number;
  readonly width: number;
  readonly height: number;
  readonly isMobile: boolean;
  readonly deviceClass: string;
  readonly connection: string;
  readonly minimumSamples: number;
  readonly lcpMs: number;
  readonly inpMs: number;
  readonly cls: number;
}

function violations(candidate: CandidateProfile): readonly string[] {
  const refused: string[] = [];
  if (candidate.percentile !== 0.75) {
    refused.push('PERCENTILE_NOT_P75');
  }
  if (candidate.width !== 1280 || candidate.height !== 720 || candidate.isMobile) {
    refused.push('PROFILE_NOT_LAPTOP');
  }
  if (candidate.deviceClass !== 'mid-range laptop' || candidate.connection !== 'broadband') {
    refused.push('DEVICE_OR_NETWORK_SUBSTITUTED');
  }
  if (candidate.minimumSamples < 20) {
    refused.push('TOO_FEW_SAMPLES');
  }
  if (candidate.lcpMs > 2500) {
    refused.push('LCP_RELAXED');
  }
  if (candidate.inpMs > 200) {
    refused.push('INP_RELAXED');
  }
  if (candidate.cls > 0.1) {
    refused.push('CLS_RELAXED');
  }
  return refused;
}

test('refuses a relaxed laptop threshold or a mobile-substituted profile', async () => {
  expect(violations(laptop)).toEqual([]);
  expect(violations({ ...laptop, lcpMs: 2600 })).toEqual(['LCP_RELAXED']);
  expect(violations({ ...laptop, inpMs: 201 })).toEqual(['INP_RELAXED']);
  expect(violations({ ...laptop, cls: 0.11 })).toEqual(['CLS_RELAXED']);
  expect(
    violations({
      ...laptop,
      width: 390,
      height: 844,
      isMobile: true,
      deviceClass: 'phone',
      connection: 'lte',
      lcpMs: 4000,
      minimumSamples: 8,
    }),
  ).toEqual([
    'PROFILE_NOT_LAPTOP',
    'DEVICE_OR_NETWORK_SUBSTITUTED',
    'TOO_FEW_SAMPLES',
    'LCP_RELAXED',
  ]);
});
```
