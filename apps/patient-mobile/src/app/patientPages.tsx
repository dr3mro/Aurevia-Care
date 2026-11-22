/**
 * Data-backed patient tabs (READY-48, PAT-01–03).
 *
 * Each page is a thin container: it binds one transport loader to the shared
 * resource states and passes validated projections to the existing
 * presentational screen. Appointment confirmation is a real versioned command;
 * the remaining commands stay no-op placeholders until their own stories.
 * A shared layout frame keeps each page readable on compact, large and
 * right-to-left windows without changing data or exported names.
 */
import { useCallback, useMemo, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { StyleSheet, useWindowDimensions, View } from 'react-native';
import * as SafeArea from 'react-native-safe-area-context';

import type { Transport } from '../api/transport';
import { AppointmentListScreen } from '../features/appointments/AppointmentListScreen';
import type { AppointmentView } from '../features/appointments/appointments';
import { confirmedAppointmentView } from '../features/appointments/appointments';
import {
  claimPendingCommand,
  confirmAppointment,
  releasePendingCommand,
} from '../features/appointments/confirmClient';
import { ConversationInbox } from '../features/conversations/ConversationInbox';
import { ReleasedReportsScreen } from '../features/reports/ReleasedReportsScreen';

import type { WindowClass, WindowLayout } from './layout/useWindowClass';
import { windowClassFor } from './layout/useWindowClass';
import { appText, type Locale } from './messages';
import { PatientResource } from './PatientResource';
import {
  facilityZoneFormatter,
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

/** Safe-area inset shape shared with the window-layout hook. */
type Insets = WindowLayout['insets'];

/** Zero insets for headless renderers that expose no safe-area hook. */
const NO_INSETS: Insets = { top: 0, right: 0, bottom: 0, left: 0 };

/**
 * Read the safe-area insets from the platform hook. The repository's headless
 * Jest setup maps the library to a default-only mock without a named hook, so
 * resolve the binding once and fall back to zero insets there; on a device the
 * platform hook is always present.
 */
const readInsets = SafeArea.useSafeAreaInsets ?? ((): Insets => NO_INSETS);

/**
 * Resolve the responsive inputs for one page from the current window. The
 * breakpoint rule is shared with `windowClassFor`, so rotation and
 * split-screen classify a window exactly as they do elsewhere in the app.
 */
function usePageLayout(): { windowClass: WindowClass; insets: Insets } {
  const { width } = useWindowDimensions();
  return { windowClass: windowClassFor(width), insets: readInsets() };
}

/** Maximum inline width of the centred content column on large windows. */
const READING_MAX_WIDTH = 1120;

/** Inputs to the shared responsive frame around each data-backed tab. */
interface PatientPageFrameProps {
  /** Stable identifier used to expose the frame structure to tests. */
  readonly pageId: string;
  /** Active display locale; Arabic mirrors the two-pane order. */
  readonly locale: Locale;
  /** The existing page content, rendered without modification. */
  readonly children: ReactNode;
}

/**
 * Presentational layout frame for the three data-backed patient tabs
 * (PAT-01, PAT-02, UX-01).
 *
 * It follows the window class from the shared `windowClassFor` rule, so
 * rotation, split-screen and Slide Over reuse one classification. Physical
 * safe-area insets are mapped to logical padding (swapped under RTL), wide
 * windows get a centred maximum-width column and a list/detail two-pane row,
 * and no fixed height or line clamp is applied so text can scale to 200%. The
 * frame holds no clinical data, issues no request and adds no accessible
 * control: the wrapped screen keeps its focus order and screen-reader labels.
 *
 * @param props page identifier, locale and existing page content
 * @returns the framed page
 */
function PatientPageFrame({ pageId, locale, children }: PatientPageFrameProps): React.JSX.Element {
  const { windowClass, insets } = usePageLayout();
  const large = windowClass === 'large';
  const rtl = locale === 'ar';
  return (
    <View
      testID={`${pageId}-frame`}
      style={[
        styles.frame,
        {
          paddingTop: insets.top,
          paddingBottom: insets.bottom,
          paddingStart: rtl ? insets.right : insets.left,
          paddingEnd: rtl ? insets.left : insets.right,
        },
      ]}>
      <View testID={`${pageId}-column`} style={large ? styles.columnLarge : styles.column}>
        {large ? (
          <View testID={`${pageId}-panes`} style={rtl ? styles.panesRtl : styles.panes}>
            <View testID={`${pageId}-list-pane`} style={styles.pane}>
              {children}
            </View>
            <View testID={`${pageId}-detail-pane`} style={styles.pane} />
          </View>
        ) : (
          <View testID={`${pageId}-list-pane`} style={styles.pane}>
            {children}
          </View>
        )}
      </View>
    </View>
  );
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
  const formatter = useMemo(() => facilityZoneFormatter(), []);
  /** Server-confirmed revisions by tenant row key, applied over the loaded list. */
  const [confirmedRows, setConfirmedRows] = useState<Readonly<Record<string, AppointmentView>>>({});
  /** Row whose command is in flight, exposed to the screen as a disabled control. */
  const [pendingId, setPendingId] = useState<string | null>(null);
  /** Bumped on a stale version to reissue the load with fresh server data. */
  const [reloadCount, setReloadCount] = useState(0);
  /** Synchronous guard: React state updates alone would allow a fast double press. */
  const pendingRef = useRef<string | null>(null);

  const load = useCallback(
    () => loadAppointments(transport, patientId, organizationId),
    [transport, patientId, organizationId],
  );

  /**
   * Issue one versioned confirmation for the row that was displayed.
   *
   * A press while a command is in flight is dropped before any request or state
   * change. The server instant becomes the displayed timestamp on success; a
   * stale version or an already-confirmed row reloads the list instead of
   * asserting a confirmation; a 401 ends the session; every other failure
   * leaves the row untouched so the patient can retry.
   */
  const confirm = useCallback(
    async (row: AppointmentView): Promise<void> => {
      if (!claimPendingCommand(pendingRef, row.id)) {
        return;
      }
      setPendingId(row.id);
      try {
        const result = await confirmAppointment(transport, {
          appointmentId: row.id,
          patientId,
          expectedVersion: row.version,
        });
        if (result.kind === 'confirmed') {
          setConfirmedRows((current) => ({
            ...current,
            [row.key]: confirmedAppointmentView(row, result.confirmedAt, result.version, formatter),
          }));
        } else if (result.kind === 'stale') {
          setConfirmedRows({});
          setReloadCount((value) => value + 1);
        } else if (result.kind === 'unauthenticated') {
          onSessionExpired();
        } else if (result.error.code === 'NOT_CONFIRMABLE') {
          // The server may have committed before its reply was lost. A retry
          // with the old version then reports 422 NOT_CONFIRMABLE because the
          // row is already confirmed; reload to show the server's truth rather
          // than looping on a retry that can never succeed.
          setConfirmedRows({});
          setReloadCount((value) => value + 1);
        }
      } finally {
        releasePendingCommand(pendingRef);
        setPendingId(null);
      }
    },
    [transport, patientId, onSessionExpired, formatter],
  );

  return (
    <PatientPageFrame pageId="appointments" locale={locale}>
      <PatientResource
        key={reloadCount}
        load={load}
        isEmpty={isEmptyRows}
        emptyMessage={appText(locale, 'emptyAppointments')}
        messages={messages}
        onSessionExpired={onSessionExpired}>
        {(appointments) => (
          <AppointmentListScreen
            appointments={appointments.map((row) => confirmedRows[row.key] ?? row)}
            notice={placeholderNotice()}
            patientId={patientId}
            devices={placeholderDevices()}
            onConfirm={(row) => {
              void confirm(row);
            }}
            pendingAppointmentId={pendingId}
            onOpenDetails={placeholderAction}
            onRevokeDevice={placeholderAction}
          />
        )}
      </PatientResource>
    </PatientPageFrame>
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
    <PatientPageFrame pageId="reports" locale={locale}>
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
    </PatientPageFrame>
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
    <PatientPageFrame pageId="conversations" locale={locale}>
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
    </PatientPageFrame>
  );
}

const styles = StyleSheet.create({
  frame: { flex: 1 },
  column: { flex: 1, width: '100%' },
  columnLarge: { flex: 1, width: '100%', maxWidth: READING_MAX_WIDTH, alignSelf: 'center' },
  // Direction is authoritative: the app also forces native RTL globally, so an
  // extra row-reverse here would flip the panes back into the wrong order.
  panes: { flex: 1, flexDirection: 'row', direction: 'ltr' },
  panesRtl: { flex: 1, flexDirection: 'row', direction: 'rtl' },
  pane: { flex: 1, minWidth: 0 },
});
