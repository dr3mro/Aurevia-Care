import { acknowledgeRevisionBound, EMPTY_FACTS, overdueInstructions, recordFirstViewOnce } from './facts';
import type {
  AckResult,
  DeliveryFacts,
  FirstViewFact,
  InstructionView,
  MessageFactsClient,
  MessageFactsFixture,
} from './types';

/**
 * Create a synthetic, auth-gated MSG-05 patient client.
 *
 * Every protected command reads the OS-backed credential immediately before
 * use and rejects when it is absent. The client holds only fact projections,
 * never a cached clinical document; the full instruction is re-read through the
 * authenticated load path on each call.
 *
 * @param fixture synthetic instructions, facts, clock and credential reader
 * @returns a typed MSG-05 client boundary
 */
export function createMessageFactsClient(fixture: MessageFactsFixture): MessageFactsClient {
  let facts: Readonly<Record<string, DeliveryFacts>> = fixture.facts;

  function requireAuth(): void {
    if (fixture.readToken() === null) {
      throw new Error('NOT_AUTHENTICATED');
    }
  }

  function findInstruction(instructionId: string): InstructionView {
    const view = fixture.instructions.find((candidate) => candidate.id === instructionId);
    if (view === undefined) {
      throw new Error('INSTRUCTION_NOT_FOUND');
    }
    if (view.organizationId !== fixture.organizationId || view.patientId !== fixture.viewer) {
      throw new Error('INSTRUCTION_NOT_FOUND');
    }
    return view;
  }

  function factsFor(instructionId: string): DeliveryFacts {
    return facts[instructionId] ?? EMPTY_FACTS;
  }

  return {
    async loadInstructionAfterAuth(instructionId: string): Promise<InstructionView> {
      requireAuth();
      return findInstruction(instructionId);
    },

    async markFirstView(instructionId: string, revision: number): Promise<FirstViewFact> {
      requireAuth();
      const view = findInstruction(instructionId);
      if (revision !== view.revision) {
        throw new Error('REVISION_MISMATCH');
      }
      const current = factsFor(instructionId);
      const updated = recordFirstViewOnce(
        current, instructionId, revision, fixture.viewer, fixture.now,
      );
      facts = { ...facts, [instructionId]: updated };
      const recorded = updated.firstView;
      if (recorded === null || recorded.revision !== revision || recorded.viewer !== fixture.viewer) {
        throw new Error('FIRST_VIEW_MISMATCH');
      }
      return recorded;
    },

    async acknowledge(instructionId: string, revision: number): Promise<AckResult> {
      requireAuth();
      const view = findInstruction(instructionId);
      const result = acknowledgeRevisionBound(
        view, factsFor(instructionId), revision, fixture.viewer, fixture.now,
      );
      if (result.ok) {
        facts = {
          ...facts,
          [instructionId]: { ...factsFor(instructionId), acknowledgment: result.fact },
        };
      }
      return result;
    },

    overdueInstructions(now: string): readonly InstructionView[] {
      requireAuth();
      const visible = fixture.instructions.filter((item) =>
        item.organizationId === fixture.organizationId && item.patientId === fixture.viewer);
      return overdueInstructions(visible, facts, now);
    },

    owningUnitFor(instructionId: string): string {
      requireAuth();
      return findInstruction(instructionId).unitId;
    },
  };
}
