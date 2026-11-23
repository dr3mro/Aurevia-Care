/**
 * Shared presentation language for the three patient screens (PAT-01, PAT-02,
 * MSG-06, UX-01).
 *
 * This module is the token-only style boundary and the bilingual label source
 * for the appointments, reports and conversations screens. It lives inside the
 * delivery scope because a repository-wide `src/theme`/`src/components` module
 * is owned outside BUILD-71; moving it there is a separate, owner-approved
 * change. It holds no network, storage or clinical decision logic.
 *
 * Values come from the approved token contract (docs/design/token-contract.md,
 * contract version 1.0.0). The card corner uses the contract `radius.md`
 * (8 px) rather than the ~14 px in the predicted picture: the approved-design
 * README says the token contract wins where they differ.
 */
import type { ReactNode } from 'react';
import { I18nManager, Pressable, StyleSheet, Text, View } from 'react-native';
import type { TextStyle, ViewStyle } from 'react-native';

import { appText } from '../../app/messages';
import type { Locale } from '../../app/messages';
import { TAB_DEFINITIONS } from '../../app/tabs';
import type { TabId } from '../../app/tabs';

export type { Locale, TabId };

/** Measured presentation values from the v1 token contract. */
export const SCREEN_TOKENS = {
  color: {
    surfaceDefault: '#ffffff',
    surfaceRaised: '#f7f9fc',
    surfaceSunken: '#eef1f6',
    textPrimary: '#152033',
    textSecondary: '#4a5768',
    textInverse: '#ffffff',
    borderDefault: '#6b7280',
    borderSubtle: '#c3cbd8',
    actionPrimaryBackground: '#0b5cab',
    actionPrimaryText: '#ffffff',
    warningSurface: '#fff4d6',
    warningBorder: '#8a6100',
    warningText: '#6b4a00',
    successSurface: '#e8f7ee',
    successBorder: '#1f7a44',
    successText: '#14532d',
    infoSurface: '#e8f1fb',
    infoBorder: '#1b5fa8',
    infoText: '#103f77',
  },
  space: { zero: 0, one: 4, two: 8, three: 12, four: 16, five: 20, six: 24, eight: 32 },
  radius: { sm: 4, md: 8, card: 8, pill: 9999 },
  border: { thin: 1, thick: 2 },
  font: {
    caption: 12,
    label: 13,
    body: 16,
    headingSm: 20,
    headingMd: 24,
    numericLg: 24,
    regular: '400',
    medium: '500',
    semibold: '600',
  },
  /** Patient-app touch target: the larger of the 44 pt / 48 dp platform pair. */
  size: { target: 48, icon: 20 },
} as const;

/** Every screen-level message key; both bundles carry exactly this set. */
export const SCREEN_TEXT_KEYS = [
  'visits',
  'appointmentsTitle',
  'appointmentsSubtitle',
  'reportsTitle',
  'reportsSubtitle',
  'messagesTitle',
  'messagesSubtitle',
  'pleaseConfirm',
  'confirmed',
  'completed',
  'confirmAppointment',
  'viewDetails',
  'revokeDevice',
  'appointmentDetails',
  'back',
  'revokeConfirmTitle',
  'revokeConfirmBody',
  'revokeCancel',
  'revokeConfirm',
  'revokeError',
  'noAppointments',
  'noReports',
  'revision',
  'corrected',
  'downloadPdf',
  'downloadCheck',
  'withdrawnTitle',
  'withdrawnBody',
  'result',
  'reference',
  'serviceHours',
  'responseTarget',
  'to',
  'send',
  'unsentNote',
  'readOnly',
  'acknowledge',
  'patient',
  'unit',
] as const;

/** One screen-level message key. */
export type ScreenTextKey = (typeof SCREEN_TEXT_KEYS)[number];

