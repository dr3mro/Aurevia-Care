/**
 * Re-authentication gate for sensitive account changes (PAT-02, PAT-03).
 *
 * A sensitive change is allowed only while the patient's sign-in is still
 * fresh. When no sign-in has been recorded, or the last one is older than the
 * configured freshness window, the guard denies the action with the
 * `reauth_required` reason so the caller can ask the patient to sign in again.
 * The clock is injected so the window is deterministic in tests.
 */

/** Supplies the current wall-clock time in milliseconds. */
export interface AuthenticationClock {
  /** Current wall-clock time as an epoch value in milliseconds. */
  now(): number;
}

/** Outcome of checking whether a sensitive action may proceed. */
export interface SensitiveActionDecision {
  /** Whether the sensitive action may proceed. */
  readonly allowed: boolean;
  /** `fresh` when recently authenticated, otherwise `reauth_required`. */
  readonly reason: 'fresh' | 'reauth_required';
}

/** Gate that records sign-in time and authorizes sensitive changes. */
export interface SensitiveActionGuard {
  /** Record that the patient has just authenticated successfully. */
  recordAuthentication(): void;
  /** Decide whether a sensitive account change may proceed now. */
  authorizeSensitiveAction(): SensitiveActionDecision;
  /** Forget any recorded authentication (on sign-out or idle timeout). */
  reset(): void;
}

/** Options for one sensitive-action guard. */
export interface SensitiveActionGuardOptions {
  /** Clock used to measure the age of the last sign-in. */
  readonly clock: AuthenticationClock;
  /** Maximum age in milliseconds of a sign-in that is still considered fresh. */
  readonly freshnessMs: number;
}

/**
 * Create a gate that requires a recent sign-in before a sensitive change.
 *
 * The guard holds no credential and no clinical data: it stores only the time
 * of the last successful authentication. Callers must invoke
 * {@link SensitiveActionGuard.recordAuthentication} on sign-in and
 * {@link SensitiveActionGuard.reset} on sign-out.
 *
 * @param options injected clock and freshness window
 * @returns a guard that authorizes sensitive actions only after a fresh sign-in
 */
export function createSensitiveActionGuard(
  options: SensitiveActionGuardOptions,
): SensitiveActionGuard {
  let authenticatedAt: number | null = null;

  return {
    recordAuthentication(): void {
      authenticatedAt = options.clock.now();
    },
    authorizeSensitiveAction(): SensitiveActionDecision {
      const now = options.clock.now();
      if (authenticatedAt === null || now - authenticatedAt > options.freshnessMs) {
        return { allowed: false, reason: 'reauth_required' };
      }
      return { allowed: true, reason: 'fresh' };
    },
    reset(): void {
      authenticatedAt = null;
    },
  };
}
