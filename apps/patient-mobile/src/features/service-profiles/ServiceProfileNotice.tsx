import { createContext, useContext } from 'react';
import type { Context } from 'react';
import { Pressable, Text, View, useWindowDimensions } from 'react-native';
import { SafeAreaInsetsContext } from 'react-native-safe-area-context';

import { windowClassFor } from '../../app/layout/useWindowClass';
import { channelText, localizedText, noticeLines } from './serviceNotice';
import type { ServiceAvailabilityView, ServiceProfileView } from './types';

/** Minimum touch target for the acknowledgement control (44 pt / 48 dp floor). */
const MIN_TOUCH_TARGET = 44;
/** Maximum content width on a large window so long clinical lines stay readable. */
const MAX_CONTENT_WIDTH = 960;

/** The safe-area edges this presentation consumes. */
interface NoticeInsets {
  readonly top: number;
  readonly right: number;
  readonly bottom: number;
  readonly left: number;
}

const NO_INSETS: NoticeInsets = { top: 0, right: 0, bottom: 0, left: 0 };
/** A stable fallback so a layout without a provider still renders unclipped. */
const FALLBACK_INSETS_CONTEXT = createContext<NoticeInsets | null>(null);
const INSETS_CONTEXT = (SafeAreaInsetsContext ?? FALLBACK_INSETS_CONTEXT) as Context<
  NoticeInsets | null
>;

/**
 * Read the app's safe-area edges, falling back to zero when no provider is
 * mounted. The context is read directly so this read-only notice keeps working
 * in every host (including headless tests) without owning a provider.
 */
function useNoticeInsets(): NoticeInsets {
  return useContext(INSETS_CONTEXT) ?? NO_INSETS;
}

/** Props for the patient-facing MSG-06 service-information notice. */
export interface ServiceProfileNoticeProps {
  /** Effective, named-approved profile served by the API. */
  readonly profile: ServiceProfileView;
  /** Server-computed availability; never recalculated on the device. */
  readonly availability: ServiceAvailabilityView;
  /** Active language for approved guidance (UX-01). */
  readonly language: 'en' | 'ar';
  /** Mirror layout for Arabic and other RTL locales (UX-01). */
  readonly isRTL: boolean;
  /** Called only after the patient explicitly acknowledges the guidance. */
  readonly onAcknowledge: () => void;
}

/**
 * Show service hours, response target, approved topics, urgent and downtime
 * channels, named owners and after-hours guidance before a patient sends.
 *
 * Urgent guidance and channels are rendered before any routine "next handling"
 * text and before the explicit acknowledgement action, so time-sensitive care
 * is never delayed by an after-hours routine-queue message.
 *
 * Presentation follows the current window (PAT-01, PAT-02, UX-01): small and
 * regular windows use one reflowing column; a large window below 200% text
 * (`fontScale < 2`) uses a centred overview/guidance pair of panes. At 200%
 * text even a large window reflows to one column so no approved value or action
 * is clipped. The
 * node order is identical in both layouts, so focus order and screen-reader
 * labels do not change. Layout reads window metrics only; it adds no clinical
 * cache, changes no data and makes no API call.
 */
export function ServiceProfileNotice({
  profile,
  availability,
  language,
  isRTL,
  onAcknowledge,
}: ServiceProfileNoticeProps) {
  const { width, fontScale } = useWindowDimensions();
  const windowClass = windowClassFor(width);
  const insets = useNoticeInsets();
  const isWide = windowClass === 'large';
  const twoPane = isWide && fontScale < 2;
  const urgent = localizedText(profile.urgentInstructions, language);
  const lines = noticeLines(profile, availability, language);

  // The overview is first in the accessibility tree in every layout; the
  // guidance follows it, keeping time-sensitive channels before the routine
  // queue line and the acknowledgement action.
  const overview = [
    <Text key="unit-name" accessibilityRole="header" testID="unit-name">{profile.unitName}</Text>,
    <Text key="service-hours" testID="service-hours">
      {profile.serviceHours.timeZone}: {profile.serviceHours.windows.map((window) =>
        `${window.weekday} ${window.start}-${window.end}${window.wraps ? ' (+1)' : ''}`).join(', ')}
    </Text>,
    <Text key="response-target" testID="response-target">
      Response target: {profile.responseTargetMinutes} min
    </Text>,
    <Text key="topics" testID="topics">Topics: {profile.topics.join(', ')}</Text>,
  ];

  const guidance = [];
  if (urgent !== undefined) {
    guidance.push(
      <Text testID="urgent-instructions" accessibilityRole="text">{urgent}</Text>,
    );
  }
  profile.urgentChannels.forEach((channel, index) => {
    guidance.push(
      <Text
        key={channel.key}
        testID={`urgent-channel-${index}`}
        accessibilityRole={channel.key === 'phone' || channel.key === 'link' ? 'link' : 'text'}
      >
        {channelText(channel)}
      </Text>,
    );
  });
  guidance.push(<Text testID="queue-owner">Queue owner: {profile.queueOwner}</Text>);
  guidance.push(
    <Text testID="backup-coverage">Backup coverage: {profile.backupCoverage.join(', ')}</Text>,
  );
  guidance.push(
    <Text testID="escalation-channel">Overdue escalation: {profile.escalationChannel}</Text>,
  );
  if (!availability.open && availability.nextHandlingAt !== undefined) {
    guidance.push(
      <Text testID="next-handling" accessibilityRole="alert" accessibilityLiveRegion="polite">
        {lines[lines.length - 1]}
      </Text>,
    );
  }
  guidance.push(
    <Pressable
      accessibilityRole="button"
      accessibilityLabel="I understand the service guidance"
      testID="acknowledge-guidance"
      onPress={onAcknowledge}
      style={{
        minHeight: MIN_TOUCH_TARGET,
        minWidth: MIN_TOUCH_TARGET,
        alignItems: 'center',
        justifyContent: 'center',
      }}
    >
      <Text>I understand</Text>
    </Pressable>,
  );

  return (
    <View
      testID="service-profile-notice"
      style={{
        flexDirection: isRTL ? 'row-reverse' : 'row',
        justifyContent: isWide ? 'center' : 'flex-start',
        alignSelf: isWide ? 'center' : undefined,
        maxWidth: isWide ? MAX_CONTENT_WIDTH : undefined,
        paddingTop: insets.top,
        paddingBottom: insets.bottom,
        paddingStart: isRTL ? insets.right : insets.left,
        paddingEnd: isRTL ? insets.left : insets.right,
      }}
    >
      {twoPane ? (
        <View
          testID="notice-panes"
          style={{ flexDirection: isRTL ? 'row-reverse' : 'row', flexGrow: 1, gap: 16 }}
        >
          <View testID="notice-overview" style={{ flex: 1, minWidth: 0 }}>{overview}</View>
          <View testID="notice-guidance" style={{ flex: 1, minWidth: 0 }}>{guidance}</View>
        </View>
      ) : (
        <View testID="notice-stack" style={{ flexGrow: 1 }}>{overview}{guidance}</View>
      )}
    </View>
  );
}
