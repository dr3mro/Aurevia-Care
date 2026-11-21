import { createContext, useContext } from 'react';
import { syntheticFacility } from '../fixtures/synthetic';

/** The active organization that scopes every data-loading request (ARC-02). */
export interface ActiveOrganizationContextValue {
  organizationId: string;
}

/**
 * Shares the organization switcher's selected tenant with data-loading hooks.
 *
 * The default is the synthetic facility's tenant so the visual foundation
 * behaves like `OrgSwitcher` (which starts on its first option) when no
 * provider is mounted. The owner of `src/app/OrgSwitcher.tsx` must mount this
 * provider with the switcher's selected id for live tenant changes to refetch;
 * that file is outside the READY-20 scope.
 */
export const ActiveOrganizationContext = createContext<ActiveOrganizationContextValue>({
  organizationId: syntheticFacility.organizationId,
});

/** Provider alias so callers need not import the raw context object. */
export const ActiveOrganizationProvider = ActiveOrganizationContext.Provider;

/** Read the active tenant id, falling back to the synthetic facility tenant. */
export function useActiveOrganization(): string {
  return useContext(ActiveOrganizationContext).organizationId;
}
