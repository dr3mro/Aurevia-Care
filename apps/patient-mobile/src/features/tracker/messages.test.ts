import { TRACKER_MESSAGE_KEYS, trackerText } from './messages';

describe('tracker bilingual messages (UX-01)', () => {
  it('keeps a non-empty translation for every key in both locales', () => {
    expect(TRACKER_MESSAGE_KEYS.length).toBeGreaterThan(0);
    for (const key of TRACKER_MESSAGE_KEYS) {
      expect(trackerText('en', key).length).toBeGreaterThan(0);
      expect(trackerText('ar', key).length).toBeGreaterThan(0);
    }
  });

  it('covers every item kind and canonical step in both bundles', () => {
    const kinds = ['prescription', 'lab_order', 'imaging_order'];
    const steps = [
      'placed',
      'delivered',
      'accepted',
      'collected_or_scheduled',
      'resulted',
      'released',
      'acknowledged',
    ];
    for (const kind of kinds) {
      for (const step of steps) {
        const key = `${kind}.${step}` as const;
        expect(TRACKER_MESSAGE_KEYS).toContain(key);
      }
    }
  });

  it('uses the plain words named in the acceptance criteria', () => {
    expect(trackerText('en', 'lab_order.placed')).toBe('Ordered');
    expect(trackerText('en', 'lab_order.delivered')).toBe('Sent to the lab');
    expect(trackerText('en', 'lab_order.collected_or_scheduled')).toBe('Sample taken');
    expect(trackerText('en', 'lab_order.resulted')).toBe('Result ready for your doctor');
    expect(trackerText('en', 'lab_order.released')).toBe('Released to you');
    expect(trackerText('ar', 'title')).toBe('أين هي؟');
    expect(trackerText('en', 'empty')).toBe('Nothing to track right now');
  });
});
