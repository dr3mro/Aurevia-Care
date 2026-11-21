import { Pressable, StyleSheet, Text, View } from 'react-native';

import type { Conversation } from './domain';
import { unreadCount } from './domain';
import { activeLayoutDirection } from './layout';

/** Props for the patient conversation inbox. */
export interface ConversationInboxProps {
  /** Conversations belonging to the verified patient. */
  readonly conversations: readonly Conversation[];
  /**
   * Server-authored unread counts, keyed by conversation identifier. The inbox
   * endpoint returns an authoritative count; the local derivation is a fallback
   * for callers that only have a full message trail (MSG-02).
   */
  readonly unreadByConversation?: Readonly<Record<string, number>>;
  /** Called when the patient opens a conversation. */
  readonly onOpen: (conversationId: string) => void;
}

/**
 * Patient conversation inbox with per-unit unread badges and state labels.
 *
 * Business logic stays in {@link unreadCount}; this component only renders the
 * projection and mirrors the device layout direction (MSG-02, UX-01).
 *
 * @param props conversations and open handler
 */
export function ConversationInbox({ conversations, unreadByConversation, onOpen }: ConversationInboxProps) {
  const direction = activeLayoutDirection();
  return (
    <View accessibilityLabel="conversation inbox" style={[styles.list, { direction }]}>
      {conversations.map(conversation => {
        const unread = unreadByConversation?.[conversation.id] ?? unreadCount(conversation);
        const hasUnread = unread > 0;
        return (
          <Pressable
            key={conversation.id}
            accessibilityRole="button"
            // Child texts are grouped into the pressable on device, so the
            // unread count must be part of this label to be announced (UX-01).
            accessibilityLabel={`${conversation.unitName}: ${conversation.state}`
              + (hasUnread ? `, ${unread} unread` : '')}
            onPress={() => onOpen(conversation.id)}
            style={styles.row}
          >
            <Text style={styles.unit}>{conversation.unitName}</Text>
            <Text>{conversation.state}</Text>
            {hasUnread ? <Text style={styles.badge}>{unread}</Text> : null}
          </Pressable>
        );
      })}
    </View>
  );
}

const styles = StyleSheet.create({
  list: { flexGrow: 1 },
  row: { paddingVertical: 12, paddingHorizontal: 16 },
  unit: { fontWeight: '600' },
  badge: { color: '#0a7d3c' },
});
