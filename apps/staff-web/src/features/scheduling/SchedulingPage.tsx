import { useCallback, useMemo, useState } from 'react';
import { useActiveOrganization } from '../../api/activeOrganization';
import { TransportError } from '../../api/transport';
import { useApi } from '../../api/useApi';
import { Button } from '../../components/Button';
import { defined } from '../../components/invariant';
import { EmptyState, LoadingState, StateView } from '../../components/StateViews';
import { useI18n } from '../../i18n/I18nProvider';
import { useBreakpoint } from '../../theme/useBreakpoint';
import type { BookingInput } from './BookingForm';
import { SchedulingScreen } from './SchedulingScreen';
import { book, checkIn, listAppointments, rangeWindow, toAppointment } from './api';
import type { AppointmentPageResponse, AppointmentViewResponse } from './api';
import type { Appointment, WorklistRange } from './domain';
import { schedulingText } from './messages';

/** Props for the API-backed scheduling page. */
export interface SchedulingPageProps {
  /**
   * Civil `YYYY-MM-DD` anchoring the day/week query. Tests and previews pin it;
   * the running app defaults to the current UTC date.
   */
  anchorCivilDate?: string | undefined;
}

/** Normalise an unexpected command rejection to a typed transport failure. */
function toTransportError(failure: unknown): TransportError {
  if (failure instanceof TransportError) {
    return failure;
  }
  return new TransportError('server', {
    correlationId: undefined,
    code: undefined,
    retryAfterSeconds: undefined,
  });
}

/** The civil date the worklist is anchored to; today when none is supplied. */
function resolveAnchor(anchorCivilDate: string | undefined): string {
  return anchorCivilDate ?? new Date().toISOString().slice(0, 10);
}

/**
 * Load one day or week of appointments from the real API, page through the
 * cursor, and dispatch commands through callbacks on the presentational screen.
 * A failed command is shown above the screen so the booking draft is retained.
 *
 * The root uses the shared `page` utility rather than a local grid: it owns the
 * single-column track that stacks on a phone, the centred reading width and the
 * logical safe-area padding that mirrors under right-to-left (WEB-07, UX-01).
 * The breakpoint hook exposes the active band for structure checks in jsdom,
 * where media queries cannot be evaluated; width changes layout only, never
 * authorization, data or the command flow.
 */
export function SchedulingPage({ anchorCivilDate }: SchedulingPageProps) {
  const organizationId = useActiveOrganization();
  const { locale } = useI18n();
  const breakpoint = useBreakpoint();
  const anchor = resolveAnchor(anchorCivilDate);
  const [range, setRange] = useState<WorklistRange>('day');
  const [cursor, setCursor] = useState<string | undefined>(undefined);
  const [accumulated, setAccumulated] = useState<readonly AppointmentViewResponse[]>([]);
  const [nextCursor, setNextCursor] = useState<string | undefined>(undefined);
  const [commandError, setCommandError] = useState<TransportError | undefined>(undefined);
  const [appliedPage, setAppliedPage] = useState<AppointmentPageResponse | undefined>(undefined);
  const [appliedScope, setAppliedScope] = useState(`${organizationId}|${anchor}`);

  const bounds = rangeWindow(range, anchor);
  const result = useApi<AppointmentPageResponse>(
    () => listAppointments({ from: bounds.from, to: bounds.to, cursor }, organizationId),
    [range, anchor, cursor],
  );

  // Append each resolved page. The dependency is the page object, not the
  // result wrapper, so an unrelated render never duplicates rows.
  if (result.status === 'ready' && result.data !== appliedPage) {
    const page = result.data;
    setAppliedPage(page);
    setAccumulated((current) => [...current, ...page.items]);
    setNextCursor(page.nextCursor ?? undefined);
  }

  // A tenant switch or a different anchor invalidates every accumulated page.
  const scope = `${organizationId}|${anchor}`;
  if (appliedScope !== scope) {
    setAppliedScope(scope);
    setCursor(undefined);
    setAccumulated([]);
    setNextCursor(undefined);
    setCommandError(undefined);
  }

  const loaded = useMemo<Appointment[]>(
    () => accumulated.map((view) => toAppointment(view, organizationId)),
    [accumulated, organizationId],
  );

  const { reload: refetch } = result;
  const reload = useCallback(() => {
    setCursor(undefined);
    setAccumulated([]);
    setNextCursor(undefined);
    setCommandError(undefined);
    refetch();
  }, [refetch, setCursor, setAccumulated, setNextCursor, setCommandError]);

  async function handleCheckIn(id: string) {
    const target = defined(
      loaded.find((appointment) => appointment.id === id),
      'check-in target',
    );
    try {
      await checkIn(id, target.version, organizationId);
      setCommandError(undefined);
      reload();
    } catch (failure: unknown) {
      setCommandError(toTransportError(failure));
    }
  }

  async function handleBook(request: BookingInput) {
    try {
      await book(
        {
          facilityCode: request.facilityCode,
          patientId: request.patientId,
          visitType: request.visitType,
          clinicianCode: request.resourceId,
          roomCode: null,
          startsAt: request.scheduledAt,
          overrideReason: null,
        },
        organizationId,
      );
      setCommandError(undefined);
      reload();
    } catch (failure: unknown) {
      setCommandError(toTransportError(failure));
    }
  }

  function handleRangeChange(next: WorklistRange) {
    setRange(next);
    setCursor(undefined);
    setAccumulated([]);
    setNextCursor(undefined);
    setCommandError(undefined);
  }

  function handleLoadMore() {
    setCursor(nextCursor);
  }

  // The page utility caps prose at the reading width, but the worklist is a
  // table and must use the full available inline space. responsive.css has no
  // wide-page class, so undo the cap with a logical inline style rather than
  // editing the shared stylesheet.
  return (
    <div
      className="page"
      data-scheduling-page
      data-breakpoint={breakpoint}
      style={{ maxInlineSize: 'none' }}
    >
      {commandError !== undefined ? (
        <div data-testid="scheduling-command-error">
          {StateView.forTransportError(commandError)}
        </div>
      ) : null}
      {result.status === 'loading' && loaded.length === 0 ? (
        <LoadingState />
      ) : result.status === 'error' && loaded.length === 0 ? (
        StateView.forTransportError(result.error)
      ) : loaded.length === 0 ? (
        <EmptyState />
      ) : (
        <>
          <SchedulingScreen
            appointments={loaded}
            organizationId={organizationId}
            range={range}
            onRangeChange={handleRangeChange}
            onCheckIn={(id) => {
              void handleCheckIn(id);
            }}
            onBook={(request) => {
              void handleBook(request);
            }}
          />
          {nextCursor !== undefined ? (
            <Button onClick={handleLoadMore} disabled={result.status === 'loading'}>
              {schedulingText(locale, 'loadMore')}
            </Button>
          ) : null}
        </>
      )}
    </div>
  );
}
