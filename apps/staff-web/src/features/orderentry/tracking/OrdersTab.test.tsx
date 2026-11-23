import type { ReactElement } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import type { Locale } from '../../../i18n/messages';
import { I18nProvider } from '../../../i18n/I18nProvider';
import { ThemeProvider } from '../../../theme/ThemeProvider';
import type { TrackedOrder, TrackedOrderStatus } from './domain';
import {
  acceptedOrder,
  alphaOrganizationId,
  betaOrganizationId,
  cancelRequestedOrder,
  cancelledOrder,
  deliveredOrder,
  draftOrder,
  placedOrder,
  resultedOrder,
  syntheticResult,
} from './fixtures';
import { statusMessageKey, trackingText } from './labels';
import { OrdersTab } from './OrdersTab';
import type { OrdersTabProps } from './OrdersTab';

const clinicianRole: OrdersTabProps['role'] = 'clinician';

function wrap(ui: ReactElement, locale: Locale = 'en') {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>{ui}</I18nProvider>
    </ThemeProvider>,
  );
}

function renderTab(overrides: Partial<OrdersTabProps> = {}, locale: Locale = 'en') {
  const props: OrdersTabProps = {
    organizationId: alphaOrganizationId,
    orders: [deliveredOrder, acceptedOrder, resultedOrder],
    role: 'clinician',
    onCancel: vi.fn(() => Promise.resolve(undefined)),
    onAmend: vi.fn(() => Promise.resolve(undefined)),
    onOpenResult: vi.fn(),
    ...overrides,
  };
  return { ...wrap(<OrdersTab {...props} />, locale), props };
}

function orderWithStatus(
  status: TrackedOrderStatus,
  patch: Partial<TrackedOrder> = {},
): TrackedOrder {
  return { ...draftOrder, status, ...patch };
}

function row(order: TrackedOrder): HTMLElement {
  return screen.getByTestId(`order-row-${order.id}`);
}

function reasonField(locale: Locale = 'en') {
  return screen.getByRole('textbox', {
    name: `${trackingText(locale, 'reason')} (${trackingText(locale, 'required')})`,
  });
}

function confirmAction(locale: Locale, kind: 'cancel' | 'amend') {
  return screen.getByRole('button', { name: trackingText(locale, kind) });
}

describe('OrdersTab', () => {
  it('shows the translated wording for every order status', () => {
    const statuses: readonly TrackedOrderStatus[] = [
      'draft',
      'placed',
      'delivered',
      'accepted',
      'collected_or_scheduled',
      'resulted',
      'cancelled',
    ];
    renderTab({
      orders: statuses.map((status) => orderWithStatus(status, { id: `order-${status}` })),
    });
    for (const status of statuses) {
      const cell = screen.getByTestId(`order-status-order-${status}`);
      expect(cell.textContent).toContain(trackingText('en', statusMessageKey(status)));
    }
  });

  it('shows delivery evidence, or an explicit not-delivered state', () => {
    renderTab({ orders: [deliveredOrder, draftOrder] });
    const evidence = within(row(deliveredOrder)).getByTestId(`order-delivery-${deliveredOrder.id}`);
    expect(evidence.textContent).toContain('Electronic to partner');
    expect(evidence.textContent).toContain(deliveredOrder.delivery?.partnerReference);
    expect(evidence.querySelector('time')?.getAttribute('datetime')).toBe(
      deliveredOrder.delivery?.deliveredAt,
    );
    expect(within(row(draftOrder)).getByText(trackingText('en', 'notDelivered'))).toBeTruthy();
  });

  it('opens the result from its link and says when no result exists yet', () => {
    const { props } = renderTab();
    const link = within(row(resultedOrder)).getByRole('button', {
      name: `${trackingText('en', 'openResult')}: ${syntheticResult.label}`,
    });
    fireEvent.click(link);
    expect(props.onOpenResult).toHaveBeenCalledWith(syntheticResult);
    expect(within(row(acceptedOrder)).getByText(trackingText('en', 'noResult'))).toBeTruthy();
  });

  it('requires a reason before a cancel is sent and trims the reason', async () => {
    const { props } = renderTab({ orders: [deliveredOrder] });
    fireEvent.click(
      within(row(deliveredOrder)).getByRole('button', {
        name: `${trackingText('en', 'cancel')}: ${deliveredOrder.id}`,
      }),
    );
    expect(reasonField()).toBeTruthy();

    fireEvent.click(confirmAction('en', 'cancel'));
    expect(screen.getByRole('alert').textContent).toContain(trackingText('en', 'reasonRequired'));
    expect(props.onCancel).not.toHaveBeenCalled();

    fireEvent.change(reasonField(), { target: { value: '   ' } });
    fireEvent.click(confirmAction('en', 'cancel'));
    expect(props.onCancel).not.toHaveBeenCalled();

    fireEvent.change(reasonField(), { target: { value: '  Collector rejected specimen  ' } });
    fireEvent.click(confirmAction('en', 'cancel'));
    await waitFor(() => expect(props.onCancel).toHaveBeenCalledTimes(1));
    expect(props.onCancel).toHaveBeenCalledWith(deliveredOrder, 'Collector rejected specimen');
    expect(screen.getByTestId('tracking-status').textContent).toContain(
      trackingText('en', 'cancelledNotice'),
    );
    expect(screen.queryByRole('textbox')).toBeNull();
  });

  it('says a request was sent when an accepted order is cancelled', async () => {
    const { props } = renderTab({ orders: [acceptedOrder] });
    fireEvent.click(
      within(row(acceptedOrder)).getByRole('button', {
        name: `${trackingText('en', 'cancel')}: ${acceptedOrder.id}`,
      }),
    );
    fireEvent.change(reasonField(), { target: { value: 'Patient withdrew consent' } });
    fireEvent.click(confirmAction('en', 'cancel'));
    await waitFor(() => expect(props.onCancel).toHaveBeenCalledTimes(1));
    expect(screen.getByTestId('tracking-status').textContent).toContain(
      trackingText('en', 'cancelRequestSentNotice'),
    );
  });

  it('requires a reason to amend and announces the partner notification', async () => {
    const { props } = renderTab({ orders: [placedOrder] });
    fireEvent.click(
      within(row(placedOrder)).getByRole('button', {
        name: `${trackingText('en', 'amend')}: ${placedOrder.id}`,
      }),
    );
    fireEvent.change(reasonField(), { target: { value: ' ' } });
    fireEvent.click(confirmAction('en', 'amend'));
    expect(screen.getByRole('alert').textContent).toContain(trackingText('en', 'reasonRequired'));
    expect(props.onAmend).not.toHaveBeenCalled();

    fireEvent.change(reasonField(), { target: { value: 'Add second study' } });
    fireEvent.click(confirmAction('en', 'amend'));
    await waitFor(() => expect(props.onAmend).toHaveBeenCalledTimes(1));
    expect(props.onAmend).toHaveBeenCalledWith(placedOrder, 'Add second study');
    expect(screen.getByTestId('tracking-status').textContent).toContain(
      trackingText('en', 'amendNotice'),
    );
  });

  it('discards an open reason form without sending', () => {
    const { props } = renderTab({ orders: [placedOrder] });
    fireEvent.click(
      within(row(placedOrder)).getByRole('button', {
        name: `${trackingText('en', 'cancel')}: ${placedOrder.id}`,
      }),
    );
    fireEvent.change(reasonField(), { target: { value: 'Typed but discarded' } });
    fireEvent.click(screen.getByRole('button', { name: trackingText('en', 'discard') }));
    expect(screen.queryByRole('textbox')).toBeNull();
    expect(props.onCancel).not.toHaveBeenCalled();
    expect(props.onAmend).not.toHaveBeenCalled();
  });
});

