import type { ReactElement } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { Mock } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { ActiveOrganizationProvider } from '../../api/activeOrganization';
import { TransportError } from '../../api/transport';
import { stateViewText } from '../../components/stateViewMessages';
import { I18nProvider } from '../../i18n/I18nProvider';
import type { Locale } from '../../i18n/messages';
import { ThemeProvider } from '../../theme/ThemeProvider';
import { QueuesScreen } from './QueuesScreen';
import type { QueuesScreenProps } from './QueuesScreen';
import { alphaOrganizationId, deadLetter, fixedNowMs, followUp, workItem, worklistItem } from './fixtures';
import type { QueueAction, QueueCollection, QueueId, QueueRow } from './types';

function rowsFor(queue: QueueId): QueueRow[] {
  switch (queue) {
    case 'work-items':
      return [
        {
          id: 'work-001',
          primary: 'ORD-SYN-001',
          detail: 'SYN-0001',
          state: 'awaiting_local_patient',
          occurredAt: '2026-03-09T11:30:00Z',
        },
      ];
    case 'dead-letters':
      return [
        {
          id: 'evt-001',
          primary: 'evt-001',
          detail: 'partner unavailable',
          state: 'pending review',
          occurredAt: '2026-03-09T11:00:00Z',
        },
      ];
    case 'overdue-follow-ups':
      return [
        {
          id: 'instruction-001',
          primary: 'instruction-001',
          detail: 'no acknowledgment',
          state: 'overdue',
          occurredAt: '2026-03-09T10:45:00Z',
        },
      ];
    case 'duplicate-reviews':
      return [
        {
          id: 'SYN-0002:SYN-0003',
          primary: 'SYN-0002',
          detail: 'SYN-0003',
          state: 'pending review',
          occurredAt: undefined,
        },
      ];
    case 'clinic-worklist':
      return [
        {
          id: 'wl-001',
          primary: 'SYN-0004',
          detail: 'walk-in',
          state: 'unassigned',
          occurredAt: '2026-03-09T09:30:00Z',
        },
      ];
  }
}

function collection(overrides: Partial<QueueCollection> = {}): QueueCollection {
  const base = {} as QueueCollection;
  for (const queue of rowsForKeys()) {
    base[queue] = { status: 'ready', rows: rowsFor(queue) };
  }
  return { ...base, ...overrides };
}

/** Stable list of queue keys, independent of any one row fixture. */
function rowsForKeys(): QueueId[] {
  return ['work-items', 'dead-letters', 'overdue-follow-ups', 'duplicate-reviews', 'clinic-worklist'];
}

function failure(kind: ConstructorParameters<typeof TransportError>[0] = 'server') {
  return new TransportError(kind, {
    correlationId: `corr-${kind}`,
    code: undefined,
    retryAfterSeconds: undefined,
  });
}

function element(ui: ReactElement, locale: Locale = 'en'): ReactElement {
  return (
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <ActiveOrganizationProvider value={{ organizationId: alphaOrganizationId }}>
          {ui}
        </ActiveOrganizationProvider>
      </I18nProvider>
    </ThemeProvider>
  );
}

let onAction: Mock<
  (queue: QueueId, row: QueueRow, action: QueueAction, reason: string) => Promise<void>
>;
let onReload: Mock<() => void>;

function renderScreen(
  data: QueueCollection,
  active: QueueId = 'work-items',
  locale: Locale = 'en',
) {
  const props: QueuesScreenProps = {
    collection: data,
    active,
    onActiveChange: vi.fn(),
    nowMs: fixedNowMs,
    onAction,
    onReload,
  };
  return render(element(<QueuesScreen {...props} />, locale));
}

beforeEach(() => {
  onAction = vi.fn().mockResolvedValue(undefined);
  onReload = vi.fn();
});

