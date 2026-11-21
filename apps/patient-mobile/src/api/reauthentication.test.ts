/**
 * Acceptance tests for the sensitive-action re-authentication gate
 * (PAT-02, PAT-03). A sensitive account change is allowed only when the
 * patient signed in recently; otherwise the caller must ask for sign-in again.
 */
import { createSensitiveActionGuard } from './reauthentication';
import type { AuthenticationClock } from './reauthentication';

/** Mutable synthetic clock: no wall-clock dependency in the tests. */
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

describe('createSensitiveActionGuard (PAT-02, PAT-03)', () => {
  it('requires re-authentication when no sign-in has been recorded', () => {
    const guard = createSensitiveActionGuard({ clock: clock(T0), freshnessMs: FRESHNESS_MS });

    expect(guard.authorizeSensitiveAction()).toEqual({
      allowed: false,
      reason: 'reauth_required',
    });
  });

  it('allows a sensitive action when the last sign-in is within the window', () => {
    const c = clock(T0);
    const guard = createSensitiveActionGuard({ clock: c, freshnessMs: FRESHNESS_MS });
    guard.recordAuthentication();
    c.set(T0 + FRESHNESS_MS - 1);

    expect(guard.authorizeSensitiveAction()).toEqual({ allowed: true, reason: 'fresh' });
  });

  it('allows a sensitive action exactly at the freshness boundary', () => {
    const c = clock(T0);
    const guard = createSensitiveActionGuard({ clock: c, freshnessMs: FRESHNESS_MS });
    guard.recordAuthentication();
    c.set(T0 + FRESHNESS_MS);

    expect(guard.authorizeSensitiveAction()).toEqual({ allowed: true, reason: 'fresh' });
  });

  it('requires re-authentication once the last sign-in is older than the window', () => {
    const c = clock(T0);
    const guard = createSensitiveActionGuard({ clock: c, freshnessMs: FRESHNESS_MS });
    guard.recordAuthentication();
    c.set(T0 + FRESHNESS_MS + 1);

    expect(guard.authorizeSensitiveAction()).toEqual({
      allowed: false,
      reason: 'reauth_required',
    });
  });

  it('refreshes the authentication time when the patient signs in again', () => {
    const c = clock(T0);
    const guard = createSensitiveActionGuard({ clock: c, freshnessMs: FRESHNESS_MS });
    guard.recordAuthentication();
    c.set(T0 + FRESHNESS_MS + 1);
    expect(guard.authorizeSensitiveAction().reason).toBe('reauth_required');

    guard.recordAuthentication();

    expect(guard.authorizeSensitiveAction()).toEqual({ allowed: true, reason: 'fresh' });
  });

  it('forgets the recorded authentication on reset so sign-out forces sign-in', () => {
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
