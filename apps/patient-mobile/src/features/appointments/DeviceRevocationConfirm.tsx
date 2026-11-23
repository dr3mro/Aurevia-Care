/**
 * Explicit confirmation step for one device revocation (PAT-01, PRD-03).
 *
 * A destructive command is never issued from the list row alone: this step
 * holds the chosen device until the patient presses Confirm. It is purely
 * presentational, carries an optional generic outcome line that never contains
 * a token, a response body or clinical detail, and disables Confirm while the
 * command is in flight.
 */
import { StyleSheet, Text, View } from 'react-native';

import { NoticeBox, PrimaryButton, screenText, SCREEN_TOKENS } from './patientDesign';
import type { Locale } from './patientDesign';

/** Props for {@link DeviceRevocationConfirm}. */
export interface DeviceRevocationConfirmProps {
  /** Organization-local id of the device awaiting a decision; used only as a test id. */
  readonly deviceId: string;
  /** Active display locale. */
  readonly locale: Locale;
  /** Localized prompt heading. */
  readonly title: string;
  /** Localized consequence line. */
  readonly body: string;
  /** Localized label that keeps the device. */
  readonly cancelLabel: string;
  /** Localized label that sends the revocation. */
  readonly confirmLabel: string;
  /** Generic outcome line, or null while no failure is shown. */
  readonly errorMessage: string | null;
  /** Disables Confirm while one request is in flight. */
  readonly pending: boolean;
  /** Discards the step and sends nothing. */
  readonly onCancel: () => void;
  /** Sends exactly one revocation for this device. */
  readonly onConfirm: () => void;
}

/**
 * Ask for an explicit choice before revoking one device.
 *
 * @param props prompt copy, outcome line, pending state and both handlers
 * @returns the confirmation step with a labelled Cancel and Confirm control
 */
export function DeviceRevocationConfirm({
  deviceId,
  locale,
  title,
  body,
  cancelLabel,
  confirmLabel,
  errorMessage,
  pending,
  onCancel,
  onConfirm,
}: DeviceRevocationConfirmProps): React.JSX.Element {
  return (
    <View testID={`device-revocation-${deviceId}`} style={styles.container}>
      <NoticeBox
        testID="device-revocation-prompt"
        variant="warning"
        locale={locale}
        lines={[title, body, screenText(locale, 'revokeDevice')]}
      />
      {errorMessage === null ? null : (
        <Text testID="device-revocation-error" accessibilityLanguage={locale} style={styles.error}>
          {errorMessage}
        </Text>
      )}
      <PrimaryButton
        testID="device-revoke-cancel"
        label={cancelLabel}
        locale={locale}
        onPress={onCancel}
      />
      <PrimaryButton
        testID="device-revoke-confirm"
        label={confirmLabel}
        locale={locale}
        disabled={pending}
        onPress={onConfirm}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  container: { width: '100%' },
  error: { color: SCREEN_TOKENS.color.warningText, fontSize: SCREEN_TOKENS.font.label },
});
