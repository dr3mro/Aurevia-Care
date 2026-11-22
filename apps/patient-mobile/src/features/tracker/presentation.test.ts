import { isTrackerRtl, parseTrackerLocale, rowFlexDirection, stepLineFor } from './presentation';
import type { TrackerItem } from './types';

const labItem: TrackerItem = {
  itemId: 'lab-1',
  kind: 'lab_order',
  steps: [
    { key: 'released', releasedToPatient: true },
    { key: 'collected_or_scheduled', releasedToPatient: true },
    // A fact that exists in the workflow but was never released to the patient.
    { key: 'accepted', releasedToPatient: false },
    { key: 'placed', releasedToPatient: true },
  ],
};

describe('parseTrackerLocale', () => {
  it('accepts the two supported wire locales and rejects anything else', () => {
    expect(parseTrackerLocale('en')).toBe('en');
    expect(parseTrackerLocale('ar')).toBe('ar');
    expect(parseTrackerLocale('fr')).toBeNull();
    expect(parseTrackerLocale('')).toBeNull();
  });
});

describe('right-to-left direction (UX-01)', () => {
  it('maps Arabic to RTL and English to LTR', () => {
    expect(isTrackerRtl('ar')).toBe(true);
    expect(isTrackerRtl('en')).toBe(false);
    expect(rowFlexDirection('ar')).toBe('row-reverse');
    expect(rowFlexDirection('en')).toBe('row');
  });
});

describe('stepLineFor (PAT-02, XCH-03)', () => {
  it('shows only released steps in canonical order with plain English words', () => {
    const line = stepLineFor(labItem, 'en');
    expect(line.map((entry) => entry.key)).toEqual(['placed', 'collected_or_scheduled', 'released']);
    expect(line.map((entry) => entry.label)).toEqual(['Ordered', 'Sample taken', 'Released to you']);
  });

  it('keeps hidden facts out of the patient step line', () => {
    const line = stepLineFor(labItem, 'en');
    expect(line.some((entry) => entry.key === 'accepted')).toBe(false);
  });

  it('renders Arabic plain words in the same order', () => {
    const line = stepLineFor(labItem, 'ar');
    expect(line.map((entry) => entry.key)).toEqual(['placed', 'collected_or_scheduled', 'released']);
    expect(line.map((entry) => entry.label)).toEqual(['تم الطلب', 'تم أخذ العينة', 'أُتيحت لك']);
  });

  it('uses item-appropriate plain words for prescriptions and imaging', () => {
    const prescription: TrackerItem = {
      itemId: 'rx-1',
      kind: 'prescription',
      steps: [{ key: 'delivered', releasedToPatient: true }],
    };
    const imaging: TrackerItem = {
      itemId: 'img-1',
      kind: 'imaging_order',
      steps: [
        { key: 'collected_or_scheduled', releasedToPatient: true },
        { key: 'resulted', releasedToPatient: true },
      ],
    };
    expect(stepLineFor(prescription, 'en')[0]?.label).toBe('Sent to the pharmacy');
    expect(stepLineFor(imaging, 'en').map((entry) => entry.label)).toEqual([
      'Appointment scheduled',
      'Report ready for your doctor',
    ]);
  });

  it('returns an empty line when no step is released', () => {
    const hidden: TrackerItem = {
      itemId: 'hidden-1',
      kind: 'lab_order',
      steps: [{ key: 'resulted', releasedToPatient: false }],
    };
    expect(stepLineFor(hidden, 'en')).toEqual([]);
  });
});