/** Bilingual patient-facing strings; no clinical values or raw HTML are present. */
const SCREEN_TEXT: Record<Locale, Record<ScreenTextKey, string>> = {
  en: {
    visits: 'Visits',
    appointmentsTitle: 'Appointments',
    appointmentsSubtitle: "Times are shown in the clinic's time zone",
    reportsTitle: 'Reports',
    reportsSubtitle: 'Only reports your care team has released to you',
    messagesTitle: 'Messages',
    messagesSubtitle: 'Secure messages with your care unit',
    pleaseConfirm: 'Please confirm',
    confirmed: 'Confirmed',
    completed: 'Completed',
    confirmAppointment: 'Confirm appointment',
    viewDetails: 'View details',
    revokeDevice: 'Revoke device',
    appointmentDetails: 'Appointment details',
    back: 'Back',
    revokeConfirmTitle: 'Revoke this device?',
    revokeConfirmBody: 'It will stop receiving appointment updates. You can register it again later.',
    revokeCancel: 'Keep device',
    revokeConfirm: 'Revoke device',
    revokeError: 'The device could not be revoked. Please try again.',
    noAppointments: 'No appointments',
    noReports: 'No released reports',
    revision: 'Revision',
    corrected: 'Corrected',
    downloadPdf: 'Download PDF',
    downloadCheck: 'Each download checks that you are linked to this chart and that the release is still active.',
    withdrawnTitle: 'Report withdrawn',
    withdrawnBody: 'This report is no longer available. Contact the clinic if you have questions.',
    result: 'Result',
    reference: 'reference',
    serviceHours: 'Service hours',
    responseTarget: 'Reply target',
    to: 'To',
    send: 'Send',
    unsentNote: 'Nothing is sent until you press Send.',
    readOnly: 'This conversation is read only.',
    acknowledge: 'Acknowledge receipt',
    patient: 'you',
    unit: 'care unit',
  },
  ar: {
    visits: 'المواعيد',
    appointmentsTitle: 'المواعيد',
    appointmentsSubtitle: 'الأوقات معروضة وفق التوقيت المحلي للعيادة',
    reportsTitle: 'التقارير',
    reportsSubtitle: 'تقارير أتاحها لك فريق الرعاية فقط',
    messagesTitle: 'الرسائل',
    messagesSubtitle: 'رسائل آمنة مع وحدة الرعاية الخاصة بك',
    pleaseConfirm: 'يرجى التأكيد',
    confirmed: 'مؤكدة',
    completed: 'مكتملة',
    confirmAppointment: 'تأكيد الموعد',
    viewDetails: 'عرض التفاصيل',
    revokeDevice: 'إلغاء الجهاز',
    appointmentDetails: 'تفاصيل الموعد',
    back: 'رجوع',
    revokeConfirmTitle: 'إلغاء هذا الجهاز؟',
    revokeConfirmBody: 'لن يستقبل الجهاز تحديثات المواعيد. يمكنك تسجيله مرة أخرى لاحقاً.',
    revokeCancel: 'الاحتفاظ بالجهاز',
    revokeConfirm: 'إلغاء الجهاز',
    revokeError: 'تعذّر إلغاء الجهاز. يُرجى المحاولة مرة أخرى.',
    noAppointments: 'لا توجد مواعيد',
    noReports: 'لا توجد تقارير مُصدرّة',
    revision: 'النسخة',
    corrected: 'مصححة',
    downloadPdf: 'تنزيل PDF',
    downloadCheck: 'يتحقق كل تنزيل من ارتباطك بهذا الملف من سجل المريض ومن سريان الإتاحة.',
    withdrawnTitle: 'تم سحب التقرير',
    withdrawnBody: 'لم يعد هذا التقرير متاحًا. اتصل بالعيادة إذا كانت لديك أسئلة.',
    result: 'النتيجة',
    reference: 'المدى المرجعي',
    serviceHours: 'ساعات العمل',
    responseTarget: 'الرد المتوقع',
    to: 'إلى',
    send: 'إرسال',
    unsentNote: 'لا يُرسل شيء حتى تضغط زر إرسال.',
    readOnly: 'هذه المحادثة للقراءة فقط.',
    acknowledge: 'تأكيد الاستلام',
    patient: 'أنت',
    unit: 'وحدة الرعاية',
  },
};