describe('OrdersTab command rules', () => {
  it('does not offer cancel for terminal or already-requested orders', () => {
    renderTab({ orders: [resultedOrder, cancelledOrder, cancelRequestedOrder] });
    for (const order of [resultedOrder, cancelledOrder]) {
      expect(
        within(row(order)).queryByRole('button', {
          name: `${trackingText('en', 'cancel')}: ${order.id}`,
        }),
      ).toBeNull();
      expect(
        within(row(order)).queryByRole('button', {
          name: `${trackingText('en', 'amend')}: ${order.id}`,
        }),
      ).toBeNull();
    }
    expect(
      within(row(cancelRequestedOrder)).queryByRole('button', {
        name: `${trackingText('en', 'cancel')}: ${cancelRequestedOrder.id}`,
      }),
    ).toBeNull();
    expect(
      within(row(cancelRequestedOrder)).getByText(trackingText('en', 'cancelRequested')),
    ).toBeTruthy();
  });

  it('hides cancel and amend from roles without ordering privilege', () => {
    renderTab({ orders: [deliveredOrder], role: 'reception' });
    expect(
      screen.queryByRole('button', {
        name: `${trackingText('en', 'cancel')}: ${deliveredOrder.id}`,
      }),
    ).toBeNull();
    expect(
      screen.queryByRole('button', {
        name: `${trackingText('en', 'amend')}: ${deliveredOrder.id}`,
      }),
    ).toBeNull();
  });

  it('sends only one command when confirm is clicked twice while a request is pending', async () => {
    let finish: (() => void) | undefined;
    const onCancel = vi.fn(
      () =>
        new Promise<void>((resolve) => {
          finish = resolve;
        }),
    );
    renderTab({ orders: [deliveredOrder], onCancel });
    fireEvent.click(
      within(row(deliveredOrder)).getByRole('button', {
        name: `${trackingText('en', 'cancel')}: ${deliveredOrder.id}`,
      }),
    );
    fireEvent.change(reasonField(), { target: { value: 'Collector rejected specimen' } });
    fireEvent.click(confirmAction('en', 'cancel'));
    fireEvent.click(confirmAction('en', 'cancel'));
    expect(onCancel).toHaveBeenCalledTimes(1);
    expect((confirmAction('en', 'cancel') as HTMLButtonElement).disabled).toBe(true);
    await act(() => {
      finish?.();
      return Promise.resolve();
    });
    expect(screen.getByTestId('tracking-status').textContent).toContain(
      trackingText('en', 'cancelledNotice'),
    );
  });

  it('discards a command result that arrives after a tenant switch', async () => {
    let finish: (() => void) | undefined;
    const onCancel = vi.fn(
      () =>
        new Promise<void>((resolve) => {
          finish = resolve;
        }),
    );
    const viewFor = (organizationId: string, orders: readonly TrackedOrder[]) => (
      <ThemeProvider>
        <I18nProvider initialLocale="en">
          <OrdersTab
            organizationId={organizationId}
            orders={orders}
            role={clinicianRole}
            onCancel={onCancel}
            onAmend={vi.fn(() => Promise.resolve(undefined))}
            onOpenResult={vi.fn()}
          />
        </I18nProvider>
      </ThemeProvider>
    );
    const { rerender } = render(viewFor(alphaOrganizationId, [deliveredOrder]));
    fireEvent.click(
      within(row(deliveredOrder)).getByRole('button', {
        name: `${trackingText('en', 'cancel')}: ${deliveredOrder.id}`,
      }),
    );
    fireEvent.change(reasonField(), { target: { value: 'Collector rejected specimen' } });
    fireEvent.click(confirmAction('en', 'cancel'));
    rerender(viewFor(betaOrganizationId, []));
    await act(() => {
      finish?.();
      return Promise.resolve();
    });
    expect(screen.getByTestId('tracking-status').textContent).toBe('');
    expect(screen.queryByTestId('tracking-error')).toBeNull();
  });

  it('reports a failed send without claiming the action succeeded', async () => {
    renderTab({
      orders: [deliveredOrder],
      onCancel: vi.fn(() => Promise.reject(new Error('network'))),
    });
    fireEvent.click(
      within(row(deliveredOrder)).getByRole('button', {
        name: `${trackingText('en', 'cancel')}: ${deliveredOrder.id}`,
      }),
    );
    fireEvent.change(reasonField(), { target: { value: 'Collector rejected specimen' } });
    fireEvent.click(confirmAction('en', 'cancel'));
    await waitFor(() =>
      expect(screen.getByTestId('tracking-error').textContent).toContain(
        trackingText('en', 'sendError'),
      ),
    );
    expect(screen.getByTestId('tracking-status').textContent).toBe('');
  });
});

