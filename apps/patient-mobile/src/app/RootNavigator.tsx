/**
 * Patient app root shell: four bottom tabs in the contract order
 * (docs/design/screens-and-navigation.md section 3, PAT-01–03, UX-01).
 *
 * Every screen is presentational and receives synthetic values from the
 * placeholder providers. There is no authentication, network client or push
 * notification code here; a later, separately approved task replaces the
 * providers with the authenticated API transport.
 */
import { createBottomTabNavigator } from '@react-navigation/bottom-tabs';
import { NavigationContainer } from '@react-navigation/native';
import { useEffect } from 'react';
import { StyleSheet, Text, View } from 'react-native';

import { InstructionAcknowledgmentScreen } from '../features/acknowledgments/InstructionAcknowledgmentScreen';
import { AppointmentListScreen } from '../features/appointments/AppointmentListScreen';
import { AttachmentReviewScreen } from '../features/attachments/AttachmentReviewScreen';
import { ConversationInbox } from '../features/conversations/ConversationInbox';
import {
  EnrollmentScreen,
  LockoutScreen,
  RecoveryScreen,
  SupportRequestScreen,
} from '../features/enrollment/screens';
import { ReleasedReportsScreen } from '../features/reports/ReleasedReportsScreen';

import { applyDirection } from './direction';
import { appText, type Locale } from './messages';
import {
  placeholderAction,
  placeholderAppointments,
  placeholderAttachments,
  placeholderConversations,
  placeholderDevices,
  placeholderEnrollmentStrings,
  placeholderFacts,
  placeholderInstructions,
  placeholderLockoutStrings,
  placeholderNotice,
  placeholderNow,
  placeholderPatientId,
  placeholderPublicationAuthorized,
  placeholderRecoveryStrings,
  placeholderReports,
  placeholderSupportStrings,
  placeholderUnit,
  placeholderUnitId,
} from './providers';
import { ScreenStack, type StackRoute } from './ScreenStack';
import { TAB_DEFINITIONS, type TabId } from './tabs';

/** The four tab routes carry no parameters. */
type RootTabParamList = Record<TabId, undefined>;

const Tab = createBottomTabNavigator<RootTabParamList>();

/** Props for the patient root navigator. */
export interface RootNavigatorProps {
  /** Active display locale; English and Arabic are both supported (UX-01). */
  readonly locale: Locale;
}

/** Appointments tab: the patient appointment list (PAT-01). */
function AppointmentsTab() {
  return (
    <AppointmentListScreen
      appointments={placeholderAppointments()}
      notice={placeholderNotice()}
      patientId={placeholderPatientId()}
      devices={placeholderDevices()}
      onConfirm={placeholderAction}
      onOpenDetails={placeholderAction}
      onRevokeDevice={placeholderAction}
    />
  );
}

/** Reports tab: released reports with revision context (PAT-02). */
function ReportsTab() {
  return (
    <ReleasedReportsScreen
      reports={placeholderReports()}
      error={null}
      onDownload={placeholderAction}
    />
  );
}

/** Messages tab: inbox plus pushed attachment and instruction screens (MSG-01–05). */
function MessagesTab({ locale }: { readonly locale: Locale }) {
  const routes: readonly StackRoute[] = [
    {
      key: 'conversations',
      label: appText(locale, 'tabMessages'),
      render: () => (
        <ConversationInbox
          conversations={placeholderConversations()}
          onOpen={placeholderAction}
        />
      ),
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
function AccountTab({ locale }: { readonly locale: Locale }) {
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
function TabContent({ id, locale }: { readonly id: TabId; readonly locale: Locale }) {
  switch (id) {
    case 'appointments':
      return <AppointmentsTab />;
    case 'reports':
      return <ReportsTab />;
    case 'messages':
      return <MessagesTab locale={locale} />;
    case 'account':
      return <AccountTab locale={locale} />;
  }
}

/**
 * Four-tab patient navigator with a single native-direction boundary.
 *
 * @param props active display locale
 * @returns the application title and the localized tab navigator
 */
export function RootNavigator({ locale }: RootNavigatorProps) {
  useEffect(() => {
    applyDirection(locale);
  }, [locale]);

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
              {() => <TabContent id={tab.id} locale={locale} />}
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