/**
 * Look up one screen label for the active locale.
 *
 * @param locale active display locale
 * @param key screen message key
 * @returns the translated string
 */
export function screenText(locale: Locale, key: ScreenTextKey): string {
  return SCREEN_TEXT[locale][key];
}

/**
 * Map the native layout direction to a supported display locale (UX-01).
 *
 * @param rtl whether the device uses a right-to-left layout
 * @returns Arabic for RTL, English otherwise
 */
export function localeFromDirection(rtl: boolean): Locale {
  return rtl ? 'ar' : 'en';
}

/**
 * Resolve the locale a screen should render with.
 *
 * The app shell sets the native direction at startup, so a screen mounted
 * without an explicit locale still renders Arabic on an Arabic device. An
 * explicit prop wins, which keeps the component deterministic in tests and in
 * embedded previews.
 *
 * @param locale explicit locale from the host, when supplied
 * @returns the effective display locale
 */
export function resolveScreenLocale(locale?: Locale): Locale {
  return locale ?? localeFromDirection(I18nManager.isRTL);
}

/**
 * Build the accessible label for an instruction acknowledgment.
 *
 * The exact revision is part of the label (MSG-05): an acknowledgment for a
 * superseded revision must never be confused with the current one.
 *
 * @param locale active display locale
 * @param revision immutable instruction revision being acknowledged
 * @returns a screen-reader label that names the revision
 */
export function acknowledgeLabel(locale: Locale, revision: number): string {
  return locale === 'en'
    ? `${screenText(locale, 'acknowledge')} of instruction revision ${revision}`
    : `${screenText(locale, 'acknowledge')} النسخة ${revision}`;
}

/** Status pill roles; each carries a distinct word and glyph as well as a colour. */
export type PillVariant = 'warning' | 'success' | 'neutral' | 'info';

const PILL_GLYPH: Record<PillVariant, string> = {
  warning: '!',
  success: '✓',
  neutral: '•',
  info: 'i',
};

const PILL_FILL: Record<PillVariant, ViewStyle> = {
  warning: { backgroundColor: SCREEN_TOKENS.color.warningSurface, borderColor: SCREEN_TOKENS.color.warningBorder },
  success: { backgroundColor: SCREEN_TOKENS.color.successSurface, borderColor: SCREEN_TOKENS.color.successBorder },
  neutral: { backgroundColor: SCREEN_TOKENS.color.surfaceSunken, borderColor: SCREEN_TOKENS.color.borderDefault },
  info: { backgroundColor: SCREEN_TOKENS.color.infoSurface, borderColor: SCREEN_TOKENS.color.infoBorder },
};

const PILL_TEXT: Record<PillVariant, TextStyle> = {
  warning: { color: SCREEN_TOKENS.color.warningText },
  success: { color: SCREEN_TOKENS.color.successText },
  neutral: { color: SCREEN_TOKENS.color.textSecondary },
  info: { color: SCREEN_TOKENS.color.infoText },
};

const TAB_GLYPH: Record<TabId, string> = {
  appointments: '▦',
  reports: '▤',
  messages: '◻',
  account: '♙',
};

/** Props for a status pill. */
export interface StatusPillProps {
  readonly testID: string;
  readonly variant: PillVariant;
  readonly label: string;
  readonly locale: Locale;
}

/** A short status word with a shape-distinct glyph; colour is reinforcement only. */
export function StatusPill({ testID, variant, label, locale }: StatusPillProps): React.JSX.Element {
  return (
    <View testID={testID} accessibilityLabel={label} accessibilityLanguage={locale} style={[styles.pill, PILL_FILL[variant]]}>
      <Text accessibilityLanguage={locale} style={[styles.pillGlyph, PILL_TEXT[variant]]}>{PILL_GLYPH[variant]}</Text>
      <Text accessibilityLanguage={locale} style={[styles.pillLabel, PILL_TEXT[variant]]}>{label}</Text>
    </View>
  );
}

