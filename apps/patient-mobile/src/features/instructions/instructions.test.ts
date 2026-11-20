import {
  actionDueLabel,
  canOpenAttachment,
  currentInstructions,
  displayInstruction,
  genericNotification,
  historyForThread,
  isWithdrawn,
  unitDisplayName,
} from './instructions';
import type { PatientInstruction } from './types';

/**
 * Synthetic MSG-04 acceptance for the patient inbox. No real patient data,
 * no network and no persistent storage are used.
 */
function instruction(overrides: Partial<PatientInstruction> = {}): PatientInstruction {
  return {
    threadId: 'thread-1',
    revision: 1,
    kind: 'collection',
    title: 'Morning collection',
    text: 'Please fast before 9am.',
    language: 'en',
    unitId: 'unit-lab',
    unitName: 'North Laboratory',
    organizationName: 'Synthetic Clinic',
    issuedAt: '2026-11-19T10:00:00Z',
    actionDueAt: null,
    contextType: 'order',
    attachmentIds: [],
    state: 'sent',
    supersededBy: null,
    ...overrides,
  };
}

describe('patient instruction projections', () => {
  it('selects one current revision per thread and hides drafts and superseded revisions', () => {
    const draft = instruction({ threadId: 'thread-draft', state: 'draft' });
    const superseded = instruction({ threadId: 'thread-superseded', state: 'superseded' });
    const older = instruction({ threadId: 'thread-1', revision: 1 });
    const newer = instruction({ threadId: 'thread-1', revision: 2, title: 'Updated' });
    const withdrawn = instruction({ threadId: 'thread-2', state: 'withdrawn', revision: 1 });

    const current = currentInstructions([draft, superseded, older, newer, older, withdrawn]);
    expect(current).toHaveLength(2);
    expect(current.map((item) => item.threadId).sort()).toEqual(['thread-1', 'thread-2']);
    expect(current.find((item) => item.threadId === 'thread-1')?.title).toBe('Updated');
    expect(current.find((item) => item.threadId === 'thread-2')?.state).toBe('withdrawn');
    expect(currentInstructions([])).toEqual([]);
  });

  it('reports withdrawal without hiding history', () => {
    expect(isWithdrawn(instruction({ state: 'withdrawn' }))).toBe(true);
    expect(isWithdrawn(instruction())).toBe(false);
  });

  it('projects Arabic instructions as RTL and English as LTR', () => {
    const arabic = displayInstruction(instruction({ language: 'ar-EG', title: 'جمع', text: 'صيام' }));
    expect(arabic).toEqual({ title: 'جمع', text: 'صيام', direction: 'rtl' });
    expect(displayInstruction(instruction({ language: 'ar' })).direction).toBe('rtl');
    const english = displayInstruction(instruction());
    expect(english.direction).toBe('ltr');
  });

  it('keeps Latin-script and non-Arabic tags LTR despite an ar prefix', () => {
    expect(displayInstruction(instruction({ language: 'ar-Latn' })).direction).toBe('ltr');
    expect(displayInstruction(instruction({ language: 'arn' })).direction).toBe('ltr');
  });

  it('formats the optional action due time in the facility time zone', () => {
    expect(actionDueLabel(instruction(), 'UTC')).toBeNull();
    const label = actionDueLabel(
      instruction({ actionDueAt: '2026-11-20T09:00:00Z' }),
      'UTC',
    );
    expect(label).not.toBeNull();
    expect(label).toContain('20 Nov 2026');
  });

  it('uses generic notification text that reveals no clinical detail', () => {
    const notification = genericNotification(instruction());
    expect(notification).toBe('You have a new collection instruction from North Laboratory.');
    expect(notification).not.toContain('fast');
    expect(genericNotification(instruction({ kind: 'pharmacy_pickup' }))).toContain('pharmacy pickup');
    expect(genericNotification(instruction({ kind: 'imaging_preparation' }))).toContain(
      'imaging preparation',
    );
    expect(genericNotification(instruction({ kind: 'therapy_home' }))).toContain('therapy home');
    expect(genericNotification(instruction({ kind: 'visit_follow_up' }))).toContain('visit follow-up');
    expect(genericNotification(instruction({ kind: 'general' }))).toContain('general');
  });

  it('orders thread history by revision and isolates other threads', () => {
    const first = instruction({ revision: 1 });
    const second = instruction({ revision: 2 });
    const other = instruction({ revision: 3, threadId: 'thread-2' });
    expect(historyForThread([second, other, first], 'thread-1')).toEqual([first, second]);
    expect(historyForThread([other], 'missing')).toEqual([]);
  });

  it('opens attachments only from a current sent revision that binds them', () => {
    expect(canOpenAttachment(instruction({ attachmentIds: ['doc-a'] }), 'doc-a')).toBe(true);
    expect(canOpenAttachment(instruction({ attachmentIds: ['doc-a'] }), 'doc-b')).toBe(false);
    expect(
      canOpenAttachment(instruction({ state: 'withdrawn', attachmentIds: ['doc-a'] }), 'doc-a'),
    ).toBe(false);
    expect(
      canOpenAttachment(
        instruction({ state: 'sent', supersededBy: 2, attachmentIds: ['doc-a'] }),
        'doc-a',
      ),
    ).toBe(false);
  });

  it('composes an organization and unit label without clinical content', () => {
    expect(unitDisplayName(instruction())).toBe('Synthetic Clinic - North Laboratory');
  });
});
