import { fireEvent, render } from '@testing-library/react-native';

import {
  InstructionAcknowledgmentScreen,
  MESSAGE_FACT_LABELS,
  statusLabel,
} from './InstructionAcknowledgmentScreen';
import type { DeliveryFacts, InstructionView, OverdueStatus } from './types';

const ORG = '11111111-1111-1111-1111-111111111111';
const INSTRUCTION = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
const NEXT = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';
const PATIENT = 'cccccccc-cccc-cccc-cccc-cccccccccccc';
const UNIT = 'dddddddd-dddd-dddd-dddd-dddddddddddd';
const NOW = '2026-11-19T12:00:00Z';
const DUE = '2026-11-19T11:00:00Z';
const EMPTY: DeliveryFacts = {
  publication: null,
  dispatch: null,
  firstView: null,
  acknowledgment: null,
};

function view(overrides: Partial<InstructionView> = {}): InstructionView {
  return {
    id: INSTRUCTION,
    organizationId: ORG,
    patientId: PATIENT,
    unitId: UNIT,
    revision: 2,
    latestRevision: 2,
    withdrawn: false,
    title: 'Collection instructions',
    request: { revision: 2, dueAt: DUE },
    ...overrides,
  };
}

function renderScreen(
  instructions: readonly InstructionView[],
  onFirstView: (id: string) => void,
  onAcknowledge: (id: string, revision: number) => void,
) {
  return render(
    <InstructionAcknowledgmentScreen
      instructions={instructions}
      facts={{ [INSTRUCTION]: EMPTY }}
      isRTL={false}
      now={NOW}
      onFirstView={onFirstView}
      onAcknowledge={onAcknowledge}
    />,
  );
}

describe('MSG-05 patient acknowledgment screen', () => {
  it('reports a first view exactly once for each revealed instruction', async () => {
    let views = 0;
    const tree = await renderScreen([view()], () => { views += 1; }, () => undefined);
    expect(views).toBe(1);
    await tree.rerender(
      <InstructionAcknowledgmentScreen
        instructions={[view()]}
        facts={{ [INSTRUCTION]: EMPTY }}
        isRTL
        now={NOW}
        onFirstView={() => { views += 1; }}
        onAcknowledge={() => undefined}
      />,
    );
    expect(views).toBe(1);
    await tree.rerender(
      <InstructionAcknowledgmentScreen
        instructions={[view(), view({ id: NEXT })]}
        facts={{ [INSTRUCTION]: EMPTY }}
        isRTL
        now={NOW}
        onFirstView={() => { views += 1; }}
        onAcknowledge={() => undefined}
      />,
    );
    expect(views).toBe(2);
  });

  it('renders accessible status and an explicit revision-bound acknowledge action', async () => {
    let acknowledged: [string, number] | null = null;
    const tree = await renderScreen([view()], () => undefined, (id, revision) => {
      acknowledged = [id, revision];
    });
    expect(tree.getByLabelText(MESSAGE_FACT_LABELS.inbox)).toBeTruthy();
    expect(tree.getByText('Collection instructions')).toBeTruthy();
    expect(tree.getByText('Revision 2')).toBeTruthy();
    expect(tree.getByText(MESSAGE_FACT_LABELS.overdue)).toBeTruthy();
    fireEvent.press(tree.getByLabelText(MESSAGE_FACT_LABELS.acknowledge(2)));
    expect(acknowledged).toEqual([INSTRUCTION, 2]);
  });

  it('disables acknowledgment for acknowledged, withdrawn, not-requested and stale revisions', async () => {
    const acknowledged = {
      ...EMPTY,
      acknowledgment: {
        kind: 'acknowledgment' as const,
        instructionId: INSTRUCTION,
        revision: 2,
        actor: PATIENT,
        purpose: 'receipt' as const,
        acknowledgedAt: NOW,
      },
    };
    const cases: readonly [InstructionView, DeliveryFacts, string][] = [
      [view(), acknowledged, MESSAGE_FACT_LABELS.acknowledged],
      [view({ request: null }), EMPTY, MESSAGE_FACT_LABELS.notRequested],
      [view({ withdrawn: true }), EMPTY, MESSAGE_FACT_LABELS.withdrawn],
      [view({ latestRevision: 3 }), EMPTY, MESSAGE_FACT_LABELS.overdue],
    ];
    for (const [instruction, facts, label] of cases) {
      const tree = await render(
        <InstructionAcknowledgmentScreen
          instructions={[instruction]}
          facts={{ [INSTRUCTION]: facts }}
          isRTL={false}
          now={NOW}
          onFirstView={() => undefined}
          onAcknowledge={() => undefined}
        />,
      );
      expect(tree.getByText(label)).toBeTruthy();
      expect(tree.queryByLabelText(MESSAGE_FACT_LABELS.acknowledge(2))).toBeNull();
    }
  });

  it('renders a pending status before the deadline', async () => {
    const tree = await render(
      <InstructionAcknowledgmentScreen
        instructions={[view({ request: { revision: 2, dueAt: '2026-11-20T00:00:00Z' } })]}
        facts={{ [INSTRUCTION]: EMPTY }}
        isRTL={false}
        now={NOW}
        onFirstView={() => undefined}
        onAcknowledge={() => undefined}
      />,
    );
    expect(tree.getByText(MESSAGE_FACT_LABELS.pending)).toBeTruthy();
  });

  it('renders an empty inbox with no acknowledge action', async () => {
    const tree = await render(
      <InstructionAcknowledgmentScreen
        instructions={[]}
        facts={{}}
        isRTL={false}
        now={NOW}
        onFirstView={() => undefined}
        onAcknowledge={() => undefined}
      />,
    );
    expect(tree.getByLabelText(MESSAGE_FACT_LABELS.inbox)).toBeTruthy();
    expect(tree.queryByLabelText(MESSAGE_FACT_LABELS.acknowledge(2))).toBeNull();
  });

  it('maps every status to a non-clinical label', async () => {
    const statuses: readonly OverdueStatus[] = [
      'acknowledged', 'overdue', 'pending', 'withdrawn', 'not_requested',
    ];
    for (const status of statuses) {
      expect(statusLabel(status).length).toBeGreaterThan(0);
    }
    expect(statusLabel('unknown' as OverdueStatus)).toBe(MESSAGE_FACT_LABELS.notRequested);
  });
});
