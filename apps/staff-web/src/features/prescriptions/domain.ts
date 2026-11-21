/**
 * Prescription domain for the staff prescriber screen (RX-01, RX-02).
 *
 * The screen never decides clinical meaning: it checks a selected catalog drug
 * against the verified allergy list the API supplied, records the prescriber's
 * own attestation and channel choice, and displays the transmission events the
 * API returned. All fixtures in this feature are synthetic.
 */

/** The two approved routing channels for a V1 prescription (RX-02). */
export type TransmissionChannel = 'e-prescribing' | 'printable';

/** Distinct transmission and pharmacy events kept as separate facts (RX-02). */
export type PrescriptionEventKind =
  | 'submitted'
  | 'delivery_confirmed'
  | 'accepted'
  | 'rejected'
  | 'dispensed'
  | 'clarification'
  | 'renewal'
  | 'cancelled';

/** One append-only event on a prescription's status timeline. */
export interface PrescriptionEvent {
  kind: PrescriptionEventKind;
  /** UTC instant the event was recorded, rendered in the facility zone. */
  occurredAt: string;
  /** Stable submission identifier; the same value groups related events. */
  submissionId: string;
}

/** Whether the partner reported a definite transmission result (RX-02). */
export type TransmissionOutcome = 'confirmed' | 'ambiguous';

/**
 * One approved drug catalog candidate. Field names follow the drug catalog
 * projection consumed by `api.ts`; the market-approved flag and version come
 * from the catalog authority, never from the prescriber's input.
 */
export interface DrugCandidate {
  code: string;
  version: number;
  genericName: string;
  tradeName: string;
  formulation: string;
  strength: string;
  ingredients: readonly string[];
  marketApproved: boolean;
  /** Tags printed beside tagged alternatives for prescriber review (RX-01). */
  matchTags: readonly string[];
}

/** A verified allergy the prescriber's patient record carries (RX-01). */
export interface VerifiedAllergy {
  ingredient: string;
  reaction: string;
}

/** A matched ingredient or an ingredient with no verified allergy data. */
export interface AllergyFinding {
  kind: 'match' | 'unknown';
  ingredient: string;
}

/** Everything the prescriber enters before the command is sent (RX-01, RX-02). */
export interface DraftFields {
  patientId: string;
  prescriber: string;
  drugCode: string;
  drugVersion: number;
  strength: string;
  formulation: string;
  dose: string;
  route: string;
  frequency: string;
  quantity: string;
  refills: string;
  instructions: string;
  destination: string;
  channel: TransmissionChannel;
  attested: boolean;
  overrideReason: string;
}

/** Check one selected drug against the patient's verified allergies. */
export function findAllergyFindings(
  drug: DrugCandidate,
  allergies: readonly VerifiedAllergy[],
): AllergyFinding[] {
  return drug.ingredients.map((ingredient) => {
    const normalized = ingredient.trim().toLocaleLowerCase();
    const verified = allergies.some(
      (allergy) => allergy.ingredient.trim().toLocaleLowerCase() === normalized,
    );
    return { kind: verified ? 'match' : 'unknown', ingredient };
  });
}

/**
 * Match a catalog entry by generic name, trade name and formulation. An empty
 * query or formulation is a deliberate wildcard so the search can list the
 * whole approved catalog.
 */
export function matchesDrugQuery(
  drug: DrugCandidate,
  query: string,
  formulation: string,
): boolean {
  const needle = query.trim().toLocaleLowerCase();
  const form = formulation.trim().toLocaleLowerCase();
  const matchesName =
    needle === '' ||
    drug.genericName.toLocaleLowerCase().includes(needle) ||
    drug.tradeName.toLocaleLowerCase().includes(needle);
  if (!matchesName) {
    return false;
  }
  return form === '' || drug.formulation.toLocaleLowerCase().includes(form);
}

/** Return only market-approved catalog entries that match the search. */
export function selectDrugs(
  catalog: readonly DrugCandidate[],
  query: string,
  formulation: string,
): DrugCandidate[] {
  return catalog.filter(
    (drug) => drug.marketApproved && matchesDrugQuery(drug, query, formulation),
  );
}

/** Refills are a property of the electronic channel, not of a printed sheet. */
export function refillsSupported(channel: TransmissionChannel): boolean {
  return channel === 'e-prescribing';
}

/**
 * Decide whether the prescriber may send the draft. An override reason is
 * required exactly when the allergy check surfaced something to review.
 */
export function canSubmit(draft: DraftFields, findings: readonly AllergyFinding[]): boolean {
  const required = [
    draft.patientId,
    draft.prescriber,
    draft.drugCode,
    draft.strength,
    draft.formulation,
    draft.dose,
    draft.route,
    draft.frequency,
    draft.quantity,
    draft.destination,
  ].every((value) => value.trim().length > 0);
  if (!required || !draft.attested) {
    return false;
  }
  if (findings.length > 0) {
    return draft.overrideReason.trim().length > 0;
  }
  return true;
}
