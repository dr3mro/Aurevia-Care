import { describe, expect, it } from 'vitest';
import type {
  Addendum,
  ImagingOrder,
  Interpretation,
  StudyLink,
} from './domain';
import {
  appendAddendum,
  buildReferrerView,
  canInterpret,
  canLinkStudy,
  canSchedule,
  isModality,
  linkStudyToOrder,
  scheduleOrder,
  signInterpretation,
  signInterpretationForOrder,
  verifyStudyLink,
  withAddendum,
} from './domain';

function makeOrder(overrides: Partial<ImagingOrder> = {}): ImagingOrder {
  return {
    id: 'order-1',
    version: 3,
    patientId: 'patient-a1',
    patientName: { latinName: 'Layla Haddad', localName: 'ليلى حداد' },
    urgency: 'urgent',
    clinicalQuestion: 'Synthetic: evaluate chest pain',
    state: 'received',
    accessionNo: null,
    modality: null,
    scheduledAt: null,
    studyInstanceUid: null,
    interpretation: null,
    ...overrides,
  };
}

function makeInterpretation(addenda: readonly Addendum[] = []): Interpretation {
  return {
    technique: 'Synthetic CT chest with contrast',
    comparison: 'None available',
    findings: 'No synthetic focal lesion.',
    impression: 'No acute cardiopulmonary abnormality.',
    followUp: 'Clinical correlation advised.',
    signedBy: 'synthetic.radiologist',
    signedAt: '2026-03-08T09:00:00Z',
    addenda,
  };
}

const addendum: Addendum = {
  id: 'addendum-1',
  text: 'Synthetic addendum: compared with prior study.',
  author: 'synthetic.radiologist',
  addedAt: '2026-03-08T10:00:00Z',
};

describe('verifyStudyLink', () => {
  const study: StudyLink = {
    studyInstanceUid: '1.2.826.0.1.3680043.2.1125.synthetic',
    patientId: 'patient-a1',
    accessionNo: 'ACC-2026-0001',
  };
  const scheduled = makeOrder({
    state: 'scheduled',
    accessionNo: 'ACC-2026-0001',
    scheduledAt: '2026-03-08T08:00:00Z',
    modality: 'CT',
  });

  it('accepts a study whose patient and accession both match', () => {
    expect(verifyStudyLink(scheduled, study)).toEqual({ ok: true });
  });

  it('refuses a wrong-patient link and does not claim an accession mismatch', () => {
    const check = verifyStudyLink(scheduled, { ...study, patientId: 'patient-b1' });
    expect(check).toEqual({ ok: false, patientMismatch: true, accessionMismatch: false });
  });

  it('refuses a wrong-accession link and does not claim a patient mismatch', () => {
    const check = verifyStudyLink(scheduled, { ...study, accessionNo: 'ACC-9999' });
    expect(check).toEqual({ ok: false, patientMismatch: false, accessionMismatch: true });
  });

  it('reports both mismatches when neither value matches', () => {
    const check = verifyStudyLink(scheduled, {
      ...study,
      patientId: 'patient-b1',
      accessionNo: 'ACC-9999',
    });
    expect(check).toEqual({ ok: false, patientMismatch: true, accessionMismatch: true });
  });
});

describe('workflow guards', () => {
  it('allows scheduling only in the received state', () => {
    expect(canSchedule(makeOrder())).toBe(true);
    expect(canSchedule(makeOrder({ state: 'scheduled' }))).toBe(false);
  });

  it('allows study linking only when scheduled with an accession and a time', () => {
    const base = {
      state: 'scheduled' as const,
      accessionNo: 'ACC-1',
      scheduledAt: '2026-03-08T08:00:00Z',
    };
    expect(canLinkStudy(makeOrder(base))).toBe(true);
    expect(canLinkStudy(makeOrder({ ...base, state: 'received' }))).toBe(false);
    expect(canLinkStudy(makeOrder({ ...base, accessionNo: null }))).toBe(false);
    expect(canLinkStudy(makeOrder({ ...base, scheduledAt: null }))).toBe(false);
  });

  it('allows interpretation only when a study is linked', () => {
    const base = { state: 'study-linked' as const, studyInstanceUid: '1.2.3' };
    expect(canInterpret(makeOrder(base))).toBe(true);
    expect(canInterpret(makeOrder({ ...base, state: 'scheduled' }))).toBe(false);
    expect(canInterpret(makeOrder({ ...base, studyInstanceUid: null }))).toBe(false);
  });
});

