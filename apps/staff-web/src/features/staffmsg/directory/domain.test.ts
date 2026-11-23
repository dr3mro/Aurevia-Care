import { describe, expect, it } from 'vitest';
import {
  CROSS_FACILITY_MESSAGING_DISABLED,
  CROSS_ORGANIZATION_MESSAGING_DISABLED,
  isOtherFacility,
  recipientRefusal,
  recipientStatus,
  searchContacts,
} from './domain';
import type { Contact, MessagingActor, MessagingPolicy } from './domain';
import {
  crossOrgContact,
  directoryContacts,
  latinOnlyContact,
  mainActor,
  mainOrganizationId,
  otherFacilityContact,
  policyClosed,
  policyOpen,
  sameFacilityContact,
} from './fixtures';

/** Combine the two public selectors so the assertions below stay readable. */
function decisionFor(actor: MessagingActor, contact: Contact, policy: MessagingPolicy) {
  return {
    status: recipientStatus(actor, contact, policy),
    ...recipientRefusal(actor, contact, policy),
  };
}

describe('searchContacts', () => {
  it('returns every contact for a blank or whitespace-only query in both locales', () => {
    expect(searchContacts(directoryContacts, '', 'en')).toEqual(directoryContacts);
    expect(searchContacts(directoryContacts, '   ', 'ar')).toEqual(directoryContacts);
  });

  it('matches by subject, Latin name, role and unit', () => {
    expect(searchContacts(directoryContacts, 'synthetic-staff-omar', 'en')).toEqual([
      latinOnlyContact,
    ]);
    expect(searchContacts(directoryContacts, 'Synthetic Nora', 'en')).toEqual([
      sameFacilityContact,
    ]);
    expect(searchContacts(directoryContacts, 'nurse', 'en')).toEqual([sameFacilityContact]);
    expect(searchContacts(directoryContacts, 'synthetic-front-desk', 'en')).toEqual([
      latinOnlyContact,
    ]);
  });

  it('matches a local-script name only in the Arabic locale', () => {
    expect(searchContacts(directoryContacts, 'ليلى', 'ar')).toEqual([otherFacilityContact]);
    expect(searchContacts(directoryContacts, 'ليلى', 'en')).toEqual([]);
  });

  it('searches a Latin-only contact by its Latin name in Arabic', () => {
    expect(searchContacts(directoryContacts, 'Synthetic Omar', 'ar')).toEqual([latinOnlyContact]);
    expect(searchContacts(directoryContacts, 'synthetic-staff-omar', 'ar')).toEqual([
      latinOnlyContact,
    ]);
  });

  it('returns no contacts for a query that matches nothing', () => {
    expect(searchContacts(directoryContacts, 'zzzz-no-match', 'en')).toEqual([]);
    expect(searchContacts(directoryContacts, 'zzzz-no-match', 'ar')).toEqual([]);
  });
});

describe('isOtherFacility', () => {
  it('is true only for a different known facility in the same organization', () => {
    expect(isOtherFacility(mainActor, otherFacilityContact)).toBe(true);
    expect(isOtherFacility(mainActor, sameFacilityContact)).toBe(false);
    expect(isOtherFacility(mainActor, crossOrgContact)).toBe(false);
  });

  it('is false when the actor or destination facility is unknown', () => {
    expect(isOtherFacility({ organizationId: mainOrganizationId }, otherFacilityContact)).toBe(
      false,
    );
    expect(isOtherFacility(mainActor, { ...sameFacilityContact, facilityCode: '' })).toBe(false);
  });
});

describe('recipientStatus and recipientRefusal', () => {
  it('blocks an actor with no organization (first fail-closed operand)', () => {
    const decision = decisionFor({ facilityCode: 'MAIN' }, sameFacilityContact, policyOpen);
    expect(decision.status).toBe('blocked');
    expect(decision.code).toBe(CROSS_ORGANIZATION_MESSAGING_DISABLED);
    expect(decision.otherFacility).toBe(false);
    expect(recipientStatus({ facilityCode: 'MAIN' }, sameFacilityContact, policyOpen)).toBe(
      'blocked',
    );
  });

  it('blocks a whitespace organization as unknown', () => {
    expect(
      decisionFor({ organizationId: '   ', facilityCode: 'MAIN' }, sameFacilityContact, policyOpen)
        .code,
    ).toBe(CROSS_ORGANIZATION_MESSAGING_DISABLED);
  });

  it('blocks a destination with an empty organization (second fail-closed operand)', () => {
    const decision = decisionFor(mainActor, { ...sameFacilityContact, organizationId: '' }, policyOpen);
    expect(decision.code).toBe(CROSS_ORGANIZATION_MESSAGING_DISABLED);
    expect(decision.otherFacility).toBe(false);
  });

  it('blocks an actor with no facility code', () => {
    expect(
      decisionFor({ organizationId: mainOrganizationId }, sameFacilityContact, policyOpen).code,
    ).toBe(CROSS_ORGANIZATION_MESSAGING_DISABLED);
  });

  it('blocks a destination with an empty facility code', () => {
    expect(
      decisionFor(mainActor, { ...sameFacilityContact, facilityCode: '' }, policyOpen).code,
    ).toBe(CROSS_ORGANIZATION_MESSAGING_DISABLED);
  });

  it('blocks a different organization even when facility codes match', () => {
    const decision = decisionFor(mainActor, crossOrgContact, policyOpen);
    expect(decision.status).toBe('blocked');
    expect(decision.code).toBe(CROSS_ORGANIZATION_MESSAGING_DISABLED);
    expect(decision.otherFacility).toBe(false);
  });

  it('accepts a same-facility contact regardless of the policy flag', () => {
    for (const policy of [policyOpen, policyClosed]) {
      const decision = decisionFor(mainActor, sameFacilityContact, policy);
      expect(decision.status).toBe('ok');
      expect(decision.code).toBeUndefined();
      expect(decision.otherFacility).toBe(false);
    }
  });

  it('marks an approved cross-facility contact as other-facility and selectable', () => {
    const decision = decisionFor(mainActor, otherFacilityContact, policyOpen);
    expect(decision.status).toBe('other-facility');
    expect(decision.code).toBeUndefined();
    expect(decision.otherFacility).toBe(true);
  });

  it('refuses an unapproved cross-facility contact but still marks its facility crossing', () => {
    const decision = decisionFor(mainActor, otherFacilityContact, policyClosed);
    expect(decision.status).toBe('blocked');
    expect(decision.code).toBe(CROSS_FACILITY_MESSAGING_DISABLED);
    expect(decision.otherFacility).toBe(true);
  });

  it('keeps the refusal codes aligned with the authoritative API codes', () => {
    expect(CROSS_ORGANIZATION_MESSAGING_DISABLED).toBe('CROSS_ORGANIZATION_MESSAGING_DISABLED');
    expect(CROSS_FACILITY_MESSAGING_DISABLED).toBe('CROSS_FACILITY_MESSAGING_DISABLED');
  });
});