describe('OrdersTab tenant scope and locale', () => {
  it('renders an empty state when the chart has no orders', () => {
    renderTab({ orders: [] });
    expect(screen.getByText(trackingText('en', 'noOrders'))).toBeTruthy();
  });

  it('scopes the list to the active tenant and clears it on a tenant switch', () => {
    const betaOrder: TrackedOrder = {
      ...draftOrder,
      id: 'beta-order',
      organizationId: betaOrganizationId,
    };
    const { rerender } = renderTab({ orders: [draftOrder, betaOrder] });
    expect(screen.getByTestId(`order-row-${draftOrder.id}`)).toBeTruthy();
    expect(screen.queryByTestId(`order-row-${betaOrder.id}`)).toBeNull();

    rerender(
      <ThemeProvider>
        <I18nProvider initialLocale="en">
          <OrdersTab
            organizationId={betaOrganizationId}
            orders={[draftOrder, betaOrder]}
            role={clinicianRole}
            onCancel={vi.fn(() => Promise.resolve(undefined))}
            onAmend={vi.fn(() => Promise.resolve(undefined))}
            onOpenResult={vi.fn()}
          />
        </I18nProvider>
      </ThemeProvider>,
    );
    expect(screen.queryByTestId(`order-row-${draftOrder.id}`)).toBeNull();
    expect(screen.getByTestId(`order-row-${betaOrder.id}`)).toBeTruthy();
  });

  it('renders Arabic RTL copy with isolated mixed-script values and translated status', async () => {
    const { props } = renderTab({ orders: [acceptedOrder, resultedOrder] }, 'ar');
    expect(document.documentElement.dir).toBe('rtl');
    expect(
      screen.getByRole('heading', { level: 2, name: trackingText('ar', 'ordersTab') }),
    ).toBeTruthy();
    expect(screen.getByTestId(`order-status-${acceptedOrder.id}`).textContent).toContain(
      trackingText('ar', statusMessageKey(acceptedOrder.status)),
    );
    expect(within(row(acceptedOrder)).getByText(acceptedOrder.display).getAttribute('dir')).toBe(
      'auto',
    );

    fireEvent.click(
      within(row(acceptedOrder)).getByRole('button', {
        name: `${trackingText('ar', 'cancel')}: ${acceptedOrder.id}`,
      }),
    );
    fireEvent.change(reasonField('ar'), { target: { value: 'تم سحب الموافقة' } });
    fireEvent.click(confirmAction('ar', 'cancel'));
    await waitFor(() => expect(props.onCancel).toHaveBeenCalledTimes(1));
    expect(screen.getByTestId('tracking-status').textContent).toContain(
      trackingText('ar', 'cancelRequestSentNotice'),
    );
  });
});
