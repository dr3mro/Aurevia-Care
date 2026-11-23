import { defined } from '../../components/invariant';
import { syntheticOrganizations } from '../../fixtures/synthetic';
import type {
  ConflictWire,
  ConsolidationWire,
  DependencyWire,
  DuplicatePairWire,
  PatientWire,
  ReviewDetailWire,
  ReviewWire,
} from './domain';

/** The two synthetic tenants; their local MRNs intentionally collide. */
export const alphaOrganizationId = defined(syntheticOrganizations[0], 'alpha organization').id;
export const betaOrganizationId = defined(syntheticOrganizations[1], 'beta organization').id;

/** Synthetic subjects; the opener and the second reviewer are different people. */
export const openerSubject = 'synthetic.reviewer.first';
export const secondReviewerSubject = 'synthetic.reviewer.second';

/** The survivor and its suspected duplicate; names intentionally differ in script. */
export const survivorPatient: PatientWire = {
  id: 'patient-survivor',
  version: 3,
  mrn: 'MRN-1001',
  mrnIssuer: 'SYNTHETIC',
  displayName: 'Layla Haddad',
  dob: '1990-05-12',
  dobUnknownReason: null,
  language: 'en',
};

export const duplicatePatient: PatientWire = {
  id: 'patient-duplicate',
  version: 7,
  mrn: 'MRN-1002',
  mrnIssuer: 'SYNTHETIC',
  displayName: 'ليلى حداد',
  dob: null,
  dobUnknownReason: 'not_disclosed',
  language: 'ar',
};

/** One worklist pair built from the synthetic patients. */
export const syntheticPair: DuplicatePairWire = {
  survivorId: survivorPatient.id,
  survivorName: survivorPatient.displayName,
  duplicateId: duplicatePatient.id,
  duplicateName: duplicatePatient.displayName,
};

/** A second worklist pair used to prove row selection and tenant scoping. */
export const syntheticPairTwo: DuplicatePairWire = {
  survivorId: 'patient-survivor-two',
  survivorName: 'Synthetic Second Survivor',
  duplicateId: 'patient-duplicate-two',
  duplicateName: 'Synthetic Second Duplicate',
};

/** An open ticket owned by the first reviewer. */
export const reviewOpen: ReviewWire = {
  id: 'review-1',
  survivorId: survivorPatient.id,
  duplicateId: duplicatePatient.id,
  openedBySubject: openerSubject,
  version: 1,
  status: 'OPEN',
};

/** Demographic conflicts shown side by side. */
export const syntheticConflicts: readonly ConflictWire[] = [
  { field: 'dob', survivorValue: '1990-05-12', duplicateValue: 'Not disclosed' },
  { field: 'language', survivorValue: 'en', duplicateValue: 'ar' },
];

/** An ambiguous, unresolved dependency: the server blocker condition. */
export const depsUnresolved: readonly DependencyWire[] = [
  { id: 'dep-1', kind: 'ORDER', reference: 'order-9', ambiguous: true, resolved: false },
];

/** An ambiguous dependency an authorized workflow has already resolved. */
export const depsResolved: readonly DependencyWire[] = [
  { id: 'dep-2', kind: 'PATIENT_LINK', reference: 'link-7', ambiguous: true, resolved: true },
];

/** An unambiguous dependency never blocks. */
export const depsUnambiguous: readonly DependencyWire[] = [
  { id: 'dep-3', kind: 'GRANT', reference: 'grant-4', ambiguous: false, resolved: false },
];

/** A full non-mutating review detail with no open ticket. */
export const detailWithoutReview: ReviewDetailWire = {
  review: null,
  survivor: survivorPatient,
  duplicate: duplicatePatient,
  conflicts: syntheticConflicts,
  dependencies: [],
  dryRun:
    'Dry run: move 1 chart visibility association to the surviving record. Nothing was changed.',
  chartVisibilityId: duplicatePatient.id,
};

/** A full review detail with an open ticket owned by the first reviewer. */
export const syntheticDetail: ReviewDetailWire = {
  ...detailWithoutReview,
  review: reviewOpen,
};

/** A detail whose duplicate has an unresolved ambiguous dependency. */
export const detailWithUnresolvedDependency: ReviewDetailWire = {
  ...syntheticDetail,
  dependencies: depsUnresolved,
};

/** A detail with no conflicts or dependencies and an unknown DOB code. */
export const detailMinimal: ReviewDetailWire = {
  ...detailWithoutReview,
  conflicts: [],
  dependencies: depsResolved,
  duplicate: { ...duplicatePatient, dobUnknownReason: 'unable_to_collect' },
};

/** The consolidation event returned by a successful approval. */
export const syntheticConsolidation: ConsolidationWire = {
  id: 'consolidation-1',
  reviewId: reviewOpen.id,
  survivorId: survivorPatient.id,
  duplicateId: duplicatePatient.id,
  version: 1,
  status: 'CONSOLIDATED',
  approverSubject: secondReviewerSubject,
  openedBySubject: openerSubject,
  reason: 'duplicate registration confirmed by site policy',
  correctionReason: null,
};