/** Props for a card. */
export interface CardProps {
  readonly testID: string;
  readonly children: ReactNode;
  readonly dashed?: boolean;
}

/** A raised content card; a dashed variant marks a withdrawn/unavailable record. */
export function Card({ testID, children, dashed = false }: CardProps): React.JSX.Element {
  return (
    <View testID={testID} style={[styles.card, dashed ? styles.cardDashed : null]}>
      {children}
    </View>
  );
}

/** Props for a notice box. */
export interface NoticeBoxProps {
  readonly testID: string;
  readonly variant: 'info' | 'warning';
  readonly locale: Locale;
  /** One translated string per line, each exposed to screen readers. */
  readonly lines: readonly string[];
}

const NOTICE_FILL: Record<NoticeBoxProps['variant'], ViewStyle> = {
  info: { backgroundColor: SCREEN_TOKENS.color.infoSurface, borderColor: SCREEN_TOKENS.color.infoBorder },
  warning: { backgroundColor: SCREEN_TOKENS.color.warningSurface, borderColor: SCREEN_TOKENS.color.warningBorder },
};

/** An informational or cautionary box whose text carries the meaning. */
export function NoticeBox({ testID, variant, locale, lines }: NoticeBoxProps): React.JSX.Element {
  return (
    <View testID={testID} accessibilityLabel={testID} style={[styles.notice, NOTICE_FILL[variant]]}>
      {lines.map((line, index) => (
        <Text key={`${testID}-${index}`} accessibilityLabel={line} accessibilityLanguage={locale} style={styles.noticeText}>
          {line}
        </Text>
      ))}
    </View>
  );
}

/** Props for a full-width primary command. */
export interface PrimaryButtonProps {
  readonly testID: string;
  readonly label: string;
  readonly locale: Locale;
  readonly onPress: () => void;
  readonly disabled?: boolean;
}

/** A touch-first primary command with a word label, not colour alone. */
export function PrimaryButton({ testID, label, locale, onPress, disabled = false }: PrimaryButtonProps): React.JSX.Element {
  return (
    <Pressable
      testID={testID}
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityLanguage={locale}
      accessibilityState={{ disabled }}
      disabled={disabled}
      onPress={onPress}
      style={[styles.primaryButton, disabled ? styles.primaryButtonDisabled : null]}>
      <Text accessibilityLanguage={locale} style={[styles.primaryButtonText, disabled ? styles.primaryButtonTextDisabled : null]}>
        {label}
      </Text>
    </Pressable>
  );
}

/** Props for one message bubble. */
export interface MessageBubbleProps {
  readonly testID: string;
  /** True for a patient-authored message; it sits at the inline start edge. */
  readonly own: boolean;
  readonly label: string;
  readonly body: string;
  readonly time: string;
  readonly locale: Locale;
}

/** A message bubble; `alignSelf` mirrors with the native layout direction. */
export function MessageBubble({ testID, own, label, body, time, locale }: MessageBubbleProps): React.JSX.Element {
  return (
    <View testID={testID} style={[styles.bubble, own ? styles.ownBubble : styles.otherBubble]}>
      <Text accessibilityLabel={label} accessibilityLanguage={locale} style={styles.bubbleBody}>{body}</Text>
      <Text accessibilityLabel={`time ${time}`} accessibilityLanguage={locale} style={styles.bubbleTime}>{time}</Text>
    </View>
  );
}

/** Props for the screen chrome shared by all four patient tabs. */
export interface ScreenFrameProps {
  readonly testID: string;
  readonly locale: Locale;
  readonly title: string;
  readonly subtitle: string;
  readonly children: ReactNode;
  /** Render the four-tab bar for a standalone phone-frame review. */
  readonly showTabBar?: boolean;
  /** Tab highlighted in the standalone frame. */
  readonly activeTab?: TabId;
  /** Optional tab handler; the mounted app uses the native navigator instead. */
  readonly onTab?: ((tab: TabId) => void) | undefined;
  /** Optional label on the frame root for screen readers. */
  readonly rootLabel?: string;
  /** Explicit layout direction; defaults to the direction implied by the locale. */
  readonly direction?: 'ltr' | 'rtl';
}

