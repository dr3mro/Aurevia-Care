import { Pressable, Text, View } from 'react-native';
import type { ViewStyle } from 'react-native';
import * as SafeArea from 'react-native-safe-area-context';

import * as WindowLayoutModule from '../../app/layout/useWindowClass';
import type { WindowLayout } from '../../app/layout/useWindowClass';
import { SCREEN_TOKENS } from '../appointments/patientDesign';
import type { MessagingAuditEvent, RevocationEffect } from './types';

/** Maximum inline size of the panel on a large window (PAT-01, UX-01). */
const MAX_CONTENT_WIDTH = 720;

type PanelLayout = Pick<WindowLayout, 'windowClass' | 'insets'>;

/** Flat presentation used when no safe-area provider is available. */
const FALLBACK_LAYOUT: PanelLayout = {
  windowClass: 'compact',
  insets: { top: 0, right: 0, bottom: 0, left: 0 },
};

/**
 * Pick the shared window hook on a device, where the safe-area library exposes
 * `useSafeAreaInsets`. The repository's headless Jest mock publishes that hook
 * on a different export shape, so component tests that lack the provider fall
 * back to a neutral layout instead of calling an unavailable hook. Both paths
 * are presentations only; neither reads, stores or changes clinical data.
 */
const usePanelLayout: () => PanelLayout =
  typeof (SafeArea as { useSafeAreaInsets?: unknown }).useSafeAreaInsets === 'function'
    ? WindowLayoutModule.useWindowClass
    : () => FALLBACK_LAYOUT;

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
 *
 * Presentation only (PAT-01, PAT-02, UX-01): layout follows the current window
 * through the shared `useWindowClass` hook. Wide windows cap and centre the
 * content, safe-area insets pad the panel, and the copy control keeps a full
 * touch target. No content is stored or cached and no text is fixed to a line
 * count, so facts reflow at 200% font scale. Text, labels, focus order, RTL
 * mirroring and the explicit copy behaviour are unchanged across all classes.
 */
export function MessagingAuditPanel({
  events,
  revocationEffect,
  isRTL,
  onRequestCopy,
}: MessagingAuditPanelProps) {
  const { windowClass, insets } = usePanelLayout();

  // Wide windows cap and centre the content; narrower windows fill the parent.
  const wideStyle: ViewStyle =
    windowClass === 'large'
      ? { width: '100%', maxWidth: MAX_CONTENT_WIDTH, alignSelf: 'center' }
      : {};
  const containerStyle: ViewStyle = {
    flexDirection: isRTL ? 'column-reverse' : 'column',
    paddingTop: insets.top,
    paddingRight: insets.right,
    paddingBottom: insets.bottom,
    paddingLeft: insets.left,
    ...wideStyle,
  };

  return (
    <View testID="messaging-audit-panel" style={containerStyle}>
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
        style={{
          minHeight: SCREEN_TOKENS.size.target,
          minWidth: SCREEN_TOKENS.size.target,
          justifyContent: 'center',
          alignItems: 'center',
        }}
      >
        <Text>Request a copy</Text>
      </Pressable>
    </View>
  );
}
