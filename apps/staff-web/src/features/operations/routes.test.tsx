import { Suspense } from 'react';
import type { ReactElement } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { ActiveOrganizationProvider } from '../../api/activeOrganization';
import { I18nProvider } from '../../i18n/I18nProvider';
import { ThemeProvider } from '../../theme/ThemeProvider';
import { createRoutes } from '../../app/routes';
import { loadQueues } from './api';
import type * as ApiModule from './api';
import { alphaOrganizationId } from './fixtures';
import type { QueueCollection } from './types';

vi.mock('./api', async (importOriginal) => {
  const actual = await importOriginal<typeof ApiModule>();
  return { ...actual, loadQueues: vi.fn() };
});

const loadMock = vi.mocked(loadQueues);

beforeEach(() => {
  loadMock.mockReset();
  const empty = {
    'work-items': { status: 'ready', rows: [] },
    'dead-letters': { status: 'ready', rows: [] },
    'overdue-follow-ups': { status: 'ready', rows: [] },
    'duplicate-reviews': { status: 'ready', rows: [] },
    'clinic-worklist': { status: 'ready', rows: [] },
  } as QueueCollection;
  loadMock.mockResolvedValue(empty);
});

describe('operations routes', () => {
  it('registers the queues path before the catch-all', () => {
    const patterns = createRoutes(false).map((route) => route.pattern);
    expect(patterns).toContain('/operations/queues');
    expect(patterns.indexOf('/operations/queues')).toBeLessThan(patterns.indexOf('*'));
  });

  it('lazily loads the registered queues page end to end', async () => {
    const route = createRoutes(false).find(
      (candidate) => candidate.pattern === '/operations/queues',
    )!;
    const Page = route.component;
    const ui: ReactElement = (
      <ThemeProvider>
        <I18nProvider initialLocale="en">
          <ActiveOrganizationProvider value={{ organizationId: alphaOrganizationId }}>
            <Page />
          </ActiveOrganizationProvider>
        </I18nProvider>
      </ThemeProvider>
    );

    render(<Suspense fallback={<p>Loading route</p>}>{ui}</Suspense>);
    expect(await screen.findByRole('button', { name: 'Work items (0)' })).toBeTruthy();
    expect(loadMock).toHaveBeenCalledTimes(1);
  });
});
