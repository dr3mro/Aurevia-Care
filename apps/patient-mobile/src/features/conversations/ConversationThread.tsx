import { useState } from 'react';
import { Pressable, StyleSheet, Text, TextInput, View } from 'react-native';

import {
  acknowledgeLabel,
  Card,
  MessageBubble,
  NoticeBox,
  PrimaryButton,
  resolveScreenLocale,
  screenText,
  ScreenFrame,
  SCREEN_TOKENS,
} from '../appointments/patientDesign';
import type { Locale, TabId } from '../appointments/patientDesign';

import { canReply, senderLabel } from './domain';
import type { Conversation, LayoutDirection } from './domain';
import { activeLayoutDirection } from './layout';

/** Props for the patient conversation thread. */
export interface ConversationThreadProps {
  /** Conversation and its ordered messages. */
  readonly conversation: Conversation;
  /** Called with the conversation id and trimmed draft text for a non-empty reply. */
  readonly onSend: (conversationId: string, body: string) => void;
  /** Active display locale for all patient-facing copy. */
  readonly locale?: Locale;
  /** Explicit layout direction; the device direction is the default. */
  readonly direction?: LayoutDirection;
  /** Called when the patient acknowledges the exact instruction revision shown. */
  readonly onAcknowledge?: (conversationId: string, revision: number) => void;
  /** Render the standalone phone-frame tab bar for a design review. */
  readonly showTabBar?: boolean;
  /** Highlighted standalone tab. */
  readonly activeTab?: TabId;
  /** Standalone tab handler. */
  readonly onTab?: (tab: TabId) => void;
}

/**
 * Patient conversation thread with a reply composer for writable threads.
 *
 * Closed threads and revoked connections render a read-only notice and no send
 * control, so the app mirrors the server's MSG-02 read-only rule. The MSG-06
 * service notice, sender/time bubbles and the revision-bound acknowledgment
 * are presentational only: every command is a typed callback to the
 * authenticated host.
 *
 * @param props conversation, direction, locale and handlers
 */
