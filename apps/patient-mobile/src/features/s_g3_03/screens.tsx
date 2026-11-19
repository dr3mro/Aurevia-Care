/**
 * Small, controlled screens for privacy-safe enrollment, recovery, lockout,
 * step-up re-authentication and support requests (PAT-03, UX-01). Each screen
 * is presentational: the parent owns state and the server owns all decisions.
 */
import React from 'react';
import { Pressable, StyleSheet, Text, TextInput, View } from 'react-native';

import {
  formatLockoutCountdown,
  buildSubmissionNotice,
  getRowDirection,
  getTextAlign,
  normalizeInvitationCode,
  validateInvitationCode,
  validateSupportRequest,
  type EnrollmentStatus,
  type StepUpReason,
  type SupportCategory,
} from './model';

/** Labels for the enrollment screen, pre-localized by the caller. */
export interface EnrollmentStrings {
  codeLabel: string;
  codeHint: string;
  submit: string;
  expired: string;
  reused: string;
  locked: string;
  invalid: string;
}

/** Props for {@link EnrollmentScreen}. */
export interface EnrollmentScreenProps {
  status: EnrollmentStatus;
  code: string;
  onCodeChange: (value: string) => void;
  onSubmit: (normalizedCode: string) => void;
  strings: EnrollmentStrings;
  isRTL: boolean;
}

/** Enter a single-use invitation code. The code is never echoed in an error. */
export function EnrollmentScreen({
  status,
  code,
  onCodeChange,
  onSubmit,
  strings,
  isRTL,
}: EnrollmentScreenProps): React.JSX.Element {
  const valid = validateInvitationCode(normalizeInvitationCode(code)).valid;
  const message =
    status === 'expired'
      ? strings.expired
      : status === 'reused'
        ? strings.reused
        : status === 'locked'
          ? strings.locked
          : status === 'error'
            ? strings.invalid
            : null;
  return (
    <View style={styles.screen}>
      <Text accessibilityRole="header" style={{ textAlign: getTextAlign(isRTL) }}>
        {strings.codeLabel}
      </Text>
      <TextInput
        accessibilityLabel={strings.codeLabel}
        accessibilityHint={strings.codeHint}
        autoCapitalize="characters"
        secureTextEntry
        value={code}
        onChangeText={onCodeChange}
        style={{ textAlign: getTextAlign(isRTL) }}
      />
      {message === null ? null : (
        <Text accessibilityRole="alert" accessibilityLiveRegion="polite">
          {message}
        </Text>
      )}
      <Pressable
        accessibilityRole="button"
        accessibilityLabel={strings.submit}
        accessibilityState={{ disabled: !valid }}
        disabled={!valid}
        onPress={() => onSubmit(normalizeInvitationCode(code))}
        style={styles.button}>
        <Text>{strings.submit}</Text>
      </Pressable>
    </View>
  );
}

/** Labels for the recovery screen. */
export interface RecoveryStrings {
  codeLabel: string;
  verificationLabel: string;
  submit: string;
  expired: string;
  reused: string;
  locked: string;
  invalid: string;
}

/** Props for {@link RecoveryScreen}. */
export interface RecoveryScreenProps {
  status: EnrollmentStatus;
  code: string;
  verification: string;
  onCodeChange: (value: string) => void;
  onVerificationChange: (value: string) => void;
  onSubmit: (code: string, verification: string) => void;
  strings: RecoveryStrings;
  isRTL: boolean;
}

/** Recover lost access with a single-use code and re-verified facts. */
export function RecoveryScreen({
  status,
  code,
  verification,
  onCodeChange,
  onVerificationChange,
  onSubmit,
  strings,
  isRTL,
}: RecoveryScreenProps): React.JSX.Element {
  const ready = validateInvitationCode(normalizeInvitationCode(code)).valid && verification.trim().length > 0;
  const message =
    status === 'expired'
      ? strings.expired
      : status === 'reused'
        ? strings.reused
        : status === 'locked'
          ? strings.locked
          : status === 'error'
            ? strings.invalid
            : null;
  return (
    <View style={styles.screen}>
      <TextInput
        accessibilityLabel={strings.codeLabel}
        value={code}
        onChangeText={onCodeChange}
        secureTextEntry
        style={{ textAlign: getTextAlign(isRTL) }}
      />
      <TextInput
        accessibilityLabel={strings.verificationLabel}
        value={verification}
        onChangeText={onVerificationChange}
        secureTextEntry
        style={{ textAlign: getTextAlign(isRTL) }}
      />
      {message === null ? null : (
        <Text accessibilityRole="alert" accessibilityLiveRegion="polite">
          {message}
        </Text>
      )}
      <Pressable
        accessibilityRole="button"
        accessibilityLabel={strings.submit}
        accessibilityState={{ disabled: !ready }}
        disabled={!ready}
        onPress={() => onSubmit(normalizeInvitationCode(code), verification.trim())}
        style={styles.button}>
        <Text>{strings.submit}</Text>
      </Pressable>
    </View>
  );
}

/** Labels for the lockout screen. */
export interface LockoutStrings {
  title: string;
  support: string;
  retry: string;
  timerLabel: string;
}

/** Props for {@link LockoutScreen}. */
export interface LockoutScreenProps {
  remainingSeconds: number;
  onSupport: () => void;
  onRetry?: () => void;
  strings: LockoutStrings;
  isRTL: boolean;
}

