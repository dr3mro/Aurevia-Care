import type { PersonName } from '../../../i18n/format';
import type { Locale } from '../../../i18n/messages';

/** Refusal for a destination outside the actor's organization (unconditional). */
export const CROSS_ORGANIZATION_MESSAGING_DISABLED = 'CROSS_ORGANIZATION_MESSAGING_DISABLED';

/** Refusal for a same-organization crossing the tenant has not approved. */
export const CROSS_FACILITY_MESSAGING_DISABLED = 'CROSS_FACILITY_MESSAGING_DISABLED';

/**
 * The actor browsing the directory. Both identifiers are optional because a
 * request without a bound context must fail closed rather than assume a scope.
 */
export interface MessagingActor {
  organizationId?: string;
  facilityCode?: string;
}

/** The tenant decision that may permit a crossing between facilities. */
export interface MessagingPolicy {
  crossFacilityEnabled: boolean;
}

/** A synthetic, addressable staff member shown in the directory (MSG-02). */
export interface Contact {
  subject: string;
  displayName: PersonName;
  facilityCode: string;
  organizationId: string;
  roles: string[];
  units: string[];
}

/** Whether a recipient can be addressed and why a facility is shown. */
export type RecipientStatus = 'ok' | 'other-facility' | 'blocked';

/** The refusal code and marker for one recipient; no code means allowed. */
export interface RecipientRefusal {
  code: string | undefined;
  otherFacility: boolean;
}

/** Internal full decision used by the two public selectors. */
interface RecipientEvaluation extends RecipientRefusal {
  status: RecipientStatus;
}

/** Treat a missing or blank identifier as unknown so every rule fails closed. */
function isUnknown(value: string | undefined): boolean {
  return value === undefined || value.trim() === '';
}

/**
 * Filter synthetic contacts by a live query. The Latin form is always searched;
 * the local form is searched only in Arabic so a translated name cannot match a
 * Latin query by accident. Roles and units are searched in every locale.
 *
 * @param contacts directory entries already scoped to the active tenant
 * @param query raw text from the search field
 * @param locale active interface locale
 * @returns all contacts for a blank query, otherwise the matching contacts
 */
export function searchContacts(
  contacts: readonly Contact[],
  query: string,
  locale: Locale,
): Contact[] {
  const needle = query.trim().toLocaleLowerCase(locale);
  if (needle === '') {
    return [...contacts];
  }
  return contacts.filter((contact) => {
    const names =
      contact.displayName.localName === undefined
        ? [contact.displayName.latinName]
        : locale === 'ar'
          ? [contact.displayName.localName, contact.displayName.latinName]
          : [contact.displayName.latinName];
    const haystack = [contact.subject, ...names, ...contact.roles, ...contact.units];
    return haystack.some((value) => value.toLocaleLowerCase(locale).includes(needle));
  });
}

/**
 * Report a facility crossing independently of authorization. An unknown
 * facility is not a crossing: the decision reports it as a fail-closed refusal
 * instead of presenting an unverified location to the author.
 */
export function isOtherFacility(actor: MessagingActor, contact: Contact): boolean {
  if (isUnknown(actor.facilityCode) || isUnknown(contact.facilityCode)) {
    return false;
  }
  return actor.facilityCode !== contact.facilityCode;
}

/**
 * Single source of the fail-closed rules. The crossing comes from the shared
 * helper so the marker and the policy decision can never disagree.
 */
function evaluate(actor: MessagingActor, contact: Contact, policy: MessagingPolicy): RecipientEvaluation {
  const crossing = isOtherFacility(actor, contact);
  const organizationKnown = !isUnknown(actor.organizationId) && !isUnknown(contact.organizationId);
  const sameOrganization = organizationKnown && actor.organizationId === contact.organizationId;
  const facilityKnown = !isUnknown(actor.facilityCode) && !isUnknown(contact.facilityCode);

  if (!sameOrganization || !facilityKnown) {
    return {
      status: 'blocked',
      code: CROSS_ORGANIZATION_MESSAGING_DISABLED,
      otherFacility: crossing,
    };
  }
  if (crossing && !policy.crossFacilityEnabled) {
    return {
      status: 'blocked',
      code: CROSS_FACILITY_MESSAGING_DISABLED,
      otherFacility: true,
    };
  }
  return {
    status: crossing ? 'other-facility' : 'ok',
    code: undefined,
    otherFacility: crossing,
  };
}

/** Selectability of one recipient: ok, other-facility or blocked (MSG-02). */
export function recipientStatus(
  actor: MessagingActor,
  contact: Contact,
  policy: MessagingPolicy,
): RecipientStatus {
  return evaluate(actor, contact, policy).status;
}

/** Refusal code and facility marker used to render one recipient row. */
export function recipientRefusal(
  actor: MessagingActor,
  contact: Contact,
  policy: MessagingPolicy,
): RecipientRefusal {
  const { code, otherFacility } = evaluate(actor, contact, policy);
  return { code, otherFacility };
}
