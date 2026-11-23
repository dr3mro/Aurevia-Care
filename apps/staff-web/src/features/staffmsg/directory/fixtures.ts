import type { Contact, MessagingActor, MessagingPolicy } from './domain';

/** Synthetic organization used by every same-tenant fixture in this feature. */
export const mainOrganizationId = 'org-synthetic-main';

/** Synthetic facility codes. MAIN is the actor's home; NORTH is a crossing. */
export const mainFacilityCode = 'MAIN';
export const northFacilityCode = 'NORTH';

/** Same organization and home facility; Latin and Arabic names are both present. */
export const sameFacilityContact: Contact = {
  subject: 'synthetic-staff-nora',
  displayName: { latinName: 'Synthetic Nora', localName: 'نورة تجريبية' },
  facilityCode: mainFacilityCode,
  organizationId: mainOrganizationId,
  roles: ['coordinator', 'nurse'],
  units: ['cardiology', 'synthetic-alpha-unit'],
};

/** Same organization but a different facility; a marker is required for this person. */
export const otherFacilityContact: Contact = {
  subject: 'synthetic-staff-layla',
  displayName: { latinName: 'Synthetic Layla', localName: 'ليلى تجريبية' },
  facilityCode: northFacilityCode,
  organizationId: mainOrganizationId,
  roles: ['physician'],
  units: ['synthetic-radiology'],
};

/**
 * Different organization but the same facility code. This proves the organization
 * rule is independent of the facility rule and always fails closed.
 */
export const crossOrgContact: Contact = {
  subject: 'synthetic-staff-partner',
  displayName: { latinName: 'Synthetic Partner', localName: 'شريك تجريبي' },
  facilityCode: mainFacilityCode,
  organizationId: 'org-synthetic-partner',
  roles: ['liaison'],
  units: ['synthetic-partner-unit'],
};

/** A contact with no Arabic form and roles/units whose searchable value is last. */
export const latinOnlyContact: Contact = {
  subject: 'synthetic-staff-omar',
  displayName: { latinName: 'Synthetic Omar' },
  facilityCode: mainFacilityCode,
  organizationId: mainOrganizationId,
  roles: ['receptionist'],
  units: ['synthetic-clinic', 'synthetic-front-desk'],
};

/** Every synthetic contact, in stable directory order. */
export const directoryContacts: readonly Contact[] = [
  sameFacilityContact,
  otherFacilityContact,
  crossOrgContact,
  latinOnlyContact,
];

/** The synthetic actor browsing the directory from the MAIN facility. */
export const mainActor: MessagingActor = {
  organizationId: mainOrganizationId,
  facilityCode: mainFacilityCode,
};

/** An approved tenant flag: cross-facility messaging is permitted but marked. */
export const policyOpen: MessagingPolicy = { crossFacilityEnabled: true };

/** The unapproved default: cross-facility messaging is refused. */
export const policyClosed: MessagingPolicy = { crossFacilityEnabled: false };
