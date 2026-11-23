/**
 * Clause-only proof for PAT-03: a sensitive account change must be refused
 * unless the patient's sign-in is fresh. Fixtures are synthetic and the clock
 * is injected, so no wall-clock time, credential or patient data is involved.
 */
import { createSensitiveActionGuard } from '../../api/reauthentication';
import type { AuthenticationClock } from '../../api/reauthentication';

/** Mutable synthetic clock that keeps the freshness window deterministic. */
function clock(at: number): AuthenticationClock & { set(value: number): void } {
  let current = at;
  return {
    now: () => current,
    set: (value: number) => {
      current = value;
    },
  };
}

const FRESHNESS_MS = 5 * 60_000;
const T0 = 1_000_000;

describe('require renewed authentication for sensitive account changes (PAT-03)', () => {
  it('allows a sensitive change while the sign-in is fresh', () => {
    const c = clock(T0);
    const guard = createSensitiveActionGuard({ clock: c, freshnessMs: FRESHNESS_MS });
    guard.recordAuthentication();
    c.set(T0 + FRESHNESS_MS - 1);

    expect(guard.authorizeSensitiveAction()).toEqual({ allowed: true, reason: 'fresh' });
  });

  it('refuses a sensitive change when no sign-in has been recorded', () => {
    const guard = createSensitiveActionGuard({ clock: clock(T0), freshnessMs: FRESHNESS_MS });

    expect(guard.authorizeSensitiveAction()).toEqual({
      allowed: false,
      reason: 'reauth_required',
    });
  });

  it('refuses a sensitive change once the sign-in is stale', () => {
    const c = clock(T0);
    const guard = createSensitiveActionGuard({ clock: c, freshnessMs: FRESHNESS_MS });
    guard.recordAuthentication();
    c.set(T0 + FRESHNESS_MS + 1);

    expect(guard.authorizeSensitiveAction()).toEqual({
      allowed: false,
      reason: 'reauth_required',
    });
  });

  it('forces renewed authentication again after a sign-out reset', () => {
    const c = clock(T0);
    const guard = createSensitiveActionGuard({ clock: c, freshnessMs: FRESHNESS_MS });
    guard.recordAuthentication();
    expect(guard.authorizeSensitiveAction().allowed).toBe(true);

    guard.reset();

    expect(guard.authorizeSensitiveAction()).toEqual({
      allowed: false,
      reason: 'reauth_required',
    });
  });
});
