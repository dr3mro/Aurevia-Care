import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import type { StyleProp, TextStyle, ViewStyle } from 'react-native';

import {
  canDownload,
  canSend,
  isBoundToUnit,
  validateAttachment,
} from './domain';
import type { CareUnitRef, PendingAttachment, ValidationError } from './domain';
import { attachmentText } from './messages';
import type { AttachmentMessageKey, Locale } from './messages';

/** Props for the synthetic attachment review surface. */
export interface AttachmentReviewScreenProps {
  /** Display locale; Arabic mirrors the layout. */
  locale: Locale;
  /** Verified recipient unit shown before send. */
  activeUnit: CareUnitRef;
  /** Identifier of the active unit, used for wrong-unit filtering. */
  activeUnitId: string;
  /** Pending synthetic attachments. */
  pending: readonly PendingAttachment[];
  /** Server-derived fact that an authorized publication exists. */
  publicationAuthorized: boolean;
  /** Remove a failed draft. */
  onRemove: (id: string) => void;
  /** Replace a failed draft. */
  onReplace: (id: string) => void;
  /** Obtain a mediated download handle. */
  onDownload: (id: string) => void;
  /** Explicitly send the named pending files. */
  onSend: (ids: readonly string[]) => void;
}

const styles = StyleSheet.create({
  container: { flex: 1, padding: 16 },
  card: { padding: 12, marginBottom: 12, borderWidth: 1 },
  row: { flexDirection: 'row', flexWrap: 'wrap' },
  label: { fontWeight: '600' },
  button: { padding: 10, borderWidth: 1, alignSelf: 'flex-start' },
  disabled: { opacity: 0.4 },
  error: { color: '#a11' },
});

const SCAN_KEY: Record<PendingAttachment['scanState'], AttachmentMessageKey> = {
  quarantined: 'quarantined',
  clean: 'clean',
  failed: 'failed',
};

const ERROR_KEY: Record<ValidationError, AttachmentMessageKey> = {
  unsupportedType: 'unsupportedType',
  magicMismatch: 'magicMismatch',
  sizeOutOfRange: 'sizeOutOfRange',
  checksumMismatch: 'checksumMismatch',
};

/**
 * Render the pre-send review for patient-supplied attachments.
 *
 * The screen never receives payload bytes to display, never constructs a URL
 * and never logs clinical content. A file bound to another unit is filtered
 * out before rendering, so a wrong-unit file cannot be downloaded from here.
 */
export function AttachmentReviewScreen({
  locale,
  activeUnit,
  activeUnitId,
  pending,
  publicationAuthorized,
  onRemove,
  onReplace,
  onDownload,
  onSend,
}: AttachmentReviewScreenProps): React.JSX.Element {
  const isRTL = locale === 'ar';
  const text = (key: AttachmentMessageKey): string => attachmentText(locale, key);
  const containerStyle: StyleProp<ViewStyle> = [styles.container, { direction: isRTL ? 'rtl' : 'ltr' }];
  const textStyle: StyleProp<TextStyle> = { textAlign: isRTL ? 'right' : 'left' };

  // Business rules stay out of JSX: scope, authorize and decide first.
  const visible = pending.filter((item) => isBoundToUnit(item, activeUnitId));
  const hiddenCount = pending.length - visible.length;
  const visibleIds = visible.map((item) => item.id);
  const sendAllowed = canSend(visible);
  const downloadAllowed = new Map(
    visible.map((item) => [item.id, canDownload(item, activeUnitId, publicationAuthorized)]),
  );
  const errors = new Map(visible.map((item) => [item.id, validateAttachment(item)]));

  // The Pressable is disabled unless canSend holds; the API re-validates every
  // send, so there is no second hidden gate to keep in sync here.
  const send = (): void => onSend(visibleIds);

  return (
    <ScrollView testID="attachment-review" style={containerStyle}>
      <Text testID="synthetic-notice" style={textStyle}>
        {text('syntheticNotice')}
      </Text>
      <View testID="recipient-unit" style={styles.card}>
        <Text testID="recipient-heading" style={[styles.label, textStyle]}>
          {text('recipient')}
        </Text>
        <Text testID="recipient-organization" style={textStyle}>
          {text('organization')}: {activeUnit.organizationId}
        </Text>
        <Text testID="recipient-facility" style={textStyle}>
          {text('facility')}: {activeUnit.facilityCode}
        </Text>
        <Text testID="recipient-unit-name" style={textStyle}>
          {text('unit')}: {activeUnit.unitName}
        </Text>
      </View>

      <Text testID="pending-heading" style={[styles.label, textStyle]}>
        {text('pendingFiles')}
      </Text>
      <Text testID="send-hint" style={textStyle}>
        {text('sendHint')}
      </Text>
      {hiddenCount > 0 ? (
        <Text testID="hidden-other-unit" style={textStyle}>
          {text('hiddenOtherUnit')}
        </Text>
      ) : null}
      {visible.length === 0 ? (
        <Text testID="no-files" style={textStyle}>
          {text('noFiles')}
        </Text>
      ) : null}

      {visible.map((item, index) => (
        <View key={item.id} testID={`file-${item.id}`} style={styles.card}>
          <Text testID={`file-label-${item.id}`} style={[styles.label, textStyle]}>
            {text('file')} {index + 1}
          </Text>
          <Text testID={`scan-${item.id}`} style={textStyle}>
            {text('scanState')}: {text(SCAN_KEY[item.scanState])}
          </Text>
          <Text testID={`supplied-${item.id}`} style={textStyle}>
            {text('patientSupplied')}
          </Text>
          <Text testID={`provenance-${item.id}`} style={textStyle}>
            {text('provenance')}: {text('checksum')} {item.declaredSha256}; {text('size')}{' '}
            {item.bytes.length}; {text('type')} {item.declaredMimeType}; {text('boundUnit')}{' '}
            {item.unitId}; {text('uploadedAt')} {item.uploadedAt}
          </Text>
          {errors.get(item.id)!.map((error) => (
            <Text key={error} testID={`error-${item.id}-${error}`} style={[styles.error, textStyle]}>
              {text(ERROR_KEY[error])}
            </Text>
          ))}
          {downloadAllowed.get(item.id) === true ? (
            <Pressable
              testID={`download-${item.id}`}
              style={styles.button}
              onPress={() => onDownload(item.id)}
            >
              <Text style={textStyle}>{text('download')}</Text>
            </Pressable>
          ) : (
            <Text testID={`download-unavailable-${item.id}`} style={textStyle}>
              {text('notAvailable')}
            </Text>
          )}
          {item.scanState === 'failed' ? (
            <View style={styles.row}>
              <Pressable
                testID={`remove-${item.id}`}
                style={styles.button}
                onPress={() => onRemove(item.id)}
              >
                <Text style={textStyle}>{text('remove')}</Text>
              </Pressable>
              <Pressable
                testID={`replace-${item.id}`}
                style={styles.button}
                onPress={() => onReplace(item.id)}
              >
                <Text style={textStyle}>{text('replace')}</Text>
              </Pressable>
            </View>
          ) : null}
        </View>
      ))}

      <Pressable
        testID="send"
        disabled={!sendAllowed}
        accessibilityState={{ disabled: !sendAllowed }}
        style={[styles.button, !sendAllowed ? styles.disabled : null]}
        onPress={send}
      >
        <Text style={textStyle}>{text('send')}</Text>
      </Pressable>
    </ScrollView>
  );
}
