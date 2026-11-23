import { createContext, useContext } from 'react';

/** Callbacks the application shell supplies for session-related state views. */
export interface SessionContextValue {
  /** Reports an expired session once, for the shell to handle globally. */
  onSessionExpired: () => void;
  /**
   * Returns the user to sign-in while keeping their place. Optional because
   * screens without a sign-in owner still share this context's default.
   */
  onSignIn?: (() => void) | undefined;
}

/** Silent default so a state view never throws when no session owner is mounted. */
export const defaultSessionContext: SessionContextValue = {
  onSessionExpired: () => undefined,
  onSignIn: () => undefined,
};

/** Owns the handler that moves a user to the session-expired screen. */
export const SessionContext = createContext<SessionContextValue>(defaultSessionContext);

/** Read the session callback supplied by the application shell. */
export function useSession(): SessionContextValue {
  return useContext(SessionContext);
}
