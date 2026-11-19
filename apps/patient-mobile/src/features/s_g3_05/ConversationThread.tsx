import { useState } from 'react';
import { Pressable, StyleSheet, Text, TextInput, View } from 'react-native';

import type { Conversation } from './domain';
import { canReply } from './domain';
import { activeLayoutDirection } from './layout';

/** Props for the patient conversation thread. */
export interface ConversationThreadProps {
  /** Conversation and its ordered messages. */
  readonly conversation: Conversation;
  /** Called with the durable message id and draft text when the patient sends. */
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
  return (
    <View accessibilityLabel="conversation thread" style={[styles.container, { direction }]}>
      <Text accessibilityLabel={`unit ${conversation.unitName}`}>{conversation.unitName}</Text>
      <Text accessibilityLabel={`state ${conversation.state}`}>{conversation.state}</Text>
      {conversation.messages.map(message => (
        <Text key={message.id} accessibilityLabel={`message ${message.sequence} from ${message.senderRole}`}>
          {message.body}
        </Text>
      ))}
      {editable ? (
        <View accessibilityLabel="reply composer">
          <TextInput accessibilityLabel="reply input" value={draft} onChangeText={setDraft} />
          <Pressable accessibilityLabel="send reply" onPress={() => onSend(conversation.id, draft)}>
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
