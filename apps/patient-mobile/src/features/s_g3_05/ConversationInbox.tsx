import { Pressable, StyleSheet, Text, View } from 'react-native';

import type { Conversation } from './domain';
import { unreadCount } from './domain';
import { activeLayoutDirection } from './layout';

/** Props for the patient conversation inbox. */
export interface ConversationInboxProps {
  /** Conversations belonging to the verified patient. */
  readonly conversations: readonly Conversation[];
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
export function ConversationInbox({ conversations, onOpen }: ConversationInboxProps) {
  const direction = activeLayoutDirection();
  return (
    <View accessibilityLabel="conversation inbox" style={[styles.list, { direction }]}>
      {conversations.map(conversation => {
        const unread = unreadCount(conversation);
        const hasUnread = unread > 0;
        return (
          <Pressable
            key={conversation.id}
            accessibilityLabel={`${conversation.unitName}: ${conversation.state}`}
            onPress={() => onOpen(conversation.id)}
            style={styles.row}
          >
            <Text style={styles.unit}>{conversation.unitName}</Text>
            <Text accessibilityLabel={`state ${conversation.state}`}>{conversation.state}</Text>
            {hasUnread ? (
              <Text accessibilityLabel={`unread ${unread}`} style={styles.badge}>{unread}</Text>
            ) : null}
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
