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
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { StyleSheet, useWindowDimensions, View } from 'react-native';
import * as SafeArea from 'react-native-safe-area-context';

import { TransportError } from '../api/transport';
import type { Transport } from '../api/transport';
import { AppointmentDetail } from '../features/appointments/AppointmentDetail';
import { AppointmentListScreen } from '../features/appointments/AppointmentListScreen';
import { DeviceRevocationConfirm } from '../features/appointments/DeviceRevocationConfirm';
import type { AppointmentView } from '../features/appointments/appointments';
import { confirmedAppointmentView } from '../features/appointments/appointments';
import {
  claimPendingCommand,
  confirmAppointment,
  releasePendingCommand,
} from '../features/appointments/confirmClient';
import { createDeviceWriteClient, revokeDevice } from '../features/appointments/deviceClient';
import type { DeviceWriteClient } from '../features/appointments/deviceClient';
import {
  devicesForPatient,
  findEligibleDevice,
  refreshVisibleDevices,
} from '../features/appointments/devices';
import { GENERIC_NOTICE } from '../features/appointments/notifications';
import { screenText } from '../features/appointments/patientDesign';
import type { DeviceToken } from '../features/appointments/types';
import { ConversationInbox } from '../features/conversations/ConversationInbox';
import { triggerStatusNotice } from '../features/notifications/statusChangeTrigger';
import type { StatusNoticeSeam } from '../features/notifications/statusChangeTrigger';
import { SecureCredentialStore } from '../features/reports/credentials';
import { createAlertConfirmer, downloadWithHandoff } from '../features/reports/downloadFlow';
import type { Confirmer, DocumentHandoff } from '../features/reports/downloadFlow';
import { ReleasedReportClient } from '../features/reports/reportClient';
import { ReleasedReportsScreen } from '../features/reports/ReleasedReportsScreen';
import type { DownloadAuthorization, ReleasedReport, TokenStore } from '../features/reports/types';

