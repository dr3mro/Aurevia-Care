import { Pressable, Text, View } from 'react-native';

import { channelText, localizedText, noticeLines } from './serviceNotice';
import type { ServiceAvailabilityView, ServiceProfileView } from './types';

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
 */
export function ServiceProfileNotice({
  profile,
  availability,
  language,
  isRTL,
  onAcknowledge,
}: ServiceProfileNoticeProps) {
  const urgent = localizedText(profile.urgentInstructions, language);
  const lines = noticeLines(profile, availability, language);
  return (
    <View
      testID="service-profile-notice"
      style={{ flexDirection: isRTL ? 'row-reverse' : 'row' }}
    >
      <Text accessibilityRole="header" testID="unit-name">{profile.unitName}</Text>
      <Text testID="service-hours">
        {profile.serviceHours.timeZone}: {profile.serviceHours.windows.map((window) =>
          `${window.weekday} ${window.start}-${window.end}${window.wraps ? ' (+1)' : ''}`).join(', ')}
      </Text>
      <Text testID="response-target">Response target: {profile.responseTargetMinutes} min</Text>
      <Text testID="topics">Topics: {profile.topics.join(', ')}</Text>
      {urgent !== undefined && (
        <Text testID="urgent-instructions" accessibilityRole="text">{urgent}</Text>
      )}
      {profile.urgentChannels.map((channel, index) => (
        <Text
          key={channel.key}
          testID={`urgent-channel-${index}`}
          accessibilityRole={channel.key === 'phone' || channel.key === 'link' ? 'link' : 'text'}
        >
          {channelText(channel)}
        </Text>
      ))}
      <Text testID="queue-owner">Queue owner: {profile.queueOwner}</Text>
      <Text testID="backup-coverage">Backup coverage: {profile.backupCoverage.join(', ')}</Text>
      <Text testID="escalation-channel">Overdue escalation: {profile.escalationChannel}</Text>
      {!availability.open && availability.nextHandlingAt !== undefined && (
        <Text testID="next-handling" accessibilityRole="alert" accessibilityLiveRegion="polite">
          {lines[lines.length - 1]}
        </Text>
      )}
      <Pressable
        accessibilityRole="button"
        accessibilityLabel="I understand the service guidance"
        testID="acknowledge-guidance"
        onPress={onAcknowledge}
      >
        <Text>I understand</Text>
      </Pressable>
    </View>
  );
}
