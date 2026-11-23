/**
 * Patient app root shell: four bottom tabs in the contract order
 * (docs/design/screens-and-navigation.md section 3, PAT-01–03, UX-01).
 *
 * The three data-backed tabs now read through the READY-47 transport; the
 * remaining screens stay presentational. A 401 clears the stored credential
 * and replaces the tabs with a signed-out screen, without a navigation
 * dependency or any clinical data in app state.
 */
import { createBottomTabNavigator } from '@react-navigation/bottom-tabs';
import { NavigationContainer } from '@react-navigation/native';
import { useCallback, useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import type { ViewStyle } from 'react-native';

import { clearToken } from '../api/tokenStore';
import type { Transport } from '../api/transport';
import { InstructionAcknowledgmentScreen } from '../features/acknowledgments/InstructionAcknowledgmentScreen';
import { AttachmentReviewScreen } from '../features/attachments/AttachmentReviewScreen';
import {
  EnrollmentScreen,
  LockoutScreen,
  RecoveryScreen,
  SupportRequestScreen,
} from '../features/enrollment/screens';
import { applyRegistration, commandForSession } from '../features/notifications/registration';

import type { DeviceWriteClient } from '../features/appointments/deviceClient';
import { eligibleTokens } from '../features/appointments/devices';
import { SCREEN_TOKENS } from '../features/appointments/patientDesign';
import type { DeviceToken } from '../features/appointments/types';

import { applyDirection } from './direction';
import { useWindowClass } from './layout/useWindowClass';
import type { WindowLayout } from './layout/useWindowClass';
import { appText } from './messages';
import type { Locale } from './messages';
import {
  placeholderAction,
  placeholderAttachments,
  placeholderEnrollmentStrings,
  placeholderFacts,
  placeholderInstructions,
  placeholderLockoutStrings,
  placeholderNow,
  placeholderPublicationAuthorized,
  placeholderRecoveryStrings,
  placeholderSupportStrings,
  placeholderUnit,
  placeholderUnitId,
  createPatientTransport,
  PATIENT_API_BASE_URL,
} from './providers';
import {
  AppointmentListPage,
  ConversationInboxPage,
  ReleasedReportsPage,
} from './patientPages';
import type { AppointmentListPageProps, PatientPageProps } from './patientPages';
import { ScreenStack, type StackRoute } from './ScreenStack';
import { SignedOutScreen } from './SignedOutScreen';
import { TAB_DEFINITIONS, type TabId } from './tabs';

/** The four tab routes carry no parameters. */
type RootTabParamList = Record<TabId, undefined>;

const Tab = createBottomTabNavigator<RootTabParamList>();

/** Synthetic organization and patient identifiers until identity is wired. */
const SYNTHETIC_ORGANIZATION_ID = 'synthetic-organization';
const SYNTHETIC_PATIENT_ID = 'synthetic-patient';

/**
 * Device registered for the synthetic signed-in session in the installed shell.
 *
 * V1 publishes no server device-list route and account identity is not wired,
 * so the synthetic shell has no authoritative registration to project. Keeping
 * this fixture beside the synthetic organization and patient ids means the
 * seeded device still travels through the real registration command and the
 * tenant/patient eligibility helpers before it reaches the appointments tab.
 * A clinic-supplied device source replaces it when identity is wired.
 */
const SYNTHETIC_REGISTERED_DEVICE: DeviceToken = {
  id: 'dev-1',
  patientId: SYNTHETIC_PATIENT_ID,
  state: 'active',
  token: 'synthetic-device-token',
};

/**
 * Maximum inline width of readable content. On a large window the shell is
 * centred at this width; narrower windows use the full available width.
 */
const READABLE_MAX_WIDTH = 840;

/**
 * Smallest permitted touch-target side, in points. The shared patient token
 * (48) already exceeds the 44 pt / 48 dp platform floor, so it is reused
 * instead of duplicating a competing value.
 */
const TOUCH_TARGET = SCREEN_TOKENS.size.target;

/** Props for the patient root navigator. */
export interface RootNavigatorProps {
  /** Active display locale; English and Arabic are both supported (UX-01). */
  readonly locale: Locale;
  /** Injectable transport; the shell builds a real one when omitted. */
  readonly transport?: Transport;
  /**
   * Device registered for the signed-in session. The installed shell omits this
   * and uses its synthetic registration; tests inject a device to prove that
   * only the verified patient's active device becomes a revoke control.
   */
  readonly registeredDevice?: DeviceToken;
  /** Injectable device write port; the appointments page builds a real one when omitted. */
  readonly deviceClient?: DeviceWriteClient;
  /** Optional hook for the host after the credential has been cleared. */
  readonly onSessionExpired?: () => void;
}

/** Appointments tab: the patient appointment list (PAT-01). */
function AppointmentsTab(props: AppointmentListPageProps) {
  return <AppointmentListPage {...props} />;
}

/** Reports tab: released reports with revision context (PAT-02). */
function ReportsTab(props: PatientPageProps) {
  return <ReleasedReportsPage {...props} />;
}

/** Messages tab: inbox plus pushed attachment and instruction screens (MSG-01–05). */
function MessagesTab({ locale, ...pageProps }: PatientPageProps) {
  const routes: readonly StackRoute[] = [
    {
      key: 'conversations',
      label: appText(locale, 'tabMessages'),
      render: () => <ConversationInboxPage locale={locale} {...pageProps} />,
    },
    {
      key: 'attachments',
      label: appText(locale, 'messagesAttachments'),
      render: () => (
        <View testID="messages-screen-attachments">
          <AttachmentReviewScreen
            locale={locale}
            activeUnit={placeholderUnit()}
            activeUnitId={placeholderUnitId()}
            pending={placeholderAttachments()}
            publicationAuthorized={placeholderPublicationAuthorized()}
            onRemove={placeholderAction}
            onReplace={placeholderAction}
            onDownload={placeholderAction}
            onSend={placeholderAction}
          />
        </View>
      ),
    },
    {
      key: 'instructions',
      label: appText(locale, 'messagesInstructions'),
      render: () => (
        <View testID="messages-screen-instructions">
          <InstructionAcknowledgmentScreen
            instructions={placeholderInstructions()}
            facts={placeholderFacts()}
            isRTL={locale === 'ar'}
            now={placeholderNow()}
            onFirstView={placeholderAction}
            onAcknowledge={placeholderAction}
          />
        </View>
      ),
    },
  ];

  return (
    <ScreenStack
      testID="stack-messages"
      title={appText(locale, 'tabMessages')}
      backLabel={appText(locale, 'back')}
      rootKey="conversations"
      routes={routes}
    />
  );
}

/** Account tab: a menu that pushes the four account flows (PAT-03). */
function AccountTab({ locale }: PatientPageProps) {
  const routes: readonly StackRoute[] = [
    {
      key: 'menu',
      label: appText(locale, 'accountMenu'),
      render: () => <View testID="account-menu" />,
    },
    {
      key: 'enrollment',
      label: appText(locale, 'accountEnrollment'),
      render: () => (
        <View testID="account-screen-enrollment">
          <EnrollmentScreen
            status="idle"
            code=""
            onCodeChange={placeholderAction}
            onSubmit={placeholderAction}
            strings={placeholderEnrollmentStrings(locale)}
            isRTL={locale === 'ar'}
          />
        </View>
      ),
    },
    {
      key: 'recovery',
      label: appText(locale, 'accountRecovery'),
      render: () => (
        <View testID="account-screen-recovery">
          <RecoveryScreen
            status="idle"
            code=""
            verification=""
            onCodeChange={placeholderAction}
            onVerificationChange={placeholderAction}
            onSubmit={placeholderAction}
            strings={placeholderRecoveryStrings(locale)}
            isRTL={locale === 'ar'}
          />
        </View>
      ),
    },
    {
      key: 'support',
      label: appText(locale, 'accountSupport'),
      render: () => (
        <View testID="account-screen-support">
          <SupportRequestScreen
            category={null}
            message=""
            noticeVisible={false}
            onCategoryChange={placeholderAction}
            onMessageChange={placeholderAction}
            onSubmit={placeholderAction}
            strings={placeholderSupportStrings(locale)}
            isRTL={locale === 'ar'}
          />
        </View>
      ),
    },
    {
      key: 'lockout',
      label: appText(locale, 'accountLockout'),
      render: () => (
        <View testID="account-screen-lockout">
          <LockoutScreen
            remainingSeconds={0}
            onSupport={placeholderAction}
            strings={placeholderLockoutStrings(locale)}
            isRTL={locale === 'ar'}
          />
        </View>
      ),
    },
  ];

  return (
    <ScreenStack
      testID="stack-account"
      title={appText(locale, 'tabAccount')}
      backLabel={appText(locale, 'back')}
      rootKey="menu"
      routes={routes}
    />
  );
}

/** Select the screen owned by one tab. */
function TabContent({
  id,
  pageProps,
}: {
  readonly id: TabId;
  readonly pageProps: AppointmentListPageProps;
}) {
  switch (id) {
    case 'appointments':
      return <AppointmentsTab {...pageProps} />;
    case 'reports':
      return <ReportsTab {...pageProps} />;
    case 'messages':
      return <MessagesTab {...pageProps} />;
    case 'account':
      return <AccountTab {...pageProps} />;
  }
}

/**
 * Presentational viewport around the active tab screen (PAT-01, PAT-02, UX-01).
 *
 * On a large window the screen is placed in the list pane of a two-pane row so
 * a list-and-detail surface can show its detail beside the list without losing
 * the list position. On compact and regular windows the same screen fills a
 * single pane, matching the pushed-screen presentation in decision 0005.
 * Panes carry no accessibility labels of their own: the screen-reader labels,
 * focus order and data flow of the wrapped screen are left untouched, and the
 * detail pane is an empty presentational slot rather than a clinical cache.
 *
 * @param layout current window layout class and direction context
 * @param isRTL whether the active locale uses right-to-left layout
 * @param children the active tab screen
 */
function TabViewport({
  layout,
  isRTL,
  children,
}: {
  readonly layout: WindowLayout;
  readonly isRTL: boolean;
  readonly children: ReactNode;
}) {
  if (layout.windowClass === 'large') {
    return (
      <View testID="two-pane" style={[styles.twoPane, { direction: isRTL ? 'rtl' : 'ltr' }]}>
        <View testID="pane-list" style={styles.paneList}>
          {children}
        </View>
        <View testID="pane-detail" style={styles.paneDetail} />
      </View>
    );
  }
  return (
    <View testID="single-pane" style={styles.singlePane}>
      {children}
    </View>
  );
}

/**
 * Four-tab patient navigator with a single native-direction boundary.
 *
 * @param props active display locale and optional transport/test hooks
 * @returns the application title and tabs, or the signed-out screen
 */
export function RootNavigator({
  locale,
  transport,
  registeredDevice,
  deviceClient,
  onSessionExpired,
}: RootNavigatorProps) {
  const [signedOut, setSignedOut] = useState(false);
  const layout = useWindowClass();
  useEffect(() => {
    applyDirection(locale);
  }, [locale]);

  const api = useMemo(
    () =>
      transport ?? createPatientTransport(PATIENT_API_BASE_URL, locale, fetch),
    [transport, locale],
  );

  const handleSessionExpired = useCallback(() => {
    void clearToken()
      .catch(() => undefined)
      .then(() => {
        setSignedOut(true);
        onSessionExpired?.();
      });
  }, [onSessionExpired]);

  if (signedOut) {
    return <SignedOutScreen locale={locale} onSignIn={() => setSignedOut(false)} />;
  }

  const isRTL = locale === 'ar';
  // Safe-area insets are physical; map them to logical padding once at the
  // shell boundary so right-to-left swaps the inline pair without a component
  // knowing about screen edges. Physical left/right styles are never used.
  const inlineContentStyle: ViewStyle = {
    direction: isRTL ? 'rtl' : 'ltr',
    paddingTop: layout.insets.top,
    // The bottom inset is applied once here, on the region that contains the
    // navigator. Adding it to the nested tab bar as well would double it.
    paddingBottom: layout.insets.bottom,
    paddingStart: isRTL ? layout.insets.right : layout.insets.left,
    paddingEnd: isRTL ? layout.insets.left : layout.insets.right,
  };
  const contentStyle = [
    styles.content,
    inlineContentStyle,
    layout.windowClass === 'large' ? styles.contentLarge : null,
  ];
  const tabBarStyle = { minHeight: TOUCH_TARGET };

  // Register the signed-in device and project it through the same tenant- and
  // patient-scoped helpers the notification and appointment domains use, so a
  // foreign or revoked registration can never become a revoke control. A plain const (not a hook)
  // keeps the early return above free of a conditional hook call, and the page
  // reads this mount-time seed before it renders any device action.
  const devices = eligibleTokens(
    applyRegistration(
      new Map<string, DeviceToken>(),
      commandForSession({
        kind: 'signed_in',
        organizationId: SYNTHETIC_ORGANIZATION_ID,
        device: registeredDevice ?? SYNTHETIC_REGISTERED_DEVICE,
      }),
    ),
    SYNTHETIC_ORGANIZATION_ID,
    SYNTHETIC_PATIENT_ID,
  );

  const pageProps: AppointmentListPageProps = {
    transport: api,
    locale,
    organizationId: SYNTHETIC_ORGANIZATION_ID,
    patientId: SYNTHETIC_PATIENT_ID,
    onSessionExpired: handleSessionExpired,
    initialDevices: devices,
    // Only forward the host's port when one was supplied: an explicitly
    // undefined optional property is rejected under exactOptionalPropertyTypes.
    ...(deviceClient !== undefined ? { deviceClient } : {}),
  };

  return (
    <View testID="responsive-content" style={[styles.root, contentStyle]}>
      <Text
        accessibilityRole="header"
        accessibilityLanguage={locale}
        allowFontScaling
        testID="app-title"
        style={styles.title}>
        {appText(locale, 'appTitle')}
      </Text>
      <NavigationContainer>
        <Tab.Navigator screenOptions={{ tabBarStyle }}>
          {TAB_DEFINITIONS.map(tab => (
            <Tab.Screen
              key={tab.id}
              name={tab.id}
              options={{
                title: appText(locale, tab.labelKey),
                tabBarLabel: appText(locale, tab.labelKey),
                tabBarAccessibilityLabel: appText(locale, tab.accessibilityLabelKey),
                tabBarButtonTestID: `tab-button-${tab.id}`,
              }}>
              {() => (
                <TabViewport layout={layout} isRTL={isRTL}>
                  <TabContent id={tab.id} pageProps={pageProps} />
                </TabViewport>
              )}
            </Tab.Screen>
          ))}
        </Tab.Navigator>
      </NavigationContainer>
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1 },
  content: { flex: 1, width: '100%' },
  contentLarge: { alignSelf: 'center', maxWidth: READABLE_MAX_WIDTH },
  title: {
    flexShrink: 1,
    fontSize: SCREEN_TOKENS.font.headingMd,
    minHeight: TOUCH_TARGET,
    textAlign: 'center',
  },
  singlePane: { flex: 1 },
  twoPane: { flex: 1, flexDirection: 'row' },
  paneList: { flex: 1, minWidth: 0 },
  paneDetail: { borderStartWidth: SCREEN_TOKENS.border.thin, flex: 1, minWidth: 0 },
});
