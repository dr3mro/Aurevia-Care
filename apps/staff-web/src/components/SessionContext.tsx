import { createContext, useContext } from 'react';

/** Callback invoked when a protected request reports an expired session. */
export interface SessionContextValue {
  onSessionExpired: () => void;
}

/** Silent default so a state view never throws when no session owner is mounted. */
export const defaultSessionContext: SessionContextValue = {
  onSessionExpired: () => undefined,
};

/** Owns the handler that moves a user to the session-expired screen. */
export const SessionContext = createContext<SessionContextValue>(defaultSessionContext);

/** Read the session callback supplied by the application shell. */
export function useSession(): SessionContextValue {
  return useContext(SessionContext);
}
