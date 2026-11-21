/**
 * Data-backed patient tabs (READY-48, PAT-01–03).
 *
 * Each page is a thin container: it binds one transport loader to the shared
 * resource states and passes validated projections to the existing
 * presentational screen. Commands remain no-op placeholders; this story only
 * replaces placeholder reads with authenticated API reads.
 */
import { useCallback, useMemo } from 'react';

import type { Transport } from '../api/transport';
import { AppointmentListScreen } from '../features/appointments/AppointmentListScreen';
import { ConversationInbox } from '../features/conversations/ConversationInbox';
import { ReleasedReportsScreen } from '../features/reports/ReleasedReportsScreen';

import { appText, type Locale } from './messages';
import { PatientResource } from './PatientResource';
import {
  loadAppointments,
  loadConversations,
  loadReports,
  placeholderAction,
  placeholderDevices,
  placeholderNotice,
} from './providers';
import type { ConversationInboxData } from './providers';
import { screenMessages } from './screenStates';

/** Inputs shared by every data-backed patient tab. */
export interface PatientPageProps {
  /** Authenticated READY-47 transport. */
  readonly transport: Transport;
  /** Active display locale. */
  readonly locale: Locale;
  /** Active organization derived from the account, not from the response. */
  readonly organizationId: string;
  /** Verified linked patient identifier. */
  readonly patientId: string;
  /** Clears the credential and leaves the signed-in shell on a 401. */
  readonly onSessionExpired: () => void;
}

/** Stable emptiness test for a loaded list. */
function isEmptyRows(rows: readonly unknown[]): boolean {
  return rows.length === 0;
}

/** Stable emptiness test for the conversation inbox projection. */
function isEmptyInbox(data: ConversationInboxData): boolean {
  return data.conversations.length === 0;
}

/** Appointments tab: patient endpoint, facility zone and confirmation state. */
export function AppointmentListPage({
  transport,
  locale,
  organizationId,
  patientId,
  onSessionExpired,
}: PatientPageProps): React.JSX.Element {
  const messages = useMemo(() => screenMessages(locale), [locale]);
  const load = useCallback(
    () => loadAppointments(transport, patientId, organizationId, locale),
    [transport, patientId, organizationId, locale],
  );
  return (
    <PatientResource
      load={load}
      isEmpty={isEmptyRows}
      emptyMessage={appText(locale, 'emptyAppointments')}
      messages={messages}
      onSessionExpired={onSessionExpired}>
      {(appointments) => (
        <AppointmentListScreen
          appointments={appointments}
          notice={placeholderNotice()}
          patientId={patientId}
          devices={placeholderDevices()}
          onConfirm={placeholderAction}
          onOpenDetails={placeholderAction}
          onRevokeDevice={placeholderAction}
        />
      )}
    </PatientResource>
  );
}

/** Reports tab: revisions currently released to the verified patient. */
export function ReleasedReportsPage({
  transport,
  locale,
  onSessionExpired,
}: PatientPageProps): React.JSX.Element {
  const messages = useMemo(() => screenMessages(locale), [locale]);
  const load = useCallback(() => loadReports(transport), [transport]);
  return (
    <PatientResource
      load={load}
      isEmpty={isEmptyRows}
      emptyMessage={appText(locale, 'emptyReports')}
      messages={messages}
      onSessionExpired={onSessionExpired}>
      {(reports) => (
        <ReleasedReportsScreen reports={reports} error={null} onDownload={placeholderAction} />
      )}
    </PatientResource>
  );
}

/** Messages tab root: one conversation inbox for the verified patient. */
export function ConversationInboxPage({
  transport,
  locale,
  organizationId,
  onSessionExpired,
}: PatientPageProps): React.JSX.Element {
  const messages = useMemo(() => screenMessages(locale), [locale]);
  const load = useCallback(
    () => loadConversations(transport, organizationId),
    [transport, organizationId],
  );
  return (
    <PatientResource
      load={load}
      isEmpty={isEmptyInbox}
      emptyMessage={appText(locale, 'emptyConversations')}
      messages={messages}
      onSessionExpired={onSessionExpired}>
      {(inbox) => (
        <ConversationInbox
          conversations={inbox.conversations}
          unreadByConversation={inbox.unreadByConversation}
          onOpen={placeholderAction}
        />
      )}
    </PatientResource>
  );
}