/** Show a lockout countdown and a route to the support channel. */
export function LockoutScreen({
  remainingSeconds,
  onSupport,
  onRetry,
  strings,
  isRTL,
}: LockoutScreenProps): React.JSX.Element {
  return (
    <View style={styles.screen}>
      <Text accessibilityRole="header" style={{ textAlign: getTextAlign(isRTL) }}>
        {strings.title}
      </Text>
      <Text accessibilityRole="timer" accessibilityLabel={strings.timerLabel}>
        {formatLockoutCountdown(remainingSeconds)}
      </Text>
      <Pressable accessibilityRole="button" accessibilityLabel={strings.support} onPress={onSupport} style={styles.button}>
        <Text>{strings.support}</Text>
      </Pressable>
      {onRetry === undefined ? null : (
        <Pressable accessibilityRole="button" accessibilityLabel={strings.retry} onPress={onRetry} style={styles.button}>
          <Text>{strings.retry}</Text>
        </Pressable>
      )}
    </View>
  );
}

/** Labels for the step-up prompt. */
export interface StepUpStrings {
  unlink: string;
  changeContact: string;
  confirm: string;
  cancel: string;
}

/** Props for {@link StepUpPrompt}. */
export interface StepUpPromptProps {
  reason: StepUpReason;
  onConfirm: () => void;
  onCancel: () => void;
  strings: StepUpStrings;
  isRTL: boolean;
}

/**
 * Gate a sensitive change on renewed authentication. This prompt is a UI gate
 * only: the server must reject the change without a fresh auth assertion.
 */
export function StepUpPrompt({
  reason,
  onConfirm,
  onCancel,
  strings,
  isRTL,
}: StepUpPromptProps): React.JSX.Element {
  const explanation = reason === 'unlink_chart' ? strings.unlink : strings.changeContact;
  return (
    <View accessible accessibilityRole="alert" style={styles.screen}>
      <Text style={{ textAlign: getTextAlign(isRTL) }}>{explanation}</Text>
      <View style={{ flexDirection: getRowDirection(isRTL) }}>
        <Pressable accessibilityRole="button" accessibilityLabel={strings.confirm} onPress={onConfirm} style={styles.button}>
          <Text>{strings.confirm}</Text>
        </Pressable>
        <Pressable accessibilityRole="button" accessibilityLabel={strings.cancel} onPress={onCancel} style={styles.button}>
          <Text>{strings.cancel}</Text>
        </Pressable>
      </View>
    </View>
  );
}

/** Labels for the support request screen. */
export interface SupportStrings {
  title: string;
  messageLabel: string;
  submit: string;
  category: string;
  invalidCategory: string;
  empty: string;
  tooLong: string;
  phi: string;
}

/** Props for {@link SupportRequestScreen}. */
export interface SupportRequestScreenProps {
  category: SupportCategory | null;
  message: string;
  noticeVisible: boolean;
  onCategoryChange: (category: SupportCategory) => void;
  onMessageChange: (message: string) => void;
  onSubmit: () => void;
  strings: SupportStrings;
  isRTL: boolean;
}

/** Open an identity-dispute, accessibility or record-access support request. */
export function SupportRequestScreen({
  category,
  message,
  noticeVisible,
  onCategoryChange,
  onMessageChange,
  onSubmit,
  strings,
  isRTL,
}: SupportRequestScreenProps): React.JSX.Element {
  const validation = validateSupportRequest({ category, message });
  const error = validation.ok
    ? null
    : validation.reason === 'category'
      ? strings.invalidCategory
      : validation.reason === 'empty'
        ? strings.empty
        : validation.reason === 'too_long'
          ? strings.tooLong
          : strings.phi;
  return (
    <View style={styles.screen}>
      <Text accessibilityRole="header" style={{ textAlign: getTextAlign(isRTL) }}>
        {strings.title}
      </Text>
      <View accessibilityRole="radiogroup" accessibilityLabel={strings.category}>
        {(['identity_dispute', 'accessibility', 'record_access'] as const).map((option) => (
          <Pressable
            key={option}
            accessibilityRole="radio"
            accessibilityLabel={option}
            accessibilityState={{ checked: category === option }}
            onPress={() => onCategoryChange(option)}
            style={styles.button}>
            <Text>{option}</Text>
          </Pressable>
        ))}
      </View>
      <TextInput
        accessibilityLabel={strings.messageLabel}
        value={message}
        onChangeText={onMessageChange}
        multiline
        style={{ textAlign: getTextAlign(isRTL) }}
      />
      {error === null ? null : (
        <Text accessibilityRole="alert" accessibilityLiveRegion="polite">
          {error}
        </Text>
      )}
      <Pressable
        accessibilityRole="button"
        accessibilityLabel={strings.submit}
        accessibilityState={{ disabled: !validation.ok }}
        disabled={!validation.ok}
        onPress={onSubmit}
        style={styles.button}>
        <Text>{strings.submit}</Text>
      </Pressable>
      {noticeVisible ? <Text accessibilityRole="alert">{buildSubmissionNotice()}</Text> : null}
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, padding: 16, gap: 8 },
  button: { minHeight: 44, minWidth: 44, justifyContent: 'center' },
});
