import { defined } from '../../../components/invariant';
import { syntheticOrganizations } from '../../../fixtures/synthetic';
import type { CodedEntry } from '../../chart/domain';
import type { ActiveOrder, CatalogItem, SavedOrderDraft } from './domain';

/** Two synthetic tenants; the same local identifiers are reused across them. */
export const entryAlphaOrganizationId = defined(syntheticOrganizations[0], 'alpha organization').id;
export const entryBetaOrganizationId = defined(syntheticOrganizations[1], 'beta organization').id;

/** Opaque local identifiers used only by synthetic fixtures. */
export const syntheticPatientLocalId = 'patient-001';
export const syntheticEncounterLocalId = 'enc-001';
export const syntheticFacilityId = 'MAIN';

/** Synthetic orderable catalog for the chart, including retired and cross-tenant items. */
export const syntheticCatalog: readonly CatalogItem[] = [
  {
    organizationId: entryAlphaOrganizationId,
    id: 'lab-cbc',
    code: 'LAB-CBC',
    name: 'Complete blood count',
    nameLocal: 'تعداد دم كامل',
    category: 'lab',
    specimenOrModality: 'EDTA whole blood',
    preparationInstructions: 'No fasting required.',
    defaultPriority: 'routine',
    partnerId: 'synthetic-lab-partner',
    version: 2,
  },
  {
    organizationId: entryAlphaOrganizationId,
    id: 'lab-fast',
    code: 'LAB-GLU',
    name: 'Fasting glucose',
    category: 'lab',
    specimenOrModality: 'Plasma',
    preparationInstructions: 'Fast for eight hours before collection.',
    defaultPriority: 'routine',
    partnerId: 'synthetic-lab-partner',
    version: 1,
  },
  {
    organizationId: entryAlphaOrganizationId,
    id: 'rad-ct',
    code: 'RAD-CT-ABD',
    name: 'CT abdomen with contrast',
    nameLocal: 'أشعة مقطعية للبطن',
    category: 'imaging',
    specimenOrModality: 'CT',
    preparationInstructions: 'Nothing by mouth for six hours; check renal function.',
    defaultPriority: 'urgent',
    partnerId: 'synthetic-radiology-partner',
    version: 3,
  },
  {
    organizationId: entryAlphaOrganizationId,
    id: 'rad-xr',
    code: 'RAD-XR-CHEST',
    name: 'Chest X-ray',
    category: 'imaging',
    specimenOrModality: 'XR',
    preparationInstructions: 'No preparation required.',
    defaultPriority: 'routine',
    partnerId: 'synthetic-radiology-partner',
    version: 1,
  },
  {
    organizationId: entryAlphaOrganizationId,
    id: 'lab-retired',
    code: 'LAB-OLD',
    name: 'Retired test',
    category: 'lab',
    specimenOrModality: 'Serum',
    preparationInstructions: 'Retired preparation.',
    defaultPriority: 'routine',
    partnerId: 'synthetic-lab-partner',
    version: 1,
    retired: true,
  },
  {
    organizationId: entryBetaOrganizationId,
    id: 'lab-beta',
    code: 'LAB-BETA',
    name: 'Beta tenant test',
    category: 'lab',
    specimenOrModality: 'Serum',
    preparationInstructions: 'Synthetic beta preparation.',
    defaultPriority: 'routine',
    partnerId: 'synthetic-beta-partner',
    version: 1,
  },
];

/** Synthetic allergies shown as safety context beside the order entry form. */
export const syntheticAllergies: readonly CodedEntry[] = [
  {
    organizationId: entryAlphaOrganizationId,
    id: 'allergy-contrast',
    code: 'CONTRAST',
    originalText: 'Synthetic iodinated contrast allergy',
    display: 'Iodinated contrast',
    displayLocal: 'حساسية صبغة اليود',
    source: 'synthetic intake',
    effectiveOn: '2026-01-12',
    verification: 'verified',
    clinicalAuthor: 'synthetic.clinician',
    enteredBy: 'synthetic.assistant',
  },
];

/** Synthetic active orders, one matching the imaging item to demonstrate a duplicate. */
export const syntheticActiveOrders: readonly ActiveOrder[] = [
  {
    organizationId: entryAlphaOrganizationId,
    id: 'order-existing',
    catalogCode: 'RAD-CT-ABD',
    display: 'CT abdomen with contrast',
    status: 'placed',
  },
  {
    organizationId: entryBetaOrganizationId,
    id: 'order-beta',
    catalogCode: 'LAB-BETA',
    display: 'Beta tenant test',
    status: 'draft',
  },
];

/** A synthetic saved lab draft at version four, opened from the chart. */
export const syntheticSavedDraft: SavedOrderDraft = {
  organizationId: entryAlphaOrganizationId,
  id: 'draft-001',
  version: 4,
  content: {
    patientId: syntheticPatientLocalId,
    encounterId: syntheticEncounterLocalId,
    facilityId: syntheticFacilityId,
    items: [
      {
        itemId: 'lab-cbc',
        version: 2,
        code: 'LAB-CBC',
        name: 'Complete blood count',
        category: 'lab',
      },
    ],
    clinicalQuestion: '',
    priority: 'routine',
    preparationInstructions: 'No fasting required.',
    deliveryMode: 'both',
  },
};