describe('isModality', () => {
  it('accepts every supported modality', () => {
    for (const modality of ['CT', 'MRI', 'XR', 'US', 'PET', 'NM'] as const) {
      expect(isModality(modality)).toBe(true);
    }
  });

  it('rejects free text and an empty value', () => {
    expect(isModality('')).toBe(false);
    expect(isModality('ULTRASOUND')).toBe(false);
  });
});

describe('immutable state transitions', () => {
  it('records accession, modality and time when scheduling', () => {
    const scheduled = scheduleOrder(makeOrder(), {
      accessionNo: 'ACC-2026-0001',
      modality: 'MRI',
      scheduledAt: '2026-03-10T08:00:00Z',
    });
    expect(scheduled.state).toBe('scheduled');
    expect(scheduled.accessionNo).toBe('ACC-2026-0001');
    expect(scheduled.modality).toBe('MRI');
    expect(scheduled.scheduledAt).toBe('2026-03-10T08:00:00Z');
    expect(scheduled.version).toBe(3);
  });

  it('records the study instance uid when linking', () => {
    const linked = linkStudyToOrder(makeOrder({ state: 'scheduled' }), {
      studyInstanceUid: '1.2.3.4',
      patientId: 'patient-a1',
      accessionNo: 'ACC-2026-0001',
    });
    expect(linked.state).toBe('study-linked');
    expect(linked.studyInstanceUid).toBe('1.2.3.4');
  });

  it('signs an interpretation with an empty addenda list', () => {
    const signed = signInterpretation(
      {
        technique: 'Synthetic XR',
        comparison: 'Prior XR',
        findings: 'Clear',
        impression: 'Normal',
        followUp: 'None',
      },
      'synthetic.radiologist',
      '2026-03-08T09:00:00Z',
    );
    expect(signed.signedBy).toBe('synthetic.radiologist');
    expect(signed.addenda).toEqual([]);
  });

  it('attaches a signed interpretation to the order', () => {
    const signed = signInterpretationForOrder(
      makeOrder({ state: 'study-linked', studyInstanceUid: '1.2.3' }),
      makeInterpretation(),
    );
    expect(signed.state).toBe('interpreted');
    expect(signed.interpretation?.signedBy).toBe('synthetic.radiologist');
  });

  it('appends an addendum without mutating the signed interpretation', () => {
    const original = makeInterpretation();
    const updated = withAddendum(original, addendum);
    expect(updated.addenda).toEqual([addendum]);
    expect(original.addenda).toEqual([]);
    expect(updated.technique).toBe(original.technique);
  });

  it('moves the order to addended when an addendum is attached', () => {
    const signedOrder = signInterpretationForOrder(
      makeOrder({ state: 'study-linked', studyInstanceUid: '1.2.3' }),
      makeInterpretation(),
    );
    const addended = appendAddendum(signedOrder, addendum);
    expect(addended.state).toBe('addended');
    expect(addended.interpretation?.addenda).toEqual([addendum]);
    expect(signedOrder.interpretation?.addenda).toEqual([]);
  });
});

describe('buildReferrerView', () => {
  it('returns no view before an interpretation is signed', () => {
    expect(buildReferrerView(makeOrder())).toBeNull();
  });

  it('carries the signed impression and every addendum to the referrer', () => {
    const order = signInterpretationForOrder(
      makeOrder({ state: 'study-linked', studyInstanceUid: '1.2.3' }),
      makeInterpretation(),
    );
    const addended = appendAddendum(order, addendum);
    const view = buildReferrerView(addended);
    expect(view).not.toBeNull();
    expect(view?.impression).toBe('No acute cardiopulmonary abnormality.');
    expect(view?.addenda).toEqual([addendum]);
    expect(view?.patientId).toBe('patient-a1');
  });
});