/** Large title, one-line subtitle, content and an optional standalone tab bar. */
export function ScreenFrame({
  testID,
  locale,
  title,
  subtitle,
  children,
  showTabBar = false,
  activeTab = 'appointments',
  onTab,
  rootLabel,
  direction,
}: ScreenFrameProps): React.JSX.Element {
  const layoutDirection = direction ?? (locale === 'ar' ? 'rtl' : 'ltr');
  return (
    <View
      testID={testID}
      accessibilityLabel={rootLabel}
      style={[styles.screen, { direction: layoutDirection }]}>
      <Text testID={`${testID}-title`} accessibilityRole="header" accessibilityLanguage={locale} style={styles.title}>
        {title}
      </Text>
      <Text accessibilityLabel="screen-subtitle" accessibilityLanguage={locale} style={styles.subtitle}>
        {subtitle}
      </Text>
      {children}
      {showTabBar ? <BottomTabBar locale={locale} activeTab={activeTab} onTab={onTab} /> : null}
    </View>
  );
}

/** Props for the standalone bottom tab bar. */
interface BottomTabBarProps {
  readonly locale: Locale;
  readonly activeTab: TabId;
  readonly onTab?: ((tab: TabId) => void) | undefined;
}

/** Four icon-and-label tabs in the contract order, mirrored by the locale. */
function BottomTabBar({ locale, activeTab, onTab }: BottomTabBarProps): React.JSX.Element {
  return (
    <View accessibilityLabel="bottom tab bar" accessibilityLanguage={locale} style={styles.tabBar}>
      {TAB_DEFINITIONS.map((tab) => {
        const label = tab.id === 'appointments' ? screenText(locale, 'visits') : appText(locale, tab.labelKey);
        const active = tab.id === activeTab;
        return (
          <Pressable
            key={tab.id}
            testID={`tab-button-${tab.id}`}
            accessibilityRole="button"
            accessibilityLabel={`${label} tab`}
            accessibilityLanguage={locale}
            accessibilityState={{ selected: active }}
            onPress={() => onTab?.(tab.id)}
            style={[styles.tab, active ? styles.tabActive : null]}>
            <Text accessibilityLanguage={locale} style={[styles.tabIcon, active ? styles.tabIconActive : null]}>
              {TAB_GLYPH[tab.id]}
            </Text>
            <Text accessibilityLanguage={locale} style={[styles.tabLabel, active ? styles.tabLabelActive : null]}>
              {label}
            </Text>
          </Pressable>
        );
      })}
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: SCREEN_TOKENS.color.surfaceDefault, padding: SCREEN_TOKENS.space.four },
  title: {
    color: SCREEN_TOKENS.color.textPrimary,
    fontSize: SCREEN_TOKENS.font.headingMd,
    fontWeight: SCREEN_TOKENS.font.semibold,
    marginBottom: SCREEN_TOKENS.space.one,
  },
  subtitle: {
    color: SCREEN_TOKENS.color.textSecondary,
    fontSize: SCREEN_TOKENS.font.body,
    marginBottom: SCREEN_TOKENS.space.five,
  },
  card: {
    backgroundColor: SCREEN_TOKENS.color.surfaceDefault,
    borderColor: SCREEN_TOKENS.color.borderSubtle,
    borderRadius: SCREEN_TOKENS.radius.card,
    borderStyle: 'solid',
    borderWidth: SCREEN_TOKENS.border.thin,
    marginBottom: SCREEN_TOKENS.space.three,
    padding: SCREEN_TOKENS.space.four,
  },
  cardDashed: { borderStyle: 'dashed', borderColor: SCREEN_TOKENS.color.borderDefault },
  pill: {
    alignSelf: 'flex-start',
    borderColor: SCREEN_TOKENS.color.borderSubtle,
    borderRadius: SCREEN_TOKENS.radius.pill,
    borderWidth: SCREEN_TOKENS.border.thin,
    flexDirection: 'row',
    marginBottom: SCREEN_TOKENS.space.two,
    paddingHorizontal: SCREEN_TOKENS.space.two,
    paddingVertical: SCREEN_TOKENS.space.one,
  },
  pillGlyph: { fontSize: SCREEN_TOKENS.font.label, fontWeight: SCREEN_TOKENS.font.semibold, marginEnd: SCREEN_TOKENS.space.one },
  pillLabel: { fontSize: SCREEN_TOKENS.font.label, fontWeight: SCREEN_TOKENS.font.medium },
  notice: {
    borderColor: SCREEN_TOKENS.color.borderDefault,
    borderRadius: SCREEN_TOKENS.radius.md,
    borderWidth: SCREEN_TOKENS.border.thin,
    marginBottom: SCREEN_TOKENS.space.three,
    padding: SCREEN_TOKENS.space.three,
  },
  noticeText: { color: SCREEN_TOKENS.color.textPrimary, fontSize: SCREEN_TOKENS.font.body, lineHeight: SCREEN_TOKENS.font.body * 1.5 },
  primaryButton: {
    alignItems: 'center',
    backgroundColor: SCREEN_TOKENS.color.actionPrimaryBackground,
    borderRadius: SCREEN_TOKENS.radius.md,
    justifyContent: 'center',
    marginTop: SCREEN_TOKENS.space.three,
    minHeight: SCREEN_TOKENS.size.target,
    padding: SCREEN_TOKENS.space.two,
  },
  primaryButtonDisabled: { backgroundColor: SCREEN_TOKENS.color.borderSubtle },
  primaryButtonText: { color: SCREEN_TOKENS.color.actionPrimaryText, fontSize: SCREEN_TOKENS.font.body, fontWeight: SCREEN_TOKENS.font.semibold },
  primaryButtonTextDisabled: { color: SCREEN_TOKENS.color.textSecondary },
  bubble: {
    borderRadius: SCREEN_TOKENS.radius.md,
    marginBottom: SCREEN_TOKENS.space.two,
    maxWidth: '85%',
    padding: SCREEN_TOKENS.space.three,
  },
  ownBubble: { alignSelf: 'flex-start', backgroundColor: SCREEN_TOKENS.color.surfaceSunken },
  otherBubble: { alignSelf: 'flex-end', backgroundColor: SCREEN_TOKENS.color.infoSurface },
  bubbleBody: { color: SCREEN_TOKENS.color.textPrimary, fontSize: SCREEN_TOKENS.font.body },
  bubbleTime: { color: SCREEN_TOKENS.color.textSecondary, fontSize: SCREEN_TOKENS.font.caption, marginTop: SCREEN_TOKENS.space.one },
  tabBar: {
    borderTopColor: SCREEN_TOKENS.color.borderSubtle,
    borderTopWidth: SCREEN_TOKENS.border.thin,
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginTop: 'auto',
    paddingTop: SCREEN_TOKENS.space.two,
  },
  tab: { alignItems: 'center', flex: 1, justifyContent: 'center', minHeight: SCREEN_TOKENS.size.target, opacity: 0.85 },
  tabActive: { opacity: 1 },
  tabIcon: { color: SCREEN_TOKENS.color.textSecondary, fontSize: SCREEN_TOKENS.font.headingSm },
  tabIconActive: { color: SCREEN_TOKENS.color.actionPrimaryBackground },
  tabLabel: { color: SCREEN_TOKENS.color.textSecondary, fontSize: SCREEN_TOKENS.font.caption },
  tabLabelActive: { color: SCREEN_TOKENS.color.actionPrimaryBackground, fontWeight: SCREEN_TOKENS.font.semibold },
});
