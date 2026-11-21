import { useCallback, useEffect, useMemo, useState } from 'react';
import { useActiveOrganization } from '../../api/activeOrganization';
import { TransportError } from '../../api/transport';
import { useApi } from '../../api/useApi';
import { Button } from '../../components/Button';
import { EmptyState, LoadingState, StateView } from '../../components/StateViews';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
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

/**
 * Load one day or week of appointments from the real API, page through the
 * cursor, and dispatch commands through callbacks on the presentational screen.
 * A failed command is shown above the screen so the booking draft is retained.
 */
export function SchedulingPage({ anchorCivilDate }: SchedulingPageProps) {
  const organizationId = useActiveOrganization();
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const anchor = anchorCivilDate ?? new Date().toISOString().slice(0, 10);
  const [range, setRange] = useState<WorklistRange>('day');
  const [cursor, setCursor] = useState<string | undefined>(undefined);
  const [accumulated, setAccumulated] = useState<readonly AppointmentViewResponse[]>([]);
  const [nextCursor, setNextCursor] = useState<string | undefined>(undefined);
  const [commandError, setCommandError] = useState<TransportError | undefined>(undefined);

  const bounds = rangeWindow(range, anchor);
  const result = useApi<AppointmentPageResponse>(
    () => listAppointments({ from: bounds.from, to: bounds.to, cursor }, organizationId),
    [range, anchor, cursor],
  );

  // Append each resolved page. The dependency is the page object, not the
  // result wrapper, so an unrelated render never duplicates rows.
  useEffect(() => {
    if (result.status !== 'ready') {
      return;
    }
    const page = result.data;
    setAccumulated((current) => [...current, ...page.items]);
    setNextCursor(page.nextCursor ?? undefined);
  }, [result.status, result.data]);

  // A tenant switch or a different anchor invalidates every accumulated page.
  useEffect(() => {
    setCursor(undefined);
    setAccumulated([]);
    setNextCursor(undefined);
    setCommandError(undefined);
  }, [organizationId, anchor]);

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
  }, [refetch]);

  async function handleCheckIn(id: string) {
    const target = loaded.find((appointment) => appointment.id === id)!;
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

  return (
    <div style={{ display: 'grid', gap: tokens.spacing.lg }}>
      {commandError !== undefined && (
        <div data-testid="scheduling-command-error">{StateView.forTransportError(commandError)}</div>
      )}
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
            onCheckIn={handleCheckIn}
            onBook={handleBook}
          />
          {nextCursor !== undefined && (
            <Button onClick={handleLoadMore} disabled={result.status === 'loading'}>
              {schedulingText(locale, 'loadMore')}
            </Button>
          )}
        </>
      )}
    </div>
  );
}
