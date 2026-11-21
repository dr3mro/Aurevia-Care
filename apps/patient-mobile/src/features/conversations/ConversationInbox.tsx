import { Pressable, StyleSheet, Text, useWindowDimensions, View } from 'react-native';

import { windowClassFor } from '../../app/layout/useWindowClass';
import { resolveScreenLocale, screenText, ScreenFrame, SCREEN_TOKENS } from '../appointments/patientDesign';
import type { Locale, TabId } from '../appointments/patientDesign';

import type { Conversation } from './domain';
import { unreadCount } from './domain';

/** Maximum inline width of the centred content column on a large window. */
const CONTENT_MAX_WIDTH = 1120;

/**
 * Maximum inline width of the inbox list pane on a large window. The remaining
 * width is left for the thread pane that the host screen composes beside it
 * (decision 0005), so the list never stretches over a detail region.
 */
const LIST_PANE_MAX_WIDTH = 560;

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
 * (MSG-02, UX-01). Layout is presentation only: the shared window classifier
 * drives a centred, width-capped list pane on large windows while labels,
 * focus order, data access and the open handler stay identical at every width
 * (PAT-02). Safe-area insets are owned by the app shell so this pane adds no
 * second, direction-sensitive padding layer.
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
  const { width } = useWindowDimensions();
  const isLarge = windowClassFor(width) === 'large';

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
      <View
        testID="conversation-inbox-content"
        style={[styles.content, isLarge ? styles.contentLarge : null]}>
        <View testID="conversation-inbox-list-pane" style={[styles.listPane, isLarge ? styles.listPaneLarge : null]}>
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
        </View>
      </View>
    </ScreenFrame>
  );
}

const styles = StyleSheet.create({
  content: { flex: 1, width: '100%' },
  // A large window centres the column and caps it at a readable inline width.
  contentLarge: { alignSelf: 'center', maxWidth: CONTENT_MAX_WIDTH },
  listPane: { width: '100%' },
  listPaneLarge: { maxWidth: LIST_PANE_MAX_WIDTH },
  // minHeight keeps every row at least 48 dp; no fixed height, capped font
  // size or line count, so text reflows instead of clipping at 200% (UX-01).
  row: {
    justifyContent: 'center',
    minHeight: SCREEN_TOKENS.size.target,
    minWidth: SCREEN_TOKENS.size.target,
    paddingVertical: SCREEN_TOKENS.space.three,
    paddingHorizontal: SCREEN_TOKENS.space.four,
  },
  unit: { color: SCREEN_TOKENS.color.textPrimary, fontWeight: SCREEN_TOKENS.font.semibold },
  badge: { color: SCREEN_TOKENS.color.successText },
});
