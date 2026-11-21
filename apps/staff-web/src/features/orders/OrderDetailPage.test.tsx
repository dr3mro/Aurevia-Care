import { Suspense } from 'react';
import type { ReactElement } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { ActiveOrganizationProvider } from '../../api/activeOrganization';
import type { RequestDescriptor } from '../../api/client';
import { TransportError, send } from '../../api/transport';
import type * as TransportModule from '../../api/transport';
import { RouterProvider } from '../../app/router';
import { createRoutes } from '../../app/routes';
import { stateViewText } from '../../components/stateViewMessages';
import { I18nProvider } from '../../i18n/I18nProvider';
import { ThemeProvider } from '../../theme/ThemeProvider';
import { syntheticOrganizations } from '../../fixtures/synthetic';
import { OrderDetailPage } from './OrderDetailPage';

const alphaOrganizationId = syntheticOrganizations[0]!.id;
import { ordersText } from './messages';

vi.mock('../../api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);

function orderWire(overrides: Record<string, unknown> = {}) {
  return {
    id: 'order-001',
    version: 3,
    patientId: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
    encounterRef: 'enc-1',
    orderCode: 'ORD-1',
    priority: 'urgent' as const,
    clinicalQuestion: 'Synthetic question',
    destinationOrg: '22222222-2222-2222-2222-222222222222',
    sourceActor: 'synthetic.source',
    responsibleClinician: 'synthetic.clinician',
    workQueue: 'queue-1',
    authorizationId: 'auth-1',
    snapshotId: 'snapshot-1',
    snapshotFingerprint: 'fingerprint-1',
    disclosureId: 'disclosure-1',
    workItemId: 'work-1',
    createdAt: '2026-11-21T09:00:00Z',
    ...overrides,
  };
}

function eventWire(overrides: Record<string, unknown> = {}) {
  return {
    id: 'event-1',
    orderVersion: 3,
    action: 'accept',
    fromState: 'requested',
    toState: 'accepted',
    reason: null,
    actorId: 'synthetic.clinician',
    occurredAt: '2026-11-21T10:00:00Z',
    ...overrides,
  };
}

/** Mutable response router, so a test can fail only the next command. */
let handler: (descriptor: RequestDescriptor) => Promise<unknown>;
let getCount: number;
let deferGetsAfter: number;

function respond(history: readonly unknown[] = [eventWire()]): void {
  getCount = 0;
  deferGetsAfter = -1;
  handler = (descriptor) => {
    const d = descriptor;
    if (d.method === 'GET') {
      getCount += 1;
      if (deferGetsAfter >= 0 && getCount > deferGetsAfter) {
        return new Promise(() => undefined);
      }
      return Promise.resolve(d.url.includes('/history') ? history : orderWire());
    }
    return Promise.resolve({});
  };
  sendMock.mockImplementation((descriptor) => handler(descriptor as RequestDescriptor));
}

function element(ui: ReactElement) {
  return (
    <ThemeProvider>
      <I18nProvider initialLocale="en">
        <ActiveOrganizationProvider value={{ organizationId: alphaOrganizationId }}>
          {ui}
        </ActiveOrganizationProvider>
      </I18nProvider>
    </ThemeProvider>
  );
}

/** Bind the `:id` parameter by default; one test deliberately omits it. */
function wrap(ui: ReactElement = <OrderDetailPage roleOverride="clinician" />, withRouter = true) {
  const content = withRouter ? (
    <RouterProvider patterns={['/orders/:id']} initialPath="/orders/order-001">
      {ui}
    </RouterProvider>
  ) : (
    ui
  );
  return render(element(content));
}

/** Keep every response failing with a conflict until the test changes it. */
function rejectAllResponses(kind: ConstructorParameters<typeof TransportError>[0]): void {
  handler = (descriptor) => {
    if (descriptor.method === 'POST') {
      return Promise.reject(transportFailure(kind));
    }
    return descriptor.url.includes('/history')
      ? Promise.resolve([eventWire({ toState: 'requested' })])
      : Promise.resolve(orderWire());
  };
  sendMock.mockImplementation((descriptor) => handler(descriptor as RequestDescriptor));
}

function descriptorAt(index: number): RequestDescriptor {
  return sendMock.mock.calls[index]![0] as RequestDescriptor;
}

function posts(): RequestDescriptor[] {
  return sendMock.mock.calls
    .map((call) => call[0] as RequestDescriptor)
    .filter((descriptor) => descriptor.method === 'POST');
}

function transportFailure(kind: ConstructorParameters<typeof TransportError>[0], correlationId = 'corr-1') {
  return new TransportError(kind, {
    correlationId,
    code: undefined,
    retryAfterSeconds: undefined,
  });
}

function submitReason() {
  fireEvent.submit(screen.getByRole('button', { name: 'Send response' }).closest('form')!);
}

beforeEach(() => {
  sendMock.mockReset();
  respond();
  window.history.replaceState(null, '', '/');
});

describe('OrderDetailPage load states', () => {
  it('shows the loading state until the order and history resolve', async () => {
    let release!: () => void;
    const barrier = new Promise<void>((resolve) => {
      release = resolve;
    });
    handler = (descriptor) =>
      barrier.then(() =>
        descriptor.url.includes('/history') ? [eventWire()] : orderWire(),
      );
    sendMock.mockImplementation((descriptor) => handler(descriptor as RequestDescriptor));

    wrap();
    expect(screen.getByRole('heading', { name: stateViewText('en', 'loading') })).toBeTruthy();
    release();
    expect(await screen.findByText('ORD-1')).toBeTruthy();
  });

  it.each(['server', 'timeout', 'offline', 'invalid', 'rate-limited'] as const)(
    'shows the error state with a correlation id for %s',
    async (kind) => {
      sendMock.mockRejectedValue(transportFailure(kind, `corr-${kind}`));
      wrap();
      expect(await screen.findByText(stateViewText('en', 'error'))).toBeTruthy();
      expect(screen.getByText(`corr-${kind}`)).toBeTruthy();
    },
  );

  it('shows the forbidden state when the role cannot read the order', async () => {
    sendMock.mockRejectedValue(transportFailure('forbidden'));
    wrap();
    expect(await screen.findByText(stateViewText('en', 'forbidden'))).toBeTruthy();
  });

  it('shows the session-expired state when the session ended', async () => {
    sendMock.mockRejectedValue(transportFailure('session-expired'));
    wrap();
    expect(await screen.findByText(stateViewText('en', 'sessionExpired'))).toBeTruthy();
  });

  it('renders the released fields and the newest task state from history', async () => {
    respond([eventWire({ reason: 'Synthetic recorded reason' })]);
    wrap();
    expect(await screen.findByText('ORD-1')).toBeTruthy();
    expect(screen.getByTestId('orders-task-state').textContent).toBe(
      ordersText('en', 'stateAccepted'),
    );
    expect(screen.getByText('Synthetic question')).toBeTruthy();
    expect(screen.getByText('Requested → Accepted')).toBeTruthy();
    expect(screen.getByText('accepted')).toBeTruthy();
    expect(screen.getAllByText('synthetic.clinician')).toHaveLength(2);
    expect(screen.getByText('Synthetic recorded reason')).toBeTruthy();
  });

  it('renders the no-history state for a newly released order', async () => {
    respond([]);
    wrap();
    expect(await screen.findByText(ordersText('en', 'noHistory'))).toBeTruthy();
    expect(ordersText('en', 'noHistory')).toContain('No recipient response');
  });
});

describe('OrderDetailPage role-scoped actions', () => {
  it('offers accept, reject and clarify to a recipient role on a requested order', async () => {
    respond([eventWire({ toState: 'requested' })]);
    wrap();
    expect(await screen.findByRole('button', { name: 'Accept' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Reject' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Request clarification' })).toBeTruthy();
    expect(screen.queryByText(ordersText('en', 'noActions'))).toBeNull();
  });

  it('offers no response to a role that cannot answer an order', async () => {
    respond([eventWire({ toState: 'requested' })]);
    wrap(<OrderDetailPage roleOverride="tenant_admin" />);
    expect(await screen.findByText(ordersText('en', 'noActions'))).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Accept' })).toBeNull();
  });

  it('offers no first response after the requested state', async () => {
    wrap();
    await screen.findByTestId('orders-task-state');
    expect(screen.getByTestId('orders-task-state').textContent).toBe(
      ordersText('en', 'stateAccepted'),
    );
    expect(screen.queryByRole('button', { name: 'Accept' })).toBeNull();
  });

  it('falls back to the synthetic session role when no override is supplied', async () => {
    respond([eventWire({ toState: 'requested' })]);
    wrap(<OrderDetailPage />);
    expect(await screen.findByText(ordersText('en', 'noActions'))).toBeTruthy();
  });

  it('falls back to an empty id when no route provider is mounted', async () => {
    respond();
    wrap(<OrderDetailPage roleOverride="clinician" />, false);
    await screen.findByText('ORD-1');
    expect(descriptorAt(0).url).toBe('/api/v1/orders/');
  });
});

describe('OrderDetailPage commands', () => {
  beforeEach(() => {
    respond([eventWire({ toState: 'requested' })]);
  });

  it('accepts with the read version and a fresh idempotency key', async () => {
    deferGetsAfter = 2;
    wrap();
    await screen.findByRole('button', { name: 'Accept' });

    fireEvent.click(screen.getByRole('button', { name: 'Accept' }));

    await waitFor(() => expect(posts()).toHaveLength(1));
    const command = posts()[0]!;
    expect(command.url).toBe('/api/v1/xch03/orders/order-001/accept');
    expect(command.headers['If-Match']).toBe('3');
    expect(command.headers['Idempotency-Key']).toBeTruthy();
    expect(command.body).toMatchObject({ localPatientId: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa' });
    expect(await screen.findByTestId('orders-action-status')).toBeTruthy();
  });

  it('requires a reason before sending reject and shows a linked error summary', async () => {
    wrap();
    fireEvent.click(await screen.findByRole('button', { name: 'Reject' }));
    submitReason();

    expect(await screen.findByRole('alert', { name: 'There is a problem' })).toBeTruthy();
    expect(screen.getByRole('textbox').getAttribute('aria-invalid')).toBe('true');
    expect(posts()).toHaveLength(0);

    fireEvent.change(screen.getByLabelText('Reason (required)'), {
      target: { value: 'Specimen details missing' },
    });
    submitReason();

    await waitFor(() => expect(posts()).toHaveLength(1));
    expect(posts()[0]!.url).toBe('/api/v1/xch03/orders/order-001/reject');
    expect(posts()[0]!.body).toEqual({ reason: 'Specimen details missing' });
  });

  it('requires a question before sending clarify and cancels the form cleanly', async () => {
    wrap();
    fireEvent.click(await screen.findByRole('button', { name: 'Request clarification' }));
    expect(screen.getByLabelText('Question for the sender (required)')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByLabelText('Question for the sender (required)')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'Request clarification' }));
    fireEvent.change(screen.getByLabelText('Question for the sender (required)'), {
      target: { value: 'Which specimen was collected?' },
    });
    submitReason();

    await waitFor(() => expect(posts()).toHaveLength(1));
    expect(posts()[0]!.url).toBe('/api/v1/xch03/orders/order-001/clarify');
    expect(posts()[0]!.body).toEqual({ question: 'Which specimen was collected?' });
  });

});

describe('OrderDetailPage conflict and idempotency', () => {
  beforeEach(() => {
    respond([eventWire({ toState: 'requested' })]);
  });

  it('keeps the typed reason and reuses the key on a 409 retry, then issues a new key', async () => {
    wrap();
    fireEvent.click(await screen.findByRole('button', { name: 'Reject' }));
    fireEvent.change(screen.getByLabelText('Reason (required)'), {
      target: { value: 'Needs the specimen type' },
    });

    rejectAllResponses('conflict');
    submitReason();
    expect(await screen.findByText(stateViewText('en', 'conflict'))).toBeTruthy();
    const reasonBox = screen.getByLabelText('Reason (required)') as HTMLTextAreaElement;
    expect(reasonBox.value).toBe('Needs the specimen type');

    submitReason();
    await waitFor(() => expect(posts()).toHaveLength(2));
    const firstKey = posts()[0]!.headers['Idempotency-Key'];
    expect(posts()[1]!.headers['Idempotency-Key']).toBe(firstKey);

    fireEvent.click(screen.getByRole('button', { name: 'Request clarification' }));
    fireEvent.change(screen.getByLabelText('Question for the sender (required)'), {
      target: { value: 'Please resend the order' },
    });
    fireEvent.submit(screen.getByRole('button', { name: 'Send response' }).closest('form')!);
    await waitFor(() => expect(posts()).toHaveLength(3));
    expect(posts()[2]!.headers['Idempotency-Key']).not.toBe(firstKey);
  });

  it('issues a new key when the reason is corrected after a conflict', async () => {
    wrap();
    fireEvent.click(await screen.findByRole('button', { name: 'Reject' }));
    fireEvent.change(screen.getByLabelText('Reason (required)'), { target: { value: 'First reason' } });
    rejectAllResponses('conflict');
    submitReason();
    expect(await screen.findByText(stateViewText('en', 'conflict'))).toBeTruthy();

    fireEvent.change(screen.getByLabelText('Reason (required)'), {
      target: { value: 'Corrected reason' },
    });
    submitReason();
    await waitFor(() => expect(posts()).toHaveLength(2));
    expect(posts()[1]!.headers['Idempotency-Key']).not.toBe(
      posts()[0]!.headers['Idempotency-Key'],
    );
  });

  it('offers compare and copy on conflict without discarding the typed reason', async () => {
    const writeText = vi.fn();
    Object.defineProperty(navigator, 'clipboard', {
      value: { writeText },
      configurable: true,
    });
    try {
      wrap();
      fireEvent.click(await screen.findByRole('button', { name: 'Reject' }));
      fireEvent.change(screen.getByLabelText('Reason (required)'), {
        target: { value: 'Specimen type missing' },
      });
      rejectAllResponses('conflict');
      submitReason();
      expect(await screen.findByText(stateViewText('en', 'conflict'))).toBeTruthy();

      fireEvent.click(screen.getByRole('button', { name: 'Compare' }));
      const compare = await screen.findByTestId('orders-compare');
      expect(within(compare).getByText('Specimen type missing')).toBeTruthy();

      fireEvent.click(screen.getByRole('button', { name: 'Copy my changes' }));
      expect(writeText).toHaveBeenCalledWith('Specimen type missing');
    } finally {
      Reflect.deleteProperty(navigator, 'clipboard');
    }
  });

  it('guards browser navigation while a reason is typed', async () => {
    wrap();
    const clean = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(clean);
    expect(clean.defaultPrevented).toBe(false);

    fireEvent.click(await screen.findByRole('button', { name: 'Reject' }));
    fireEvent.change(screen.getByLabelText('Reason (required)'), { target: { value: 'draft' } });
    const dirty = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(dirty);
    expect(dirty.defaultPrevented).toBe(true);
  });

  it('shows the conflict state for a 412 stale precondition', async () => {
    wrap();
    sendMock.mockRejectedValueOnce(transportFailure('precondition-required'));
    fireEvent.click(await screen.findByRole('button', { name: 'Accept' }));
    expect(await screen.findByText(stateViewText('en', 'conflict'))).toBeTruthy();
  });

  it('shows a plain error state for a non-conflict command failure', async () => {
    wrap();
    sendMock.mockRejectedValueOnce(transportFailure('invalid'));
    fireEvent.click(await screen.findByRole('button', { name: 'Accept' }));
    expect(await screen.findByText(stateViewText('en', 'error'))).toBeTruthy();
    expect(screen.queryByText(stateViewText('en', 'conflict'))).toBeNull();
  });
});

describe('order detail routes', () => {
  it('registers the detail path before the catch-all', () => {
    const patterns = createRoutes(false).map((route) => route.pattern);
    expect(patterns).toContain('/orders/:id');
    expect(patterns.indexOf('/orders/:id')).toBeLessThan(patterns.indexOf('*'));
  });

  it('lazily loads the detail page with the bound route parameter', async () => {
    respond();
    const route = createRoutes(false).find((candidate) => candidate.pattern === '/orders/:id')!;
    const Page = route.component;
    const routed = (
      <RouterProvider patterns={['/orders/:id']} initialPath="/orders/order-001">
        <Page />
      </RouterProvider>
    );
    render(<Suspense fallback={<p>Loading route</p>}>{element(routed)}</Suspense>);
    expect(await screen.findByText('ORD-1')).toBeTruthy();
    expect(descriptorAt(0).url).toBe('/api/v1/orders/order-001');
  });
});
