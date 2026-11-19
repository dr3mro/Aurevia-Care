import React, { useEffect, useRef } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';

import { acknowledgmentStatus } from './facts';
import type { DeliveryFacts, InstructionView, OverdueStatus } from './types';

/** Accessibility labels and patient-facing strings for MSG-05 facts. */
export const MESSAGE_FACT_LABELS = {
  inbox: 'Instruction inbox',
  acknowledged: 'Receipt acknowledged',
  overdue: 'Acknowledgment requested - overdue',
  pending: 'Acknowledgment requested',
  notRequested: 'No acknowledgment requested',
  withdrawn: 'Instruction withdrawn',
  acknowledge: (revision: number): string => `Acknowledge receipt of instruction revision ${revision}`,
  revision: (revision: number): string => `Revision ${revision}`,
} as const;

/** Props for the patient instruction acknowledgment screen (MSG-05). */
export interface InstructionAcknowledgmentScreenProps {
  readonly instructions: readonly InstructionView[];
  readonly facts: Readonly<Record<string, DeliveryFacts>>;
  readonly isRTL: boolean;
  readonly now: string;
  readonly onFirstView: (instructionId: string) => void;
  readonly onAcknowledge: (instructionId: string, revision: number) => void;
}

/** Status label shown for one instruction, independent of clinical content. */
export function statusLabel(status: OverdueStatus): string {
  switch (status) {
    case 'acknowledged':
      return MESSAGE_FACT_LABELS.acknowledged;
    case 'overdue':
      return MESSAGE_FACT_LABELS.overdue;
    case 'pending':
      return MESSAGE_FACT_LABELS.pending;
    case 'withdrawn':
      return MESSAGE_FACT_LABELS.withdrawn;
    case 'not_requested':
      return MESSAGE_FACT_LABELS.notRequested;
    default:
      return MESSAGE_FACT_LABELS.notRequested;
  }
}

/**
 * Patient screen for first-view and receipt acknowledgment facts.
 *
 * First view is reported once per revealed instruction; acknowledgment is an
 * explicit patient action bound to the displayed revision. The screen renders
 * only already-fetched projections and echoes no raw clinical payload.
 *
 * @param props instruction projections, fact state, direction and callbacks
 * @returns an accessible, RTL-aware instruction inbox
 */
export function InstructionAcknowledgmentScreen({
  instructions,
  facts,
  isRTL,
  now,
  onFirstView,
  onAcknowledge,
}: InstructionAcknowledgmentScreenProps): React.JSX.Element {
  const seen = useRef<Set<string>>(new Set());

  useEffect(() => {
    for (const instruction of instructions) {
      if (!seen.current.has(instruction.id)) {
        seen.current.add(instruction.id);
        onFirstView(instruction.id);
      }
    }
  }, [instructions, onFirstView]);

  return (
    <View
      accessible
      accessibilityLabel={MESSAGE_FACT_LABELS.inbox}
      style={[styles.container, { direction: isRTL ? 'rtl' : 'ltr' }]}>
      {instructions.map((instruction) => {
        const current = facts[instruction.id];
        const status = acknowledgmentStatus(instruction, current ?? EMPTY_SCREEN_FACTS, now);
        const acknowledged = status === 'acknowledged';
        const canAcknowledge = !acknowledged
          && status !== 'withdrawn'
          && status !== 'not_requested'
          && instruction.revision === instruction.latestRevision;
        return (
          <View key={instruction.id} testID={`instruction-${instruction.id}`} style={styles.row}>
            <Text accessibilityRole="header">{instruction.title}</Text>
            <Text accessibilityRole="text">
              {MESSAGE_FACT_LABELS.revision(instruction.revision)}
            </Text>
            <Text accessibilityRole={status === 'overdue' ? 'alert' : 'text'}>
              {statusLabel(status)}
            </Text>
            {canAcknowledge ? (
              <Pressable
                accessibilityRole="button"
                accessibilityLabel={MESSAGE_FACT_LABELS.acknowledge(instruction.revision)}
                style={styles.button}
                onPress={() => onAcknowledge(instruction.id, instruction.revision)}>
                <Text>{MESSAGE_FACT_LABELS.acknowledge(instruction.revision)}</Text>
              </Pressable>
            ) : null}
          </View>
        );
      })}
    </View>
  );
}

const EMPTY_SCREEN_FACTS: DeliveryFacts = {
  publication: null,
  dispatch: null,
  firstView: null,
  acknowledgment: null,
};

const styles = StyleSheet.create({
  container: { flex: 1, padding: 16 },
  row: { paddingVertical: 12 },
  button: { minHeight: 44, minWidth: 44, justifyContent: 'center' },
});
