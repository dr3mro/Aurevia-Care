import { useState } from 'react';
import { Pressable, StyleSheet, Text, TextInput, View } from 'react-native';

import type { Conversation } from './domain';
import { canReply, senderLabel } from './domain';
import { activeLayoutDirection } from './layout';

/** Props for the patient conversation thread. */
export interface ConversationThreadProps {
  /** Conversation and its ordered messages. */
  readonly conversation: Conversation;
  /** Called with the conversation id and trimmed draft text for a non-empty reply. */
  readonly onSend: (conversationId: string, body: string) => void;
}

/**
 * Patient conversation thread with a reply composer for writable threads.
 *
 * Closed threads and revoked connections render a read-only notice and no send
 * control, so the app mirrors the server's MSG-02 read-only rule.
 *
 * @param props conversation and send handler
 */
export function ConversationThread({ conversation, onSend }: ConversationThreadProps) {
  const [draft, setDraft] = useState('');
  const direction = activeLayoutDirection();
  const editable = canReply(conversation);
  const canSend = draft.trim().length > 0;
  // A disabled Pressable never fires onPress; the domain also rejects a blank body.
  const sendReply = () => onSend(conversation.id, draft.trim());
  return (
    <View accessibilityLabel="conversation thread" style={[styles.container, { direction }]}>
      <Text accessibilityLabel={`unit ${conversation.unitName}`}>{conversation.unitName}</Text>
      <Text accessibilityLabel={`state ${conversation.state}`}>{conversation.state}</Text>
      {conversation.messages.map(message => (
        <Text
          key={message.id}
          accessibilityRole="text"
          accessibilityLabel={`${senderLabel(message.senderRole)} said: ${message.body}`}
        >
          {message.body}
        </Text>
      ))}
      {editable ? (
        <View accessibilityLabel="reply composer">
          <TextInput accessibilityLabel="reply input" value={draft} onChangeText={setDraft} />
          <Pressable
            accessibilityRole="button"
            accessibilityLabel="send reply"
            accessibilityState={{ disabled: !canSend }}
            disabled={!canSend}
            onPress={sendReply}
          >
            <Text>Send</Text>
          </Pressable>
        </View>
      ) : (
        <Text accessibilityLabel="read only">This conversation is read only.</Text>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flexGrow: 1 },
});
