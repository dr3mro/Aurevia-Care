import { Pressable, StyleSheet, Text } from 'react-native';

import { resolveScreenLocale, screenText, ScreenFrame, SCREEN_TOKENS } from '../appointments/patientDesign';
import type { Locale, TabId } from '../appointments/patientDesign';

import type { Conversation } from './domain';
import { unreadCount } from './domain';

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
  /** Active display locale; defaults to English for embedders without a locale prop. */
  readonly locale?: Locale;
  /** Render the standalone phone-frame tab bar for a design review. */
  readonly showTabBar?: boolean;
  /** Highlighted standalone tab. */
  readonly activeTab?: TabId;
  /** Standalone tab handler. */
  readonly onTab?: (tab: TabId) => void;
}

/**
 * Patient conversation inbox with per-unit unread badges and state labels.
 *
 * Business logic stays in {@link unreadCount}; this component only renders the
 * projection and labels the unit, state and unread count for screen readers
 * (MSG-02, UX-01).
 *
 * @param props conversations and open handler
 */
export function ConversationInbox({
  conversations,
  unreadByConversation,
  onOpen,
  locale = resolveScreenLocale(),
  showTabBar = false,
  activeTab = 'messages',
  onTab,
}: ConversationInboxProps) {
  return (
    <ScreenFrame
      testID="conversation-inbox"
      rootLabel="conversation inbox"
      locale={locale}
      title={screenText(locale, 'messagesTitle')}
      subtitle={screenText(locale, 'messagesSubtitle')}
      showTabBar={showTabBar}
      activeTab={activeTab}
      onTab={onTab}>
      {conversations.map(conversation => {
        const unread = unreadByConversation?.[conversation.id] ?? unreadCount(conversation);
        const hasUnread = unread > 0;
        return (
          <Pressable
            key={conversation.id}
            accessibilityRole="button"
            accessibilityLanguage={locale}
            // Child texts are grouped into the pressable on device, so the
            // unread count must be part of this label to be announced (UX-01).
            accessibilityLabel={`${conversation.unitName}: ${conversation.state}`
              + (hasUnread ? `, ${unread} unread` : '')}
            onPress={() => onOpen(conversation.id)}
            style={styles.row}>
            <Text accessibilityLanguage={locale} style={styles.unit}>{conversation.unitName}</Text>
            <Text accessibilityLanguage={locale}>{conversation.state}</Text>
            {hasUnread ? <Text accessibilityLanguage={locale} style={styles.badge}>{unread}</Text> : null}
          </Pressable>
        );
      })}
    </ScreenFrame>
  );
}

const styles = StyleSheet.create({
  row: { paddingVertical: SCREEN_TOKENS.space.three, paddingHorizontal: SCREEN_TOKENS.space.four },
  unit: { color: SCREEN_TOKENS.color.textPrimary, fontWeight: SCREEN_TOKENS.font.semibold },
  badge: { color: SCREEN_TOKENS.color.successText },
});
