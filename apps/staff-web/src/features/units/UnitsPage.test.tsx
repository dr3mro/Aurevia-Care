import { Suspense } from 'react';
import type { ReactElement } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { ActiveOrganizationProvider } from '../../api/activeOrganization';
import type { RequestDescriptor } from '../../api/client';
import { TransportError, send } from '../../api/transport';
import type * as TransportModule from '../../api/transport';
import { stateViewText } from '../../components/stateViewMessages';
import { I18nProvider } from '../../i18n/I18nProvider';
import { ThemeProvider } from '../../theme/ThemeProvider';
import { alphaProfile, alphaUnitId, betaProfile, profileResponse, unpublishedProfile } from './fixtures';
import { unitsText } from './messages';
import { UnitsPage } from './UnitsPage';

vi.mock('../../api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);

function element(ui: ReactElement, organizationId = alphaProfile.organizationId) {
  return (
    <ThemeProvider>
      <I18nProvider initialLocale="en">
        <ActiveOrganizationProvider value={{ organizationId }}>
          <Suspense fallback={<div>Loading chunk</div>}>{ui}</Suspense>
        </ActiveOrganizationProvider>
      </I18nProvider>
    </ThemeProvider>
  );
}

function wrap(ui: ReactElement, organizationId?: string) {
  return render(element(ui, organizationId));
}

function descriptorAt(call: number): RequestDescriptor {
  return sendMock.mock.calls[call]![0] as RequestDescriptor;
}

function failure(kind: ConstructorParameters<typeof TransportError>[0]) {
  return new TransportError(kind, {
    correlationId: 'corr-units',
    code: undefined,
    retryAfterSeconds: undefined,
  });
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

beforeEach(() => {
  sendMock.mockReset();
});

describe('UnitsPage (MSG-06, ADM-01, WEB-09)', () => {
  it('shows the loading state until the profile resolves', async () => {
    const pending = deferred<ReturnType<typeof profileResponse>>();
    sendMock.mockReturnValueOnce(pending.promise);

    wrap(<UnitsPage unitId={alphaUnitId} todayCivilDate="2026-03-09" />);
    expect(screen.getByRole('heading', { name: stateViewText('en', 'loading') })).toBeTruthy();

    await act(async () => {
      pending.resolve(profileResponse(alphaProfile));
    });
    expect(await screen.findByText(alphaProfile.details.serviceHours)).toBeTruthy();
  });

  it('shows the empty state when no profile has been published', async () => {
    sendMock.mockResolvedValueOnce(profileResponse(unpublishedProfile));
    wrap(<UnitsPage unitId={alphaUnitId} todayCivilDate="2026-03-09" />);
    expect(await screen.findByText(stateViewText('en', 'empty'))).toBeTruthy();
    expect(screen.queryByRole('table')).toBeNull();
  });

  it('maps a forbidden load response to the forbidden state', async () => {
    sendMock.mockRejectedValueOnce(failure('forbidden'));
    wrap(<UnitsPage unitId={alphaUnitId} todayCivilDate="2026-03-09" />);
    expect(await screen.findByText(stateViewText('en', 'forbidden'))).toBeTruthy();
    expect(screen.getByText(stateViewText('en', 'forbiddenDetail'))).toBeTruthy();
  });

  it('uses the synthetic unit and today when props are omitted', async () => {
    sendMock.mockResolvedValue(profileResponse(alphaProfile));
    wrap(<UnitsPage />);
    expect(await screen.findByText(alphaProfile.details.topics)).toBeTruthy();
    expect(descriptorAt(0).url).toBe('/api/v1/units/synthetic-unit-001/service-profile');
  });

  it('sends a proposed change with a version precondition and reloads', async () => {
    sendMock.mockResolvedValue(profileResponse(alphaProfile));
    wrap(<UnitsPage unitId={alphaUnitId} todayCivilDate="2026-03-09" />);
    await screen.findByText(alphaProfile.details.serviceHours);

    fireEvent.click(screen.getByRole('button', { name: unitsText('en', 'proposeChanges') }));

    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(3));
    const command = descriptorAt(1);
    expect(command.method).toBe('POST');
    expect(command.url).toBe('/api/v1/units/synthetic-unit-001/service-profile');
    expect(command.headers['If-Match']).toBe(String(alphaProfile.version));
    expect(descriptorAt(2).method).toBe('GET');
  });

  it('surfaces a typed command failure above the editor', async () => {
    sendMock.mockResolvedValueOnce(profileResponse(alphaProfile));
    sendMock.mockRejectedValueOnce(failure('conflict'));
    wrap(<UnitsPage unitId={alphaUnitId} todayCivilDate="2026-03-09" />);
    await screen.findByText(alphaProfile.details.serviceHours);

    fireEvent.click(screen.getByRole('button', { name: unitsText('en', 'proposeChanges') }));

    expect(await screen.findByText(stateViewText('en', 'conflict'))).toBeTruthy();
    expect(screen.getByText(stateViewText('en', 'conflictDetail'))).toBeTruthy();
  });

  it('shows the correlation id for a typed server command failure', async () => {
    sendMock.mockResolvedValueOnce(profileResponse(alphaProfile));
    sendMock.mockRejectedValueOnce(failure('server'));
    wrap(<UnitsPage unitId={alphaUnitId} todayCivilDate="2026-03-09" />);
    await screen.findByText(alphaProfile.details.serviceHours);

    fireEvent.click(screen.getByRole('button', { name: unitsText('en', 'proposeChanges') }));

    expect(await screen.findByText(stateViewText('en', 'error'))).toBeTruthy();
    expect(screen.getByText('corr-units')).toBeTruthy();
  });

  it('normalises an untyped command rejection to the generic error state', async () => {
    sendMock.mockResolvedValueOnce(profileResponse(alphaProfile));
    sendMock.mockRejectedValueOnce(new Error('synthetic failure'));
    wrap(<UnitsPage unitId={alphaUnitId} todayCivilDate="2026-03-09" />);
    await screen.findByText(alphaProfile.details.serviceHours);

    fireEvent.click(screen.getByRole('button', { name: unitsText('en', 'proposeChanges') }));

    expect(await screen.findByText(stateViewText('en', 'error'))).toBeTruthy();
    expect(screen.getByText(stateViewText('en', 'correlationUnavailable'))).toBeTruthy();
  });

  it('sends an enable command for a unit with a named owner', async () => {
    sendMock.mockResolvedValue(profileResponse(alphaProfile));
    wrap(<UnitsPage unitId={alphaUnitId} todayCivilDate="2026-03-09" />);
    await screen.findByText(alphaProfile.details.serviceHours);

    fireEvent.click(screen.getByRole('button', { name: unitsText('en', 'enableUnit') }));

    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(3));
    expect(descriptorAt(1).url).toBe('/api/v1/units/synthetic-unit-001/enabled');
    expect(descriptorAt(1).body).toEqual({ enabled: true });
  });

  it('sends a disable command for an enabled unit', async () => {
    sendMock.mockResolvedValue(profileResponse(betaProfile));
    wrap(<UnitsPage unitId={betaUnitIdForTest()} todayCivilDate="2026-03-09" />);
    await screen.findByText(betaProfile.details.serviceHours);

    fireEvent.click(screen.getByRole('button', { name: unitsText('en', 'disableUnit') }));

    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(3));
    expect(descriptorAt(1).body).toEqual({ enabled: false });
  });

  it('sends an approval command for a pending version', async () => {
    sendMock.mockResolvedValue(profileResponse(alphaProfile));
    wrap(<UnitsPage unitId={alphaUnitId} todayCivilDate="2026-03-09" />);
    await screen.findByText(alphaProfile.details.serviceHours);

    fireEvent.click(screen.getByRole('button', { name: unitsText('en', 'approve') }));

    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(3));
    expect(descriptorAt(1).url).toBe(
      '/api/v1/units/synthetic-unit-001/service-profile/versions/3/approve',
    );
  });
});

/** The synthetic id of the beta fixture's care unit. */
function betaUnitIdForTest(): string {
  return betaProfile.unitId;
}
