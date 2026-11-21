/**
 * Idle-timeout session guard for the patient app (PAT-02, PAT-03).
 *
 * The guard owns one deadline. Any user activity calls {@link IdleSessionGuard.touch}
 * to reset it; when the deadline elapses it invokes the supplied callback once.
 * {@link createSignOutOnIdle} wires that callback to `clearToken`, which removes
 * the OS credential and all transient clinical memory, so an idle patient is
 * signed out and nothing clinical survives in the app process.
 */
import { clearToken } from './tokenStore';

/** Options for one idle deadline. */
export interface IdleSessionOptions {
  /** Milliseconds of inactivity before the deadline fires. */
  readonly idleMs: number;
  /** Invoked once when the idle deadline elapses. */
  readonly onIdle: () => void | Promise<void>;
}

/** Controls an active idle deadline. */
export interface IdleSessionGuard {
  /** Reset the deadline after user activity. */
  touch(): void;
  /** Cancel the deadline (for example on sign-out or unmount). */
  dispose(): void;
}

/**
 * Create an idle deadline that calls `onIdle` after `idleMs` of inactivity.
 *
 * @param options idle interval and the callback to run when it elapses
 * @returns a guard whose `touch` resets the deadline and `dispose` cancels it
 */
export function createIdleSessionGuard(options: IdleSessionOptions): IdleSessionGuard {
  let timer: ReturnType<typeof setTimeout> | undefined;

  const arm = (): void => {
    if (timer !== undefined) {
      clearTimeout(timer);
    }
    timer = setTimeout(() => {
      timer = undefined;
      void options.onIdle();
    }, options.idleMs);
  };

  arm();

  return {
    touch: arm,
    dispose: () => {
      if (timer !== undefined) {
        clearTimeout(timer);
        timer = undefined;
      }
    },
  };
}

/**
 * Build the production idle guard: when idle, clear the OS credential and all
 * transient clinical data, which signs the patient out (PAT-02).
 *
 * @param idleMs milliseconds of inactivity before sign-out
 * @returns a guard that signs out on idle and can be touched or disposed
 */
export function createSignOutOnIdle(idleMs: number): IdleSessionGuard {
  return createIdleSessionGuard({ idleMs, onIdle: () => clearToken() });
}
