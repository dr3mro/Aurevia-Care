import type { Facility, StaffMember } from '../api/client';
import type { PersonName } from '../i18n/format';

/** A clearly synthetic tenant; never a real organization identifier. */
export interface SyntheticOrganization {
  id: string;
  name: string;
}

export const syntheticOrganizations: readonly SyntheticOrganization[] = [
  { id: '11111111-1111-1111-1111-111111111111', name: 'Synthetic Alpha Clinic' },
  { id: '22222222-2222-2222-2222-222222222222', name: 'Synthetic Beta Lab' },
];

/** A synthetic facility with the contract fields required for display. */
export type SyntheticFacility = Required<Pick<Facility, 'organizationId' | 'code' | 'name'>>;

export const syntheticFacility: SyntheticFacility = {
  organizationId: '11111111-1111-1111-1111-111111111111',
  code: 'MAIN',
  name: 'Synthetic Main Facility',
};

export const syntheticStaff: StaffMember = {
  subject: 'synthetic.reception',
  displayName: 'Synthetic Reception',
  status: 'active',
  roles: ['reception'],
};

export const syntheticPerson: PersonName = {
  latinName: 'Layla Haddad',
  localName: 'ليلى حداد',
};

export const mixedScriptLabel = 'ليلى Haddad · MAIN';

export const syntheticInstant = '2026-03-08T06:30:00Z';
export const syntheticCivilDate = '2026-02-28';
export const syntheticTimeZone = 'America/New_York';