describe('QueuesScreen tabs and states', () => {
  it('shows a count per tab and derives oldest age from the server instant', () => {
    renderScreen(collection());
    expect(screen.getByRole('button', { name: 'Work items (1)' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Dead letters (1)' })).toBeTruthy();
    expect(screen.getByTestId('operations-oldest').textContent).toContain('Oldest item age:');
    expect(screen.getByTestId('operations-oldest').textContent).toMatch(/minute/);
    expect(screen.getByRole('rowheader', { name: 'ORD-SYN-001' })).toBeTruthy();
  });

  it.each([
    ['work-items', 'ORD-SYN-001'],
    ['overdue-follow-ups', 'instruction-001'],
    ['duplicate-reviews', 'SYN-0002'],
    ['clinic-worklist', 'SYN-0004'],
  ] as const)('renders the %s table with a stable row key', (queue, primary) => {
    renderScreen(collection(), queue);
    expect(screen.getByRole('rowheader', { name: primary })).toBeTruthy();
    expect(screen.queryByText(stateViewText('en', 'empty'))).toBeNull();
  });

  it('shows the empty state for a tab with no rows', () => {
    renderScreen(collection({ 'work-items': { status: 'ready', rows: [] } }));
    expect(screen.getByText(stateViewText('en', 'empty'))).toBeTruthy();
    expect(screen.queryByRole('table')).toBeNull();
  });

  it('shows a failed tab error with an explicit retry control', () => {
    renderScreen(collection({ 'work-items': { status: 'error', error: failure('forbidden') } }));
    expect(screen.getByText(stateViewText('en', 'forbidden'))).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Retry loading this queue' }));
    expect(onReload).toHaveBeenCalledTimes(1);
  });

  it('states that age is unavailable when the endpoint provides no timestamp', () => {
    renderScreen(collection(), 'duplicate-reviews');
    expect(screen.getByTestId('operations-oldest').textContent).toContain(
      'Not available from this endpoint',
    );
  });

  it('keeps the tab button keyboard reachable and selects a tab on activation', () => {
    const onActiveChange = vi.fn();
    const props: QueuesScreenProps = {
      collection: collection(),
      active: 'work-items',
      onActiveChange,
      nowMs: fixedNowMs,
      onAction,
      onReload,
    };
    render(element(<QueuesScreen {...props} />));
    const tab = screen.getByRole('button', { name: 'Dead letters (1)' });
    tab.focus();
    expect(document.activeElement).toBe(tab);
    fireEvent.click(tab);
    expect(onActiveChange).toHaveBeenCalledWith('dead-letters');
  });

  it('renders the table with Arabic document direction', () => {
    renderScreen(collection(), 'work-items', 'ar');
    expect(document.documentElement.dir).toBe('rtl');
    expect(screen.getByRole('rowheader', { name: 'ORD-SYN-001' })).toBeTruthy();
  });
});

describe('QueuesScreen row actions', () => {
  it('requires a reason before sending an action', () => {
    renderScreen(collection(), 'dead-letters');
    fireEvent.click(screen.getByRole('button', { name: 'Retry evt-001' }));

    const reason = screen.getByLabelText('Reason') as HTMLTextAreaElement;
    expect(reason.getAttribute('aria-invalid')).toBe('false');
    fireEvent.click(screen.getByRole('button', { name: 'Send action' }));

    expect(onAction).not.toHaveBeenCalled();
    expect(screen.getByText('Enter a reason before sending this action.')).toBeTruthy();
    expect(reason.getAttribute('aria-invalid')).toBe('true');

    fireEvent.change(reason, { target: { value: ' synthetic retry ' } });
    fireEvent.click(screen.getByRole('button', { name: 'Send action' }));

    expect(onAction).toHaveBeenCalledTimes(1);
    expect(onAction).toHaveBeenCalledWith(
      'dead-letters',
      expect.objectContaining({ id: 'evt-001' }),
      'retry',
      'synthetic retry',
    );
  });

  it('shows the success status after one confirmed action', async () => {
    renderScreen(collection(), 'dead-letters');
    fireEvent.click(screen.getByRole('button', { name: 'Review evt-001' }));
    fireEvent.change(screen.getByLabelText('Reason'), { target: { value: 'synthetic review' } });
    fireEvent.click(screen.getByRole('button', { name: 'Send action' }));

    expect(await screen.findByTestId('operations-status').then((node) => node.textContent)).toBe(
      'Action sent once',
    );
    expect(screen.queryByTestId('operations-reason-dialog')).toBeNull();
  });

  it('shows a failed retry error state and never repeats it automatically', async () => {
    onAction.mockRejectedValueOnce(failure('server'));
    renderScreen(collection(), 'dead-letters');
    fireEvent.click(screen.getByRole('button', { name: 'Retry evt-001' }));
    fireEvent.change(screen.getByLabelText('Reason'), { target: { value: 'synthetic retry' } });
    fireEvent.click(screen.getByRole('button', { name: 'Send action' }));

    expect(await screen.findByTestId('operations-action-error')).toBeTruthy();
    expect(screen.getByText(stateViewText('en', 'error'))).toBeTruthy();
    expect(onAction).toHaveBeenCalledTimes(1);
    expect(screen.queryByTestId('operations-reason-dialog')).toBeNull();
  });

  it('normalises an unexpected rejection to the server error state', async () => {
    onAction.mockRejectedValueOnce(new Error('unexpected'));
    renderScreen(collection(), 'dead-letters');
    fireEvent.click(screen.getByRole('button', { name: 'Retry evt-001' }));
    fireEvent.change(screen.getByLabelText('Reason'), { target: { value: 'synthetic retry' } });
    fireEvent.click(screen.getByRole('button', { name: 'Send action' }));

    expect(await screen.findByTestId('operations-action-error')).toBeTruthy();
    expect(screen.getByText(stateViewText('en', 'correlationUnavailable'))).toBeTruthy();
  });

  it('sends only once for two rapid confirm clicks', () => {
    let resolve!: () => void;
    onAction.mockReturnValueOnce(new Promise<void>((done) => { resolve = done; }));
    renderScreen(collection(), 'dead-letters');
    fireEvent.click(screen.getByRole('button', { name: 'Retry evt-001' }));
    fireEvent.change(screen.getByLabelText('Reason'), { target: { value: 'synthetic retry' } });
    const confirm = screen.getByRole('button', { name: 'Send action' });
    fireEvent.click(confirm);
    fireEvent.click(confirm);
    expect(onAction).toHaveBeenCalledTimes(1);
    resolve();
  });

  it('cancels the reason dialog without sending anything', () => {
    renderScreen(collection(), 'clinic-worklist');
    fireEvent.click(screen.getByRole('button', { name: 'Assign SYN-0004' }));
    expect(screen.getByTestId('operations-reason-dialog')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByTestId('operations-reason-dialog')).toBeNull();
    expect(onAction).not.toHaveBeenCalled();
  });

  it('clears a previous error when a new action dialog opens', async () => {
    onAction.mockRejectedValueOnce(failure('server'));
    renderScreen(collection(), 'dead-letters');
    fireEvent.click(screen.getByRole('button', { name: 'Retry evt-001' }));
    fireEvent.change(screen.getByLabelText('Reason'), { target: { value: 'first' } });
    fireEvent.click(screen.getByRole('button', { name: 'Send action' }));
    expect(await screen.findByTestId('operations-action-error')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'Review evt-001' }));
    expect(screen.queryByTestId('operations-action-error')).toBeNull();
    expect((screen.getByLabelText('Reason') as HTMLTextAreaElement).value).toBe('');
  });
});

describe('operations fixtures', () => {
  it('exposes synthetic wire payloads for every queue', () => {
    expect(workItem().localPatientId).toBe('SYN-0001');
    expect(deadLetter().eventId).toBe('evt-001');
    expect(followUp().instructionId).toBe('instruction-001');
    expect(worklistItem().patientId).toBe('SYN-0004');
  });
});
