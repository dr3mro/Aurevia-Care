/**
 * Keeps the shared toolchain runnable while the application modules are empty.
 * This file exercises TypeScript, ESLint, Prettier and Vitest thresholds.
 */
describe('frontend tooling', () => {
  it('runs the strict toolchain', () => {
    const strict: Readonly<{ enabled: true }> = { enabled: true };
    expect(strict.enabled).toBe(true);
  });
});