import type { WindowClass, WindowLayout } from './layout/useWindowClass';
import { windowClassFor } from './layout/useWindowClass';
import { appText, type Locale } from './messages';
import { PatientResource } from './PatientResource';
import {
  facilityZoneFormatter,
  loadAppointments,
  loadConversations,
  loadReports,
  PATIENT_API_BASE_URL,
  placeholderAction,
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

/** Optional seams for the appointments tab's device commands. */
export interface AppointmentListPageProps extends PatientPageProps {
  /**
   * Authenticated device write port. A real DELETE client is built from the
   * configured API origin when the host does not inject one; tests inject a
   * synthetic port so no request leaves the harness.
   */
  readonly deviceClient?: DeviceWriteClient;
  /**
   * Tenant-scoped flat device seed supplied by the signed-in host. No server
   * device-list route exists in V1, so the host owns the registry and this page
   * only derives the devices eligible for its verified patient. It is read when
   * the tab mounts; changing the registry requires a remount, which keeps the
   * refresh path a single deterministic projection rather than an extra effect.
   */
  readonly initialDevices?: readonly DeviceToken[];
  /**
   * Optional host seam for authoritative appointment status changes. A signed-in
   * host emits through `subscribe` and injects the delivery boundaries; the page
   * never polls the appointment list. Without a seam the tab only displays the
   * approved generic notice, because no real push gateway exists in V1.
   */
  readonly statusNotices?: StatusNoticeSeam;
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
  deviceClient,
  initialDevices,
  statusNotices,
}: AppointmentListPageProps): React.JSX.Element {
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

  /** The real write port is built once per locale; the host may inject a test one. */
  const writeClient = useMemo(
    () =>
      deviceClient ??
      createDeviceWriteClient({ baseUrl: PATIENT_API_BASE_URL, fetchImpl: fetch, language: locale }),
    [deviceClient, locale],
  );
  /** Only the host's tenant-scoped, active devices of this patient are offered. */
  const [devices, setDevices] = useState<readonly DeviceToken[]>(() =>
    devicesForPatient(initialDevices ?? [], organizationId, patientId),
  );
  /** Appointment whose detail is open, or null while the list is shown. */
  const [selectedId, setSelectedId] = useState<string | null>(null);
  /** Device awaiting an explicit confirm/cancel choice. */
  const [revocationId, setRevocationId] = useState<string | null>(null);
  /** Device whose revocation write is in flight. */
  const [deviceInFlightId, setDeviceInFlightId] = useState<string | null>(null);
  /** Generic failure line; never a token, a response body or clinical detail. */
  const [deviceError, setDeviceError] = useState<string | null>(null);
  /** Synchronous guard so two presses in one turn cannot issue two DELETEs. */
  const devicePendingRef = useRef<string | null>(null);
  /** Status changes already dispatched; a ref survives re-renders without state. */
  const dispatchedNotices = useRef(new Set<string>());

  /**
   * Subscribe to the host's status changes when it supplies a seam.
   *
   * The page contributes only verified ids, its ledger and its session callback
   * to the trigger. No result is rendered: the notice text is the same fixed
   * generic constant before and after a push, so no clinical value can reach
   * the screen through this path. The returned unsubscribe is the effect
   * cleanup, so no listener survives an unmount.
   */
  useEffect(() => {
    if (statusNotices === undefined) {
      return;
    }
    return statusNotices.subscribe((change) => {
      void triggerStatusNotice(change, {
        organizationId,
        patientId,
        auth: statusNotices.auth,
        devices: statusNotices.devices,
        inbox: statusNotices.inbox,
        gateway: statusNotices.gateway,
        logger: statusNotices.logger,
        at: statusNotices.at,
        fetchDetail: statusNotices.fetchDetail,
        dispatched: dispatchedNotices.current,
        onSessionExpired,
      });
    });
  }, [statusNotices, organizationId, patientId, onSessionExpired]);

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

  /** Open details for exactly the row whose control was pressed. */
  const openDetails = useCallback((id: string) => setSelectedId(id), []);

  /** Leave the detail without changing the loaded list or any command state. */
  const closeDetails = useCallback(() => setSelectedId(null), []);

  /**
   * Resolve a pressed device id before offering a destructive command.
   *
   * A guessed, stale, foreign or revoked identifier opens no confirmation, so
   * no request can be sent for a device outside this patient's active tenant.
   */
  const offerRevoke = useCallback(
    (deviceId: string) => {
      const device = findEligibleDevice(devices, organizationId, deviceId, patientId);
      setRevocationId(device === undefined ? null : device.id);
      setDeviceError(null);
    },
    [devices, organizationId, patientId],
  );

  /** Cancel keeps the device and sends no request. */
  const cancelRevocation = useCallback(() => {
    setRevocationId(null);
    setDeviceError(null);
  }, []);

  /**
   * Issue one revocation after the explicit Confirm press.
   *
   * A fast second press is dropped by the synchronous guard before any write.
   * A success or an idempotent already-revoked reply refreshes the scoped
   * devices; a 401 ends the session; every other failure keeps the device and
   * shows one generic line so the patient can retry deliberately.
   */
  const revoke = useCallback(async (): Promise<void> => {
    // The Confirm control is rendered only while a device is selected, so that
    // selection is an invariant of this handler, not a reachable guard.
    const deviceId = revocationId!;
    if (!claimPendingCommand(devicePendingRef, deviceId)) {
      return;
    }
    setDeviceInFlightId(deviceId);
    try {
      const result = await revokeDevice(writeClient, { deviceId, patientId });
      if (result.kind === 'unauthenticated') {
        onSessionExpired();
        setRevocationId(null);
        setDeviceError(null);
      } else if (result.kind === 'revoked' || result.kind === 'alreadyRevoked') {
        // No server device GET exists in V1: recompute eligibility from the
        // host's scoped seed so the revoked device is no longer offered.
        setDevices(refreshVisibleDevices(devices, organizationId, deviceId, patientId));
        setRevocationId(null);
        setDeviceError(null);
      } else {
        setDeviceError(screenText(locale, 'revokeError'));
      }
    } finally {
      releasePendingCommand(devicePendingRef);
      setDeviceInFlightId(null);
    }
  }, [revocationId, writeClient, patientId, onSessionExpired, devices, organizationId, locale]);

  return (
    <PatientPageFrame pageId="appointments" locale={locale}>
      <PatientResource
        key={reloadCount}
        load={load}
        isEmpty={isEmptyRows}
        emptyMessage={appText(locale, 'emptyAppointments')}
        messages={messages}
        onSessionExpired={onSessionExpired}>
        {(appointments) => {
          const visible = appointments.map((row) => confirmedRows[row.key] ?? row);
          const selected = visible.find((row) => row.id === selectedId);
          const detail =
            selected === undefined ? null : (
              <AppointmentDetail
                appointment={selected}
                locale={locale}
                backLabel={screenText(locale, 'back')}
                onBack={closeDetails}
              />
            );
          const confirmationStep =
            revocationId === null ? null : (
              <DeviceRevocationConfirm
                deviceId={revocationId}
                locale={locale}
                title={screenText(locale, 'revokeConfirmTitle')}
                body={screenText(locale, 'revokeConfirmBody')}
                cancelLabel={screenText(locale, 'revokeCancel')}
                confirmLabel={screenText(locale, 'revokeConfirm')}
                errorMessage={deviceError}
                pending={deviceInFlightId !== null}
                onCancel={cancelRevocation}
                onConfirm={() => {
                  void revoke();
                }}
              />
            );
          return (
            <AppointmentListScreen
              appointments={visible}
              notice={GENERIC_NOTICE}
              patientId={patientId}
              devices={devices}
              onConfirm={(row) => {
                void confirm(row);
              }}
              pendingAppointmentId={pendingId}
              onOpenDetails={openDetails}
              onRevokeDevice={offerRevoke}
              selectedAppointmentId={selectedId}
              detailContent={
                <>
                  {detail}
                  {confirmationStep}
                </>
              }
              locale={locale}
            />
          );
        }}
      </PatientResource>
    </PatientPageFrame>
  );
}

