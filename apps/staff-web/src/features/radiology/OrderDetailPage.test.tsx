import { Suspense } from 'react';
import type { ReactElement } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { ActiveOrganizationProvider } from '../../api/activeOrganization';
import type { RequestDescriptor } from '../../api/client';
import { TransportError, send } from '../../api/transport';
import type * as TransportModule from '../../api/transport';
import { createRoutes } from '../../app/routes';
import { RouterProvider } from '../../app/router';
import { stateViewText } from '../../components/stateViewMessages';
import { I18nProvider } from '../../i18n/I18nProvider';
import type { Locale } from '../../i18n/messages';
import { ThemeProvider } from '../../theme/ThemeProvider';
import type { ImagingOrderResponse, InterpretationResponse } from './api';
import {
  addendedOrder,
  alphaOrganizationId,
  interpretedOrder,
  receivedOrder,
  scheduledOrder,
  studyLinkedOrder,
} from './fixtures';
import type { ImagingOrder } from './domain';
import { OrderDetailPage } from './OrderDetailPage';

vi.mock('../../api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);
const patterns = ['/imaging/orders/:id'];

function descriptorAt(call: number): RequestDescriptor {
  return sendMock.mock.calls[call]![0] as RequestDescriptor;
}

function wireInterpretation(order: ImagingOrder): InterpretationResponse | null {
  const interpretation = order.interpretation;
  if (interpretation === null) {
    return null;
  }
  return {
    technique: interpretation.technique,
    comparison: interpretation.comparison,
    findings: interpretation.findings,
    impression: interpretation.impression,
    followUp: interpretation.followUp,
    signedBy: interpretation.signedBy,
    signedAt: interpretation.signedAt,
    addenda: interpretation.addenda.map((addendum) => ({
      id: addendum.id,
      text: addendum.text,
      author: addendum.author,
      addedAt: addendum.addedAt,
    })),
  };
}

function wire(order: ImagingOrder): ImagingOrderResponse {
  return {
    id: order.id,
    version: order.version,
    patientId: order.patientId,
    patientDisplayName: order.patientName.latinName,
    patientLocalName: order.patientName.localName ?? null,
    urgency: order.urgency,
    clinicalQuestion: order.clinicalQuestion,
    state: order.state === 'study-linked' ? 'study_linked' : order.state,
    accessionNo: order.accessionNo,
    modality: order.modality,
    scheduledAt: order.scheduledAt,
    studyInstanceUid: order.studyInstanceUid,
    interpretation: wireInterpretation(order),
  };
}

function element(
  ui: ReactElement,
  organizationId = alphaOrganizationId,
  locale: Locale = 'en',
  routePatterns: readonly string[] = patterns,
  path = '/imaging/orders/imaging-order-a1',
) {
  return (
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <ActiveOrganizationProvider value={{ organizationId }}>
          <RouterProvider patterns={routePatterns} initialPath={path}>
            {ui}
          </RouterProvider>
        </ActiveOrganizationProvider>
      </I18nProvider>
    </ThemeProvider>
  );
}

function wrap(order: ImagingOrder | null = receivedOrder, locale: Locale = 'en') {
  sendMock.mockResolvedValueOnce(order === null ? null : wire(order));
  return render(element(<OrderDetailPage />, alphaOrganizationId, locale));
}

function failure(kind: ConstructorParameters<typeof TransportError>[0]) {
  return new TransportError(kind, {
    correlationId: `corr-${kind}`,
    code: kind.toUpperCase(),
    retryAfterSeconds: undefined,
  });
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((res) => {
    resolve = res;
  });
  return { promise, resolve };
}

beforeEach(() => {
  sendMock.mockReset();
});

describe('OrderDetailPage states', () => {
  it('shows loading until the order resolves', async () => {
    const pending = deferred<ImagingOrderResponse>();
    sendMock.mockReturnValueOnce(pending.promise);
    render(element(<OrderDetailPage />));
    expect(screen.getByRole('heading', { name: stateViewText('en', 'loading') })).toBeTruthy();

    pending.resolve(wire(receivedOrder));
    expect(await screen.findByText('Order detail')).toBeTruthy();
  });

  it.each(['server', 'timeout', 'offline', 'invalid', 'rate-limited'] as const)(
    'shows the error state with a correlation id for %s',
    async (kind) => {
      sendMock.mockRejectedValueOnce(failure(kind));
      render(element(<OrderDetailPage />));
      expect(await screen.findByText(stateViewText('en', 'error'))).toBeTruthy();
      expect(screen.getByText(`corr-${kind}`)).toBeTruthy();
    },
  );

  it('shows the forbidden, conflict and session-expired states', async () => {
    for (const kind of ['forbidden', 'conflict', 'session-expired'] as const) {
      sendMock.mockRejectedValueOnce(failure(kind));
      const view = render(element(<OrderDetailPage />));
      const label =
        kind === 'forbidden'
          ? stateViewText('en', 'forbidden')
          : kind === 'conflict'
            ? stateViewText('en', 'conflict')
            : stateViewText('en', 'sessionExpired');
      expect(await screen.findByText(label)).toBeTruthy();
      view.unmount();
    }
  });

  it('shows the empty state when the order does not exist', async () => {
    wrap(null);
    expect(await screen.findByText(stateViewText('en', 'empty'))).toBeTruthy();
  });

  it('uses an empty identifier when no route parameter is bound', async () => {
    sendMock.mockResolvedValueOnce(wire(receivedOrder));
    render(element(<OrderDetailPage />, alphaOrganizationId, 'en', [], '/imaging/orders'));
    expect(await screen.findByText('Order detail')).toBeTruthy();
    expect(descriptorAt(0).url).toBe('/api/v1/imaging/orders/');
  });
});

describe('OrderDetailPage commands', () => {
  it('schedules an order and advances its state', async () => {
    wrap(receivedOrder);
    sendMock.mockResolvedValueOnce(wire(scheduledOrder));
    await screen.findByText('Order detail');

    fireEvent.change(screen.getByLabelText('Accession number'), {
      target: { value: 'ACC-2026-0001' },
    });
    fireEvent.change(screen.getByLabelText('Modality'), { target: { value: 'CT' } });
    fireEvent.change(screen.getByLabelText('Scheduled instant (UTC ISO)'), {
      target: { value: '2026-03-08T08:00:00Z' },
    });
    fireEvent.submit(screen.getByRole('form', { name: 'Schedule the examination' }));

    expect(await screen.findByText('Scheduled')).toBeTruthy();
    const command = descriptorAt(1);
    expect(command.method).toBe('POST');
    expect(command.url).toBe('/api/v1/imaging/orders/imaging-order-a1/schedule');
    expect(command.headers['If-Match']).toBe('1');
    expect(command.headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('uses the API version so sequential steps do not resend a stale precondition', async () => {
    wrap(receivedOrder);
    sendMock
      .mockResolvedValueOnce(wire(scheduledOrder))
      .mockResolvedValueOnce(wire(studyLinkedOrder));
    await screen.findByText('Order detail');

    fireEvent.change(screen.getByLabelText('Accession number'), {
      target: { value: 'ACC-2026-0001' },
    });
    fireEvent.change(screen.getByLabelText('Modality'), { target: { value: 'CT' } });
    fireEvent.change(screen.getByLabelText('Scheduled instant (UTC ISO)'), {
      target: { value: '2026-03-08T08:00:00Z' },
    });
    fireEvent.submit(screen.getByRole('form', { name: 'Schedule the examination' }));
    expect(await screen.findByText('Scheduled')).toBeTruthy();

    fireEvent.change(screen.getByLabelText('Study Instance UID'), {
      target: { value: '1.2.3' },
    });
    fireEvent.change(screen.getByLabelText('Study patient identifier'), {
      target: { value: 'patient-a1' },
    });
    fireEvent.change(screen.getByLabelText('Study accession number'), {
      target: { value: 'ACC-2026-0001' },
    });
    fireEvent.submit(screen.getByRole('form', { name: 'Link the external study' }));
    expect(await screen.findByText('Study linked')).toBeTruthy();

    expect(descriptorAt(1).headers['If-Match']).toBe('1');
    expect(descriptorAt(2).headers['If-Match']).toBe('2');
    expect(sendMock).toHaveBeenCalledTimes(3);
  });

  it('refuses a wrong-patient study without saving it', async () => {
    wrap(scheduledOrder);
    await screen.findByText('Order detail');

    fireEvent.change(screen.getByLabelText('Study Instance UID'), {
      target: { value: '1.2.3' },
    });
    fireEvent.change(screen.getByLabelText('Study patient identifier'), {
      target: { value: 'patient-b1' },
    });
    fireEvent.change(screen.getByLabelText('Study accession number'), {
      target: { value: 'ACC-2026-0001' },
    });
    fireEvent.submit(screen.getByRole('form', { name: 'Link the external study' }));

    const alert = await screen.findByRole('alert', { name: 'Study association refused' });
    expect(within(alert).getByText('The study patient does not match this order.')).toBeTruthy();
    expect(sendMock).toHaveBeenCalledTimes(1);
  });

  it('links a verified study and advances to the study-linked state', async () => {
    wrap(scheduledOrder);
    sendMock.mockResolvedValueOnce(wire(studyLinkedOrder));
    await screen.findByText('Order detail');

    fireEvent.change(screen.getByLabelText('Study Instance UID'), {
      target: { value: '1.2.826.0.1.3680043.2.1125.synthetic' },
    });
    fireEvent.change(screen.getByLabelText('Study patient identifier'), {
      target: { value: 'patient-a1' },
    });
    fireEvent.change(screen.getByLabelText('Study accession number'), {
      target: { value: 'ACC-2026-0001' },
    });
    fireEvent.submit(screen.getByRole('form', { name: 'Link the external study' }));

    expect(await screen.findByText('Study linked')).toBeTruthy();
    expect(descriptorAt(1).url).toBe('/api/v1/imaging/orders/imaging-order-a1/study');
    expect(descriptorAt(1).headers['If-Match']).toBe('2');
  });

  it('signs an interpretation and shows the signed panel', async () => {
    wrap(studyLinkedOrder);
    sendMock.mockResolvedValueOnce(wire(interpretedOrder));
    await screen.findByText('Order detail');

    for (const label of ['Technique', 'Comparison', 'Findings', 'Impression', 'Follow-up']) {
      fireEvent.change(screen.getByLabelText(label), { target: { value: label } });
    }
    fireEvent.submit(screen.getByRole('form', { name: 'Sign the interpretation' }));

    const signed = await screen.findByRole('region', { name: 'Signed interpretation' });
    expect(within(signed).getByText('synthetic.radiologist')).toBeTruthy();
    const command = descriptorAt(1);
    expect(command.url).toBe('/api/v1/imaging/orders/imaging-order-a1/interpretation');
    expect(command.body).toEqual({
      technique: 'Technique',
      comparison: 'Comparison',
      findings: 'Findings',
      impression: 'Impression',
      followUp: 'Follow-up',
    });
  });

  it('carries a new addendum into the referrer view', async () => {
    wrap(interpretedOrder);
    sendMock.mockResolvedValueOnce(wire(addendedOrder));
    const referrer = await screen.findByRole('region', { name: 'Referrer view' });
    expect(within(referrer).queryByText('Synthetic addendum reaches the referrer')).toBeNull();

    fireEvent.change(screen.getByLabelText('Addendum text'), {
      target: { value: 'Synthetic addendum reaches the referrer' },
    });
    fireEvent.submit(screen.getByRole('form', { name: 'Add addendum' }));

    await waitFor(() =>
      expect(
        within(screen.getByRole('region', { name: 'Referrer view' })).getByText(
          'Synthetic addendum reaches the referrer',
        ),
      ).toBeTruthy(),
    );
    expect(descriptorAt(1).url).toBe('/api/v1/imaging/orders/imaging-order-a1/addenda');
    expect(descriptorAt(1).body).toEqual({ text: 'Synthetic addendum reaches the referrer' });
  });

  it('keeps typed work in the conflict state', async () => {
    wrap(receivedOrder);
    sendMock.mockRejectedValueOnce(failure('conflict'));
    await screen.findByText('Order detail');

    fireEvent.change(screen.getByLabelText('Accession number'), {
      target: { value: 'ACC-KEEP' },
    });
    fireEvent.change(screen.getByLabelText('Modality'), { target: { value: 'CT' } });
    fireEvent.submit(screen.getByRole('form', { name: 'Schedule the examination' }));

    expect(await screen.findByText(stateViewText('en', 'conflict'))).toBeTruthy();
    expect(screen.getByTestId('radiology-command-error')).toBeTruthy();
    expect((screen.getByLabelText('Accession number') as HTMLInputElement).value).toBe('ACC-KEEP');
  });

  it('reloads an authoritative version after a conflict', async () => {
    wrap(receivedOrder);
    sendMock.mockRejectedValueOnce(failure('conflict'));
    await screen.findByText('Order detail');

    fireEvent.change(screen.getByLabelText('Accession number'), {
      target: { value: 'ACC-KEEP' },
    });
    fireEvent.change(screen.getByLabelText('Modality'), { target: { value: 'CT' } });
    fireEvent.submit(screen.getByRole('form', { name: 'Schedule the examination' }));
    expect(await screen.findByText(stateViewText('en', 'conflict'))).toBeTruthy();

    sendMock.mockResolvedValueOnce(wire(receivedOrder));
    fireEvent.click(screen.getByRole('button', { name: 'Reload the order' }));

    await waitFor(() => expect(screen.queryByTestId('radiology-command-error')).toBeNull());
    expect(sendMock).toHaveBeenCalledTimes(3);
    expect(descriptorAt(2).method).toBe('GET');
  });

  it('renders the whole workflow in Arabic under RTL', async () => {
    wrap(receivedOrder, 'ar');
    expect(document.documentElement.dir).toBe('rtl');
    expect(await screen.findByRole('heading', { name: 'تفاصيل الطلب' })).toBeTruthy();
    expect(screen.getByRole('form', { name: 'جدولة الفحص' })).toBeTruthy();
  });
});

describe('OrderDetailPage command failures', () => {
  async function fillStudy() {
    fireEvent.change(screen.getByLabelText('Study Instance UID'), {
      target: { value: '1.2.3' },
    });
    fireEvent.change(screen.getByLabelText('Study patient identifier'), {
      target: { value: 'patient-a1' },
    });
    fireEvent.change(screen.getByLabelText('Study accession number'), {
      target: { value: 'ACC-2026-0001' },
    });
  }

  it('shows a typed error when the study-link command fails', async () => {
    wrap(scheduledOrder);
    sendMock.mockRejectedValueOnce(failure('server'));
    await screen.findByText('Order detail');
    await fillStudy();

    fireEvent.submit(screen.getByRole('form', { name: 'Link the external study' }));
    expect(await screen.findByText(stateViewText('en', 'error'))).toBeTruthy();
    expect(screen.getByText('corr-server')).toBeTruthy();
    expect(screen.queryByRole('alert', { name: 'Study association refused' })).toBeNull();
  });

  it('shows a typed error when the sign command fails', async () => {
    wrap(studyLinkedOrder);
    sendMock.mockRejectedValueOnce(failure('server'));
    await screen.findByText('Order detail');
    for (const label of ['Technique', 'Comparison', 'Findings', 'Impression', 'Follow-up']) {
      fireEvent.change(screen.getByLabelText(label), { target: { value: label } });
    }

    fireEvent.submit(screen.getByRole('form', { name: 'Sign the interpretation' }));
    expect(await screen.findByText(stateViewText('en', 'error'))).toBeTruthy();
    expect(screen.getByText('corr-server')).toBeTruthy();
  });

  it('shows a typed error when the addendum command fails', async () => {
    wrap(interpretedOrder);
    sendMock.mockRejectedValueOnce(failure('server'));
    await screen.findByRole('region', { name: 'Referrer view' });

    fireEvent.change(screen.getByLabelText('Addendum text'), {
      target: { value: 'Synthetic failing addendum' },
    });
    fireEvent.submit(screen.getByRole('form', { name: 'Add addendum' }));
    expect(await screen.findByText(stateViewText('en', 'error'))).toBeTruthy();
    expect(screen.getByText('corr-server')).toBeTruthy();
  });
});

describe('radiography route registration', () => {
  it('registers both imaging routes', () => {
    const patterns = createRoutes(false).map((route) => route.pattern);
    expect(patterns).toContain('/imaging/orders');
    expect(patterns).toContain('/imaging/orders/:id');
  });

  it('lazily renders the detail page with a bound order identifier', async () => {
    sendMock.mockResolvedValueOnce(wire(scheduledOrder));
    const route = createRoutes(false).find((candidate) => candidate.pattern === '/imaging/orders/:id')!;
    const Page = route.component;
    render(
      <Suspense fallback={<p>Loading route</p>}>
        {element(<Page />, alphaOrganizationId, 'en', patterns, '/imaging/orders/imaging-order-a1')}
      </Suspense>,
    );
    expect(await screen.findByText('Order detail')).toBeTruthy();
    expect(descriptorAt(0).url).toBe('/api/v1/imaging/orders/imaging-order-a1');
  });

  it('lazily renders the order list page', async () => {
    sendMock.mockResolvedValueOnce({ items: [wire(receivedOrder)], nextCursor: null });
    const route = createRoutes(false).find((candidate) => candidate.pattern === '/imaging/orders')!;
    const Page = route.component;
    render(
      <Suspense fallback={<p>Loading route</p>}>
        {element(<Page />, alphaOrganizationId, 'en', ['/imaging/orders'], '/imaging/orders')}
      </Suspense>,
    );
    expect(await screen.findByText('Imaging orders')).toBeTruthy();
  });
});
