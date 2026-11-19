import { createMessageFactsClient } from './client';
import type { DeliveryFacts, InstructionView, MessageFactsFixture } from './types';

const ORG = '11111111-1111-1111-1111-111111111111';
const OTHER_ORG = '22222222-2222-2222-2222-222222222222';
const INSTRUCTION = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
const FOREIGN = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';
const PATIENT = 'cccccccc-cccc-cccc-cccc-cccccccccccc';
const UNIT = 'dddddddd-dddd-dddd-dddd-dddddddddddd';
const OTHER_UNIT = 'eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee';
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

function fixture(overrides: Partial<MessageFactsFixture> = {}): MessageFactsFixture {
  return {
    organizationId: ORG,
    viewer: PATIENT,
    instructions: [view()],
    facts: { [INSTRUCTION]: EMPTY },
    now: NOW,
    readToken: () => 'synthetic-token',
    newId: () => 'new-id',
    ...overrides,
  };
}

describe('MSG-05 auth-gated patient client', () => {
  it('reads the credential before every protected command', async () => {
    let reads = 0;
    const client = createMessageFactsClient(fixture({
      readToken: () => {
        reads += 1;
        return 'synthetic-token';
      },
    }));
    await client.loadInstructionAfterAuth(INSTRUCTION);
    expect(reads).toBe(1);
    await client.markFirstView(INSTRUCTION, 2);
    expect(reads).toBe(2);
    await client.acknowledge(INSTRUCTION, 2);
    expect(reads).toBe(3);
  });

  it('rejects every protected command when no credential is present', async () => {
    const client = createMessageFactsClient(fixture({ readToken: () => null }));
    await expect(client.loadInstructionAfterAuth(INSTRUCTION)).rejects.toThrow('NOT_AUTHENTICATED');
    await expect(client.markFirstView(INSTRUCTION, 2)).rejects.toThrow('NOT_AUTHENTICATED');
    await expect(client.acknowledge(INSTRUCTION, 2)).rejects.toThrow('NOT_AUTHENTICATED');
  });

  it('never caches clinical content between authenticated loads', async () => {
    const client = createMessageFactsClient(fixture());
    const first = await client.loadInstructionAfterAuth(INSTRUCTION);
    const second = await client.loadInstructionAfterAuth(INSTRUCTION);
    expect(second).toEqual(first);
  });

  it('conceals unknown and cross-tenant instructions', async () => {
    const client = createMessageFactsClient(fixture());
    await expect(client.loadInstructionAfterAuth(FOREIGN)).rejects.toThrow('INSTRUCTION_NOT_FOUND');
    const crossTenant = createMessageFactsClient(fixture({
      instructions: [view({ organizationId: OTHER_ORG })],
    }));
    await expect(crossTenant.loadInstructionAfterAuth(INSTRUCTION)).rejects.toThrow('INSTRUCTION_NOT_FOUND');
    const otherChart = createMessageFactsClient(fixture({
      instructions: [view({ patientId: FOREIGN })],
    }));
    await expect(otherChart.loadInstructionAfterAuth(INSTRUCTION)).rejects.toThrow('INSTRUCTION_NOT_FOUND');
  });

  it('records first view once and binds the acknowledgment to the revision', async () => {
    const client = createMessageFactsClient(fixture());
    const first = await client.markFirstView(INSTRUCTION, 2);
    const again = await client.markFirstView(INSTRUCTION, 2);
    expect(again).toEqual(first);
    const acknowledged = await client.acknowledge(INSTRUCTION, 2);
    expect(acknowledged.ok).toBe(true);
    const repeated = await client.acknowledge(INSTRUCTION, 2);
    expect(repeated).toEqual({ ok: false, reason: 'ALREADY_ACKNOWLEDGED' });
  });

  it('starts from empty facts when an instruction has no recorded projection yet', async () => {
    const client = createMessageFactsClient(fixture({ facts: {} }));
    const first = await client.markFirstView(INSTRUCTION, 2);
    expect(first.revision).toBe(2);
    const acknowledged = await client.acknowledge(INSTRUCTION, 2);
    expect(acknowledged.ok).toBe(true);
  });

  it('surfaces a stable denial when the first view cannot be recorded', async () => {
    const existing = {
      ...EMPTY,
      firstView: {
        kind: 'first_view' as const,
        instructionId: INSTRUCTION,
        revision: 2,
        viewer: PATIENT,
        occurredAt: NOW,
      },
    };
    const client = createMessageFactsClient(fixture({ facts: { [INSTRUCTION]: existing } }));
    await expect(client.markFirstView(INSTRUCTION, 1)).rejects.toThrow('FIRST_VIEW_MISMATCH');
  });

  it('projects overdue instructions and their owning unit', () => {
    const client = createMessageFactsClient(fixture());
    expect(client.overdueInstructions(NOW).map((item) => item.id)).toEqual([INSTRUCTION]);
    expect(client.overdueInstructions('2026-11-19T10:00:00Z')).toEqual([]);
    expect(client.owningUnitFor(INSTRUCTION)).toBe(UNIT);
    expect(client.owningUnitFor(INSTRUCTION)).not.toBe(OTHER_UNIT);
    expect(() => client.owningUnitFor(FOREIGN)).toThrow('INSTRUCTION_NOT_FOUND');
  });
});
