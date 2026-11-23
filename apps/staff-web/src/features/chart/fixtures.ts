import { defined } from '../../components/invariant';
import { syntheticOrganizations } from '../../fixtures/synthetic';
import type { PersonName } from '../../i18n/format';
import type { Chart, CodedEntry, DocumentAssociation, NoteRevision, Vital } from './domain';

/** The two synthetic tenants; their local identifiers intentionally collide. */
export const alphaOrganizationId = defined(syntheticOrganizations[0], 'alpha organization').id;
export const betaOrganizationId = defined(syntheticOrganizations[1], 'beta organization').id;

/** Both tenants deliberately own a chart with this same local identifier. */
export const syntheticChartLocalId = 'chart-001';

/** Synthetic clinical author used by coded entries and note signatures. */
export const syntheticSigner = 'synthetic.clinician';

/** Synthetic data-entry actor, recorded separately from the clinical author. */
export const syntheticDataEntryActor = 'synthetic.assistant';

/** Synthetic encounter context used by sign commands. */
export const syntheticEncounterLocalId = 'enc-001';

/** Clearly synthetic reason shown in the entered-in-error banner. */
export const syntheticEnteredInErrorReason = 'Synthetic duplicate entry in the source document.';

/** Clearly synthetic reason shown on a voided document association. */
export const syntheticVoidReason = 'Synthetic superseded document version.';

const alphaPatient: PersonName = {
  latinName: 'Synthetic Layla',
  localName: 'ليلى تجريبية',
};

const betaPatient: PersonName = {
  latinName: 'Synthetic Reem',
  localName: 'ريم تجريبية',
};

/** Synthetic coded entries, including explicit none and unknown states. */
const alphaProblems: readonly CodedEntry[] = [
  {
    organizationId: alphaOrganizationId,
    id: 'problem-001',
    code: 'P-42',
    originalText: 'Synthetic essential hypertension (source text)',
    display: 'Essential hypertension',
    displayLocal: 'ارتفاع ضغط الدم الأساسي',
    source: 'synthetic intake',
    effectiveOn: '2026-01-12',
    verification: 'verified',
    clinicalAuthor: syntheticSigner,
    enteredBy: syntheticDataEntryActor,
  },
  {
    organizationId: alphaOrganizationId,
    id: 'problem-002',
    code: 'P-77',
    originalText: 'Synthetic fatigue (source text)',
    display: 'Fatigue',
    source: 'synthetic patient report',
    effectiveOn: '2026-02-02',
    verification: 'unverified',
    clinicalAuthor: syntheticSigner,
    enteredBy: syntheticDataEntryActor,
  },
];

const alphaAllergies: readonly CodedEntry[] = [
  {
    organizationId: alphaOrganizationId,
    id: 'allergy-001',
    code: 'NONE',
    originalText: 'Synthetic no known drug allergies',
    display: 'No known drug allergies',
    displayLocal: 'لا توجد حساسيات دوائية معروفة',
    source: 'synthetic intake',
    effectiveOn: '2026-01-12',
    verification: 'none',
    clinicalAuthor: syntheticSigner,
    enteredBy: syntheticDataEntryActor,
  },
  {
    organizationId: alphaOrganizationId,
    id: 'allergy-002',
    code: 'UNK',
    originalText: 'Synthetic allergy history not taken',
    display: 'Allergy history not taken',
    source: 'synthetic referral',
    effectiveOn: '2026-02-02',
    verification: 'unknown',
    clinicalAuthor: syntheticSigner,
    enteredBy: syntheticDataEntryActor,
  },
];

const alphaMedications: readonly CodedEntry[] = [
  {
    organizationId: alphaOrganizationId,
    id: 'medication-001',
    code: 'M-11',
    originalText: 'Synthetic amlodipine 5 mg daily',
    display: 'Amlodipine 5 mg daily',
    source: 'synthetic reconciliation',
    effectiveOn: '2026-01-12',
    verification: 'verified',
    clinicalAuthor: syntheticSigner,
    enteredBy: syntheticDataEntryActor,
  },
];

/** Synthetic observations with their source units preserved verbatim. */
const alphaVitals: readonly Vital[] = [
  {
    organizationId: alphaOrganizationId,
    id: 'vital-001',
    kind: 'Systolic blood pressure',
    value: 120,
    unit: 'mmHg',
    measuredAt: '2026-03-09T08:15:00Z',
    timeZone: 'America/New_York',
  },
  {
    organizationId: alphaOrganizationId,
    id: 'vital-002',
    kind: 'Body temperature',
    value: 37,
    unit: '°C',
    measuredAt: '2026-03-09T08:16:00Z',
    timeZone: 'America/New_York',
  },
  {
    organizationId: alphaOrganizationId,
    id: 'vital-003',
    kind: 'Heart rate',
    value: 72,
    unit: 'beats/min',
    measuredAt: '2026-03-09T08:17:00Z',
    timeZone: 'America/New_York',
  },
];

const syntheticSoap = {
  subjective: 'Synthetic chief concern and history for review.',
  objective: 'Synthetic examination findings with vitals in the chart.',
  assessment: 'Synthetic assessment for synthetic hypertension.',
  plan: 'Synthetic follow-up plan for review.',
};

