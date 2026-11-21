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
import { StyleSheet, Text, View } from 'react-native';

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

import { applyDirection } from './direction';
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
import type { PatientPageProps } from './patientPages';
import { ScreenStack, type StackRoute } from './ScreenStack';
import { SignedOutScreen } from './SignedOutScreen';
import { TAB_DEFINITIONS, type TabId } from './tabs';

/** The four tab routes carry no parameters. */
type RootTabParamList = Record<TabId, undefined>;

const Tab = createBottomTabNavigator<RootTabParamList>();

/** Synthetic organization and patient identifiers until identity is wired. */
const SYNTHETIC_ORGANIZATION_ID = 'synthetic-organization';
const SYNTHETIC_PATIENT_ID = 'synthetic-patient';

/** Props for the patient root navigator. */
export interface RootNavigatorProps {
  /** Active display locale; English and Arabic are both supported (UX-01). */
  readonly locale: Locale;
  /** Injectable transport; the shell builds a real one when omitted. */
  readonly transport?: Transport;
  /** Optional hook for the host after the credential has been cleared. */
  readonly onSessionExpired?: () => void;
}

/** Appointments tab: the patient appointment list (PAT-01). */
function AppointmentsTab(props: PatientPageProps) {
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
      locale={locale}
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
      locale={locale}
      rootKey="menu"
      routes={routes}
    />
  );
}

/** Select the screen owned by one tab. */
function TabContent({ id, pageProps }: { readonly id: TabId; readonly pageProps: PatientPageProps }) {
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
 * Four-tab patient navigator with a single native-direction boundary.
 *
 * @param props active display locale and optional transport/test hooks
 * @returns the application title and tabs, or the signed-out screen
 */
export function RootNavigator({ locale, transport, onSessionExpired }: RootNavigatorProps) {
  const [signedOut, setSignedOut] = useState(false);
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

  const pageProps: PatientPageProps = {
    transport: api,
    locale,
    organizationId: SYNTHETIC_ORGANIZATION_ID,
    patientId: SYNTHETIC_PATIENT_ID,
    onSessionExpired: handleSessionExpired,
  };

  return (
    <View style={styles.root}>
      <Text
        accessibilityRole="header"
        accessibilityLanguage={locale}
        testID="app-title"
        style={styles.title}>
        {appText(locale, 'appTitle')}
      </Text>
      <NavigationContainer>
        <Tab.Navigator>
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
              {() => <TabContent id={tab.id} pageProps={pageProps} />}
            </Tab.Screen>
          ))}
        </Tab.Navigator>
      </NavigationContainer>
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1 },
  title: { fontSize: 24, textAlign: 'center' },
});
