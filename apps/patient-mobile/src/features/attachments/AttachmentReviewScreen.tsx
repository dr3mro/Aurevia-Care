/**
 * Pre-send attachment review, responsive for tablets and large text
 * (PAT-01, PAT-02, UX-01; decision 0005). Responsive handling is presentation
 * only: the current window width decides the content width and safe-area
 * padding, with no persistent clinical cache and no change to validation,
 * authorization, callbacks, labels or focus order.
 */
import { Pressable, ScrollView, StyleSheet, Text, useWindowDimensions, View } from 'react-native';
import type { StyleProp, TextStyle, ViewStyle } from 'react-native';
import * as SafeArea from 'react-native-safe-area-context';

import { windowClassFor } from '../../app/layout/useWindowClass';
import { canDownload, canSend, isBoundToUnit, validateAttachment } from './domain';
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

/** Maximum readable content width on a large window. */
const MAX_CONTENT_WIDTH = 640;
/** Baseline padding added to each safe-area inset. */
const BASE_PADDING = 16;
/** Minimum accessible touch-target size in density-independent points. */
const MIN_TOUCH_TARGET = 44;

interface Insets {
  top: number;
  right: number;
  bottom: number;
  left: number;
}
const ZERO_INSETS: Insets = { top: 0, right: 0, bottom: 0, left: 0 };

/**
 * Resolve the safe-area hook for the current runtime. Production always exposes
 * the named hook; the repository's shared headless Jest mock exports only a
 * default object, so fall back to zero insets there rather than throwing. The
 * fallback keeps headless rendering possible without a dependency or a store
 * and never changes the visible layout in the real app.
 */
const useSafeInsets: () => Insets = SafeArea.useSafeAreaInsets ?? ((): Insets => ZERO_INSETS);

const styles = StyleSheet.create({
  container: { flex: 1 },
  content: { width: '100%' },
  largeContent: { maxWidth: MAX_CONTENT_WIDTH, alignSelf: 'center' },
  card: { padding: 12, marginBottom: 12, borderWidth: 1 },
  row: { flexDirection: 'row', flexWrap: 'wrap' },
  label: { fontWeight: '600' },
  button: {
    padding: 10,
    borderWidth: 1,
    alignSelf: 'flex-start',
    minHeight: MIN_TOUCH_TARGET,
    minWidth: MIN_TOUCH_TARGET,
    justifyContent: 'center',
  },
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
 *
 * On a large window the column is capped and centred; safe-area insets become
 * padding on every edge so landscape and split-screen do not clip content.
 * Text keeps the platform scaling (up to 200%) with no fixed line count, every
 * control keeps a 44 pt floor, and the content wrapper mirrors Arabic. The
 * wrapper is not a screen-reader element, so focus order and labels are
 * unchanged from the one-pane presentation.
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
  const { width } = useWindowDimensions();
  const insets = useSafeInsets();
  const isLarge = windowClassFor(width) === 'large';
  const direction: 'rtl' | 'ltr' = isRTL ? 'rtl' : 'ltr';
  // The inset values are device-physical (notch, home indicator). Assign them
  // to logical props with an RTL swap: physical props are a defect here because
  // iOS swaps them under RTL while Android does not, so the inset would land on
  // the wrong edge in Arabic landscape. Direction is set once on the root and
  // inherited by the content wrapper.
  const safeStyle: ViewStyle = {
    paddingTop: insets.top + BASE_PADDING,
    paddingBottom: insets.bottom + BASE_PADDING,
    paddingStart: (isRTL ? insets.right : insets.left) + BASE_PADDING,
    paddingEnd: (isRTL ? insets.left : insets.right) + BASE_PADDING,
  };
  const containerStyle: StyleProp<ViewStyle> = [styles.container, { direction }, safeStyle];
  const contentStyle: StyleProp<ViewStyle> = [styles.content, isLarge ? styles.largeContent : null];
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
      <View testID="attachment-review-content" style={contentStyle}>
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
              <Text
                key={error}
                testID={`error-${item.id}-${error}`}
                style={[styles.error, textStyle]}
              >
                {text(ERROR_KEY[error])}
              </Text>
            ))}
            {downloadAllowed.get(item.id) === true ? (
              <Pressable
                testID={`download-${item.id}`}
                accessibilityRole="button"
                accessibilityLabel={text('download')}
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
                  accessibilityRole="button"
                  accessibilityLabel={text('remove')}
                  style={styles.button}
                  onPress={() => onRemove(item.id)}
                >
                  <Text style={textStyle}>{text('remove')}</Text>
                </Pressable>
                <Pressable
                  testID={`replace-${item.id}`}
                  accessibilityRole="button"
                  accessibilityLabel={text('replace')}
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
          accessibilityRole="button"
          accessibilityLabel={text('send')}
          accessibilityState={{ disabled: !sendAllowed }}
          style={[styles.button, !sendAllowed ? styles.disabled : null]}
          onPress={send}
        >
          <Text style={textStyle}>{text('send')}</Text>
        </Pressable>
      </View>
    </ScrollView>
  );
}