const alphaRevisions: readonly NoteRevision[] = [
  {
    organizationId: alphaOrganizationId,
    id: 'rev-001',
    chartLocalId: syntheticChartLocalId,
    patientLocalId: syntheticChartLocalId,
    encounterLocalId: syntheticEncounterLocalId,
    version: 1,
    kind: 'original',
    state: 'signed',
    content: { ...syntheticSoap },
    templateVersion: 'soap-v2',
    signer: syntheticSigner,
    signedAt: '2026-03-02T10:00:00Z',
    effectiveOn: '2026-03-02',
    checksum: 'synthetic:prior:rev-001',
    enteredInError: {
      actor: syntheticSigner,
      reason: syntheticEnteredInErrorReason,
      affectedRecipients: ['synthetic.referrer'],
      recordedAt: '2026-03-03T09:00:00Z',
    },
  },
  {
    organizationId: alphaOrganizationId,
    id: 'rev-002',
    chartLocalId: syntheticChartLocalId,
    patientLocalId: syntheticChartLocalId,
    encounterLocalId: syntheticEncounterLocalId,
    version: 2,
    kind: 'original',
    state: 'signed',
    content: { ...syntheticSoap },
    templateVersion: 'soap-v2',
    signer: syntheticSigner,
    signedAt: '2026-03-09T11:00:00Z',
    effectiveOn: '2026-03-09',
    checksum: 'synthetic:current:rev-002',
  },
];

const betaRevisions: readonly NoteRevision[] = [
  {
    organizationId: betaOrganizationId,
    id: 'rev-001',
    chartLocalId: syntheticChartLocalId,
    patientLocalId: syntheticChartLocalId,
    encounterLocalId: syntheticEncounterLocalId,
    version: 1,
    kind: 'original',
    state: 'signed',
    content: { ...syntheticSoap },
    templateVersion: 'soap-v2',
    signer: syntheticSigner,
    signedAt: '2026-03-04T10:00:00Z',
    effectiveOn: '2026-03-04',
    checksum: 'synthetic:prior:rev-001-beta',
  },
  {
    organizationId: betaOrganizationId,
    id: 'rev-002',
    chartLocalId: syntheticChartLocalId,
    patientLocalId: syntheticChartLocalId,
    encounterLocalId: syntheticEncounterLocalId,
    version: 2,
    kind: 'amendment',
    state: 'draft',
    content: { ...syntheticSoap },
    templateVersion: 'soap-v2',
    amends: 'rev-001',
  },
];

const alphaDocuments: readonly DocumentAssociation[] = [
  {
    organizationId: alphaOrganizationId,
    id: 'doc-001',
    chartLocalId: syntheticChartLocalId,
    kind: 'pdf',
    title: 'English PDF',
    version: 2,
    language: 'en',
    checksum: 'synthetic:pdf:en:v2',
    linkedTo: `chart:${syntheticChartLocalId}`,
    rotationDegrees: 0,
  },
  {
    organizationId: alphaOrganizationId,
    id: 'doc-002',
    chartLocalId: syntheticChartLocalId,
    kind: 'pdf',
    title: 'Arabic PDF',
    titleLocal: 'مستند عربي',
    version: 1,
    language: 'ar',
    checksum: 'synthetic:pdf:ar:v1',
    linkedTo: `chart:${syntheticChartLocalId}`,
    rotationDegrees: 0,
  },
  {
    organizationId: alphaOrganizationId,
    id: 'doc-003',
    chartLocalId: syntheticChartLocalId,
    kind: 'jpeg',
    title: 'Collection image',
    version: 1,
    language: 'en',
    checksum: 'synthetic:jpeg:v1',
    linkedTo: `order:synthetic-order`,
    rotationDegrees: 90,
  },
  {
    organizationId: alphaOrganizationId,
    id: 'doc-004',
    chartLocalId: syntheticChartLocalId,
    kind: 'png',
    title: 'Voided image',
    version: 1,
    language: 'en',
    checksum: 'synthetic:png:void',
    linkedTo: `result:synthetic-result`,
    rotationDegrees: 0,
    void: {
      actor: syntheticSigner,
      reason: syntheticVoidReason,
      voidedAt: '2026-03-05T09:00:00Z',
    },
  },
];

const betaDocuments: readonly DocumentAssociation[] = [
  {
    organizationId: betaOrganizationId,
    id: 'doc-001',
    chartLocalId: syntheticChartLocalId,
    kind: 'pdf',
    title: 'Beta PDF',
    version: 1,
    language: 'en',
    checksum: 'synthetic:pdf:beta:v1',
    linkedTo: `chart:${syntheticChartLocalId}`,
    rotationDegrees: 0,
  },
];

/** Synthetic alpha chart with a preserved entered-in-error revision in its history. */
export const syntheticAlphaChart: Chart = {
  organizationId: alphaOrganizationId,
  localId: syntheticChartLocalId,
  patientName: alphaPatient,
  problems: alphaProblems,
  allergies: alphaAllergies,
  medications: alphaMedications,
  vitals: alphaVitals,
  revisions: alphaRevisions,
  documents: alphaDocuments,
};

/** Synthetic beta chart reusing the same local ids with different content. */
export const syntheticBetaChart: Chart = {
  organizationId: betaOrganizationId,
  localId: syntheticChartLocalId,
  patientName: betaPatient,
  problems: [],
  allergies: [],
  medications: [],
  vitals: [],
  revisions: betaRevisions,
  documents: betaDocuments,
};

/** The complete synthetic pool; two tenants share every local identifier. */
export const syntheticCharts: readonly Chart[] = [syntheticAlphaChart, syntheticBetaChart];
