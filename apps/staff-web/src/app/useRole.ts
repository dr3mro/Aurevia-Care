import { defined } from '../components/invariant';
import { syntheticStaff } from '../fixtures/synthetic';
import type { Role } from './navigation';

/**
 * Signed-in role for the pre-login shell. READY-11's OIDC login will replace
 * this synthetic source with the authenticated session's role. The role is
 * never read from the URL, query string or browser storage.
 */
const signedInRole: Role = defined(syntheticStaff.roles?.[0], 'synthetic staff role') as Role;

/** Return the role used to scope the navigation rail today. */
export function useRole(): Role {
  return signedInRole;
}
