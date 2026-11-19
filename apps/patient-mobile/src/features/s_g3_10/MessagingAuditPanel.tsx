import { Pressable, Text, View } from 'react-native';

import type { MessagingAuditEvent, RevocationEffect } from './types';

/** Props for the patient messaging audit and governance panel (MSG-07). */
export interface MessagingAuditPanelProps {
  /** Audit facts already authorized for this viewer, newest last. */
  readonly events: readonly MessagingAuditEvent[];
  /** Recorded revocation effects to display, when any exist. */
  readonly revocationEffect?: RevocationEffect;
  /** Mirrors column direction for Arabic and other RTL locales (UX-01). */
  readonly isRTL: boolean;
  /** Called only after the patient explicitly requests a copy. */
  readonly onRequestCopy: () => void;
}

/**
 * Show non-sensitive messaging audit facts and revocation consequences.
 *
 * The panel never renders message content, attachment bytes or clinical detail.
 * It describes whether new messaging and future attachment access are blocked
 * and whether earlier conversations remain readable under the record-access
 * policy. The copy action requires an explicit press.
 */
export function MessagingAuditPanel({
  events,
  revocationEffect,
  isRTL,
  onRequestCopy,
}: MessagingAuditPanelProps) {
  return (
    <View testID="messaging-audit-panel" style={{ flexDirection: isRTL ? 'column-reverse' : 'column' }}>
      {events.length === 0 ? (
        <Text accessibilityRole="text">No messaging activity</Text>
      ) : (
        events.map((event) => (
          <View key={event.id} testID={`audit-${event.id}`} accessibilityRole="text">
            <Text>{event.action}</Text>
            <Text>{event.outcome}</Text>
            <Text>{event.occurredAt}</Text>
          </View>
        ))
      )}
      {revocationEffect !== undefined ? (
        <View testID="revocation-effect" accessibilityRole="text">
          <Text>{revocationEffect.messagingBlocked ? 'New messaging blocked' : 'New messaging allowed'}</Text>
          <Text>
            {revocationEffect.attachmentAccessBlocked ? 'Attachments blocked' : 'Attachments allowed'}
          </Text>
          <Text>
            {revocationEffect.priorConversationsReadable
              ? 'Prior conversations readable'
              : 'Prior conversations not readable'}
          </Text>
        </View>
      ) : null}
      <Pressable
        accessibilityRole="button"
        accessibilityLabel="Request a copy"
        testID="request-copy"
        onPress={onRequestCopy}
      >
        <Text>Request a copy</Text>
      </Pressable>
    </View>
  );
}
