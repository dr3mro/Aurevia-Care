import type { ReactElement } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { ActiveOrganizationProvider } from '../../api/activeOrganization';
import { TransportError } from '../../api/transport';
import { stateViewText } from '../../components/stateViewMessages';
import { I18nProvider } from '../../i18n/I18nProvider';
import { ThemeProvider } from '../../theme/ThemeProvider';
import { loadQueues } from './api';
import type * as ApiModule from './api';
import { OperatorHome } from './OperatorHome';
import { alphaOrganizationId, workItem } from './fixtures';
import { normalizeQueue } from './domain';
import type { QueueCollection } from './types';

vi.mock('./api', async (importOriginal) => {
  const actual = await importOriginal<typeof ApiModule>();
  return { ...actual, loadQueues: vi.fn() };
});

const loadMock = vi.mocked(loadQueues);

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

function element(ui: ReactElement): ReactElement {
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

beforeEach(() => {
  loadMock.mockReset();
  loadMock.mockResolvedValue(collection());
});

describe('OperatorHome', () => {
  it('links to the queues with a count for each queue for the operator role', async () => {
    render(element(<OperatorHome role="operator" />));
    expect(await screen.findByRole('heading', { name: 'Operations today' })).toBeTruthy();
    const links = screen.getAllByRole('link');
    expect(links).toHaveLength(5);
    expect(links[0]!.getAttribute('href')).toBe('/operations/queues');
    expect(links[0]!.textContent).toBe('Work items (1)');
    expect(screen.getByRole('link', { name: 'Dead letters (0)' })).toBeTruthy();
  });

  it('renders nothing for a role other than operator, using the detected role', () => {
    const { container } = render(element(<OperatorHome />));
    expect(container.textContent).toBe('');
  });

  it('shows the loading state while counts are pending', () => {
    loadMock.mockReturnValueOnce(new Promise<QueueCollection>(() => undefined));
    render(element(<OperatorHome role="operator" />));
    expect(screen.getByRole('heading', { name: stateViewText('en', 'loading') })).toBeTruthy();
  });

  it('shows the error state when counts cannot be loaded', async () => {
    loadMock.mockRejectedValueOnce(
      new TransportError('server', {
        correlationId: 'corr-home',
        code: undefined,
        retryAfterSeconds: undefined,
      }),
    );
    render(element(<OperatorHome role="operator" />));
    expect(await screen.findByText(stateViewText('en', 'error'))).toBeTruthy();
    expect(screen.getByText('corr-home')).toBeTruthy();
  });
});