/** Injectable ports for the reports tab's download command. */
export interface ReportDownloadSeam {
  /** OS-backed session credential; the installed page uses the native secure store. */
  readonly tokens?: TokenStore;
  /** Explicit intent prompt; the installed page uses the native alert. */
  readonly confirm?: Confirmer;
  /** Authorized document handoff; the installed page uses the deferred seam below. */
  readonly handoff?: DocumentHandoff;
}

/** Inputs to the reports tab, including its optional download seams. */
export interface ReleasedReportsPageProps extends PatientPageProps {
  readonly reportDownload?: ReportDownloadSeam;
}

/**
 * Installed document handoff until a patient-document fetch route exists.
 *
 * The deployed report GET replies with an authorized reference, not bytes or a
 * signed URL, so this seam retains no document, checksum or clinical state. The
 * authorization is valid for the one confirmed action and must not outlive it;
 * a platform save action is a recorded dependency, not a local cache.
 */
function deferredDocumentHandoff(_authorization: DownloadAuthorization): void {
  // The reference is consumed by the host's download surface in a later release.
}

/** Reports tab: revisions currently released to the verified patient. */
export function ReleasedReportsPage({
  transport,
  locale,
  patientId,
  onSessionExpired,
  reportDownload,
}: ReleasedReportsPageProps): React.JSX.Element {
  const messages = useMemo(() => screenMessages(locale), [locale]);
  const load = useCallback(() => loadReports(transport), [transport]);

  /**
   * Confirm intent, authorize the tapped revision and hand off the reference.
   *
   * The client validates the OS credential before the request, while the
   * credentialed transport re-reads that credential and sends it in the
   * Authorization header. The validated token is never placed in the URL or
   * page state. An unauthenticated reply ends the session; every other refusal
   * produces no document and no cached clinical content.
   */
  const download = useMemo(() => {
    const tokens = reportDownload?.tokens ?? new SecureCredentialStore();
    const confirm = reportDownload?.confirm ?? createAlertConfirmer();
    const handoff = reportDownload?.handoff ?? deferredDocumentHandoff;
    const client = new ReleasedReportClient(
      { getJson: (path: string, _token: string) => transport.get<unknown>(path) },
      tokens,
      patientId,
    );
    return async (report: ReleasedReport): Promise<void> => {
      try {
        await downloadWithHandoff(client, report, confirm, handoff);
      } catch (error) {
        if (error instanceof TransportError && error.kind === 'unauthenticated') {
          onSessionExpired();
        }
        // A refused authorization leaves no document behind. The page stays
        // stateless, so the patient simply remains on the list and can retry.
      }
    };
  }, [reportDownload, transport, patientId, onSessionExpired]);

  return (
    <PatientPageFrame pageId="reports" locale={locale}>
      <PatientResource
        load={load}
        isEmpty={isEmptyRows}
        emptyMessage={appText(locale, 'emptyReports')}
        messages={messages}
        onSessionExpired={onSessionExpired}>
        {(reports) => (
          <ReleasedReportsScreen
            reports={reports}
            error={null}
            onDownload={(report) => {
              void download(report);
            }}
          />
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
