import type { ReactElement } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { ActiveOrganizationProvider } from '../../api/activeOrganization';
import { TransportError } from '../../api/transport';
import { I18nProvider } from '../../i18n/I18nProvider';
import { stateViewText } from '../../components/stateViewMessages';
import { ThemeProvider } from '../../theme/ThemeProvider';
import { loadQueues, runQueueAction } from './api';
import type * as ApiModule from './api';
import { QueuesPage } from './QueuesPage';
import { alphaOrganizationId, betaOrganizationId, fixedNowMs, workItem } from './fixtures';
import { normalizeQueue } from './domain';
import type { QueueCollection } from './types';

vi.mock('./api', async (importOriginal) => {
  const actual = await importOriginal<typeof ApiModule>();
  return { ...actual, loadQueues: vi.fn(), runQueueAction: vi.fn() };
});

const loadMock = vi.mocked(loadQueues);
const actionMock = vi.mocked(runQueueAction);

function collection(): QueueCollection {
  const rows = normalizeQueue('work-items', [workItem()]);
  return {
    'work-items': { status: 'ready', rows },
    'dead-letters': { status: 'ready', rows: [] },
    'overdue-follow-ups': { status: 'ready', rows: [] },
    'duplicate-reviews': { status: 'ready', rows: [] },
    'clinic-worklist': { status: 'ready', rows: [] },
  };
}

function element(ui: ReactElement, organizationId = alphaOrganizationId): ReactElement {
  return (
    <ThemeProvider>
      <I18nProvider initialLocale="en">
        <ActiveOrganizationProvider value={{ organizationId }}>
          {ui}
        </ActiveOrganizationProvider>
      </I18nProvider>
    </ThemeProvider>
  );
}

beforeEach(() => {
  loadMock.mockReset();
  actionMock.mockReset();
  actionMock.mockResolvedValue(undefined);
});

describe('QueuesPage loading', () => {
  it('shows the loading state until every queue resolves', () => {
    loadMock.mockReturnValueOnce(new Promise<QueueCollection>(() => undefined));
    render(element(<QueuesPage nowMs={fixedNowMs} />));
    expect(screen.getByRole('heading', { name: stateViewText('en', 'loading') })).toBeTruthy();
  });

  it('shows a global error state when the loader itself rejects', async () => {
    loadMock.mockRejectedValueOnce(
      new TransportError('server', {
        correlationId: 'corr-page',
        code: undefined,
        retryAfterSeconds: undefined,
      }),
    );
    render(element(<QueuesPage nowMs={fixedNowMs} />));
    expect(await screen.findByText(stateViewText('en', 'error'))).toBeTruthy();
    expect(screen.getByText('corr-page')).toBeTruthy();
  });

  it('hands a loaded collection to the screen and uses the current clock when none is given', async () => {
    loadMock.mockResolvedValue(collection());
    render(element(<QueuesPage />));
    expect(await screen.findByRole('button', { name: 'Work items (1)' })).toBeTruthy();
    expect(loadMock).toHaveBeenCalledTimes(1);
  });
});

describe('QueuesPage commands', () => {
  it('sends one command with the reason and reloads the queues after success', async () => {
    loadMock.mockResolvedValue(collection());
    render(element(<QueuesPage nowMs={fixedNowMs} />));
    await screen.findByRole('button', { name: 'Review ORD-SYN-001' });

    fireEvent.click(screen.getByRole('button', { name: 'Review ORD-SYN-001' }));
    const reason = screen.getByLabelText('Reason') as HTMLTextAreaElement;
    fireEvent.change(reason, { target: { value: 'synthetic review' } });
    fireEvent.click(screen.getByRole('button', { name: 'Send action' }));

    await waitFor(() => expect(loadMock).toHaveBeenCalledTimes(2));
    expect(actionMock).toHaveBeenCalledTimes(1);
    expect(actionMock).toHaveBeenCalledWith(
      'work-items',
      expect.objectContaining({ id: 'work-001' }),
      'review',
      'synthetic review',
      alphaOrganizationId,
    );
  });

  it('loads the new tenant when the active organization changes', async () => {
    loadMock.mockResolvedValue(collection());
    const { rerender } = render(element(<QueuesPage nowMs={fixedNowMs} />));
    await screen.findByRole('button', { name: 'Work items (1)' });

    rerender(element(<QueuesPage nowMs={fixedNowMs} />, betaOrganizationId));
    await waitFor(() => expect(loadMock).toHaveBeenCalledTimes(2));
    expect(loadMock.mock.calls[1]).toBeDefined();
  });
});