export function ConversationThread({
  conversation,
  onSend,
  locale = resolveScreenLocale(),
  direction,
  onAcknowledge,
  showTabBar = false,
  activeTab = 'messages',
  onTab,
}: ConversationThreadProps) {
  const [draft, setDraft] = useState('');
  const layoutDirection = direction ?? activeLayoutDirection();
  const editable = canReply(conversation);
  const canSend = draft.trim().length > 0;
  // A disabled Pressable never fires onPress; the domain also rejects a blank body.
  const sendReply = () => onSend(conversation.id, draft.trim());
  const instruction = conversation.instruction;
  const canAcknowledge = instruction !== undefined
    && instruction.acknowledgedRevision !== instruction.revision;
  return (
    <ScreenFrame
      testID="conversation-thread"
      rootLabel="conversation thread"
      locale={locale}
      direction={layoutDirection}
      title={screenText(locale, 'messagesTitle')}
      subtitle={screenText(locale, 'messagesSubtitle')}
      showTabBar={showTabBar}
      activeTab={activeTab}
      onTab={onTab}>
      <Text accessibilityLabel={`unit ${conversation.unitName}`} accessibilityLanguage={locale} style={styles.unit}>
        {conversation.unitName}
      </Text>
      <Text accessibilityLabel={`state ${conversation.state}`} accessibilityLanguage={locale} style={styles.caption}>
        {conversation.state}
      </Text>
      {conversation.serviceNotice === undefined ? null : (
        <NoticeBox
          testID="service-notice"
          variant="info"
          locale={locale}
          lines={[
            `${screenText(locale, 'serviceHours')}: ${conversation.serviceNotice.serviceHours}`,
            `${screenText(locale, 'responseTarget')}: ${conversation.serviceNotice.responseTarget}`,
          ]}
        />
      )}
      {conversation.messages.map((message) => (
        <MessageBubble
          key={message.id}
          testID={`message-${message.id}`}
          own={message.senderRole === 'patient'}
          label={`${senderLabel(message.senderRole)} said: ${message.body}`}
          body={message.body}
          time={message.sentAt}
          locale={locale}
        />
      ))}
      {instruction === undefined ? null : (
        <Card testID={`instruction-${instruction.id}`}>
          <Text accessibilityLabel="instruction-title" accessibilityLanguage={locale} style={styles.instructionTitle}>
            {instruction.title}
          </Text>
          <Text accessibilityLabel="instruction-revision" accessibilityLanguage={locale}>
            {`${screenText(locale, 'revision')} ${instruction.revision}`}
          </Text>
          {canAcknowledge && onAcknowledge !== undefined ? (
            <PrimaryButton
              testID={`acknowledge-${instruction.id}`}
              label={acknowledgeLabel(locale, instruction.revision)}
              locale={locale}
              onPress={() => onAcknowledge(conversation.id, instruction.revision)}
            />
          ) : null}
        </Card>
      )}
      <Text accessibilityLabel="to" accessibilityLanguage={locale} style={styles.to}>
        {`${screenText(locale, 'to')}: ${conversation.unitName}`}
      </Text>
      {editable ? (
        <View accessibilityLabel="reply composer">
          <TextInput
            accessibilityLabel="reply input"
            accessibilityLanguage={locale}
            value={draft}
            onChangeText={setDraft}
            style={styles.input}
          />
          <Pressable
            accessibilityRole="button"
            accessibilityLabel="send reply"
            accessibilityLanguage={locale}
            accessibilityState={{ disabled: !canSend }}
            disabled={!canSend}
            onPress={sendReply}
            style={[styles.sendButton, !canSend ? styles.sendButtonDisabled : null]}>
            <Text accessibilityLanguage={locale} style={[styles.sendText, !canSend ? styles.sendTextDisabled : null]}>
              {screenText(locale, 'send')}
            </Text>
          </Pressable>
          <Text accessibilityLabel="unsent-note" accessibilityLanguage={locale} style={styles.caption}>
            {screenText(locale, 'unsentNote')}
          </Text>
        </View>
      ) : (
        <Text accessibilityLabel="read only" accessibilityLanguage={locale}>
          {screenText(locale, 'readOnly')}
        </Text>
      )}
    </ScreenFrame>
  );
}

const styles = StyleSheet.create({
  unit: { color: SCREEN_TOKENS.color.textPrimary, fontSize: SCREEN_TOKENS.font.headingSm, fontWeight: SCREEN_TOKENS.font.semibold },
  caption: { color: SCREEN_TOKENS.color.textSecondary, fontSize: SCREEN_TOKENS.font.caption },
  instructionTitle: { color: SCREEN_TOKENS.color.warningText, fontWeight: SCREEN_TOKENS.font.semibold },
  to: { color: SCREEN_TOKENS.color.textSecondary, fontSize: SCREEN_TOKENS.font.label, marginTop: SCREEN_TOKENS.space.four },
  input: {
    borderColor: SCREEN_TOKENS.color.borderDefault,
    borderRadius: SCREEN_TOKENS.radius.md,
    borderWidth: SCREEN_TOKENS.border.thin,
    minHeight: SCREEN_TOKENS.size.target,
    padding: SCREEN_TOKENS.space.two,
  },
  sendButton: {
    alignItems: 'center',
    backgroundColor: SCREEN_TOKENS.color.actionPrimaryBackground,
    borderRadius: SCREEN_TOKENS.radius.md,
    justifyContent: 'center',
    minHeight: SCREEN_TOKENS.size.target,
    minWidth: SCREEN_TOKENS.size.target,
  },
  sendButtonDisabled: { backgroundColor: SCREEN_TOKENS.color.borderSubtle },
  sendText: { color: SCREEN_TOKENS.color.actionPrimaryText, fontWeight: SCREEN_TOKENS.font.semibold },
  sendTextDisabled: { color: SCREEN_TOKENS.color.textSecondary },
});
