import type { ReactElement } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { ActiveOrganizationProvider } from '../../../api/activeOrganization';
import type { RequestDescriptor } from '../../../api/client';
import { TransportError, send } from '../../../api/transport';
import type * as TransportModule from '../../../api/transport';
import { stateViewText } from '../../../components/stateViewMessages';
import { defined } from '../../../components/invariant';
import { I18nProvider } from '../../../i18n/I18nProvider';
import { ThemeProvider } from '../../../theme/ThemeProvider';
import { alphaDirectory, validWireDraft, validWireListing } from './fixtures';
import { mailText } from './labels';
import { MailPage } from './MailPage';
import type { MailPageProps } from './MailPage';

vi.mock('../../../api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);

/** A typed failure with a stable correlation id for WEB-09 state assertions. */
function failure(kind: ConstructorParameters<typeof TransportError>[0], correlationId = 'corr-1') {
  return new TransportError(kind, {
    correlationId,
    code: undefined,
    retryAfterSeconds: undefined,
  });
}

/** Build a wire draft that echoes what the page actually sent. */
function echoDraft(version: number, descriptor: RequestDescriptor) {
  const body = descriptor.body ?? {};
  return {
    ...validWireDraft,
    version,
    subject: typeof body.subject === 'string' ? body.subject : '',
    body: typeof body.body === 'string' ? body.body : '',
  };
}

/** A URL-keyed plan so autosave never steals a failure meant for send. */
interface MockPlan {
  readonly list?: (url: string) => Promise<unknown>;
  readonly create?: (descriptor: RequestDescriptor) => Promise<unknown>;
  readonly save?: (descriptor: RequestDescriptor) => Promise<unknown>;
  readonly publish?: (descriptor: RequestDescriptor) => Promise<unknown>;
}

function listFor(url: string) {
  if (url.endsWith('/folders/inbox')) {
    return [defined(validWireListing[0], 'first wire view')];
  }
  return [];
}

/** Widen the request method so a PUT can be asserted despite the shared union. */
function methodOf(descriptor: RequestDescriptor): string {
  return descriptor.method;
}

/** Each mocked response kind lives in its own small function to keep branches legible. */
function listResult(descriptor: RequestDescriptor, overrides: MockPlan): Promise<unknown> {
  return overrides.list?.(descriptor.url) ?? Promise.resolve(listFor(descriptor.url));
}

function saveResult(descriptor: RequestDescriptor, overrides: MockPlan): Promise<unknown> {
  return (
    overrides.save?.(descriptor) ??
    Promise.resolve(echoDraft(Number(descriptor.headers['If-Match'] ?? '1') + 1, descriptor))
  );
}

function publishResult(descriptor: RequestDescriptor, overrides: MockPlan): Promise<unknown> {
  return (
    overrides.publish?.(descriptor) ??
    Promise.resolve(defined(validWireListing[0], 'first wire view'))
  );
}

function createResult(descriptor: RequestDescriptor, overrides: MockPlan): Promise<unknown> {
  return overrides.create?.(descriptor) ?? Promise.resolve(echoDraft(2, descriptor));
}

/** Resolve one mocked request from the active plan, keyed by method and URL. */
function resolveRequest(descriptor: RequestDescriptor, overrides: MockPlan): Promise<unknown> {
  const method = methodOf(descriptor);
  if (method === 'GET') {
    return listResult(descriptor, overrides);
  }
  if (descriptor.url.endsWith('/send')) {
    return publishResult(descriptor, overrides);
  }
  if (method === 'PUT') {
    return saveResult(descriptor, overrides);
  }
  return createResult(descriptor, overrides);
}

function plan(overrides: MockPlan = {}): void {
  sendMock.mockImplementation((descriptor) => resolveRequest(descriptor, overrides));
}

/** Providers bound to one active tenant and locale. */
function element(ui: ReactElement, organizationId: string, locale: 'en' | 'ar'): ReactElement {
  return (
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <ActiveOrganizationProvider value={{ organizationId }}>
          {ui}
        </ActiveOrganizationProvider>
      </I18nProvider>
    </ThemeProvider>
  );
}

function wrap(props: MailPageProps = {}, organizationId = 'org-alpha', locale: 'en' | 'ar' = 'en') {
  return render(element(<MailPage {...props} />, organizationId, locale));
}

/** The compose body textarea, found by its bilingual label. */
function bodyField(locale: 'en' | 'ar' = 'en') {
  return screen.getByLabelText<HTMLTextAreaElement>(mailText(locale, 'bodyLabel'));
}

/** Select descriptors by method and URL instead of fragile call indexes. */
function descriptorsWhere(predicate: (descriptor: RequestDescriptor) => boolean): RequestDescriptor[] {
  return sendMock.mock.calls.map(([descriptor]) => descriptor).filter(predicate);
}

beforeEach(() => {
  sendMock.mockReset();
  plan();
});

describe('MailPage WEB-09 states', () => {
  it('shows loading until the folder overview resolves', async () => {
    let resolve!: (value: unknown) => void;
    const pending = new Promise<unknown>((done) => {
      resolve = done;
    });
    plan({ list: () => pending });
    wrap();
    expect(screen.getByRole('heading', { name: stateViewText('en', 'loading') })).toBeTruthy();
    await act(async () => {
      resolve([defined(validWireListing[0], 'first wire view')]);
      await Promise.resolve();
    });
    expect(await screen.findByRole('button', { name: /Synthetic appointment change/ })).toBeTruthy();
  });

  it('shows the empty state when every folder is empty', async () => {
    plan({ list: () => Promise.resolve([]) });
    wrap();
    expect(await screen.findByText(mailText('en', 'emptyFolder'))).toBeTruthy();
  });

  it.each(['server', 'timeout', 'offline'] as const)(
    'shows the %s load error with its correlation id',
    async (kind) => {
      plan({ list: () => Promise.reject(failure(kind, `corr-${kind}`)) });
      wrap();
      expect(await screen.findByText(stateViewText('en', 'error'))).toBeTruthy();
      expect(screen.getByText(`corr-${kind}`)).toBeTruthy();
    },
  );

  it('shows the forbidden and session-expired states', async () => {
    plan({ list: () => Promise.reject(failure('forbidden')) });
    const first = wrap();
    expect(await screen.findByText(stateViewText('en', 'forbidden'))).toBeTruthy();
    first.unmount();
    plan({ list: () => Promise.reject(failure('session-expired')) });
    wrap();
    expect(await screen.findByText(stateViewText('en', 'sessionExpired'))).toBeTruthy();
  });

  it('retries a failed load through the shared Retry action', async () => {
    plan({ list: () => Promise.reject(failure('server')) });
    wrap();
    expect(await screen.findByText(stateViewText('en', 'error'))).toBeTruthy();
    plan();
    fireEvent.click(screen.getByRole('button', { name: stateViewText('en', 'retry') }));
    expect(await screen.findByRole('button', { name: /Synthetic appointment change/ })).toBeTruthy();
  });
});

describe('MailPage autosave and recovery', () => {
  it('creates the draft on first content then saves each version with If-Match', async () => {
    wrap();
    await screen.findByRole('button', { name: /Synthetic appointment change/ });
    fireEvent.change(bodyField(), { target: { value: 'Synthetic first edit' } });
    await waitFor(() =>
      expect(descriptorsWhere((d) => d.url.endsWith('/staff/mail/drafts') && d.method === 'POST')).toHaveLength(1),
    );
    fireEvent.change(bodyField(), { target: { value: 'Synthetic second edit' } });
    const puts = () => descriptorsWhere((d) => methodOf(d) === 'PUT');
    await waitFor(() => expect(puts()).toHaveLength(1));
    expect(puts()[0]?.headers['If-Match']).toBe('2');
    expect(puts()[0]?.body).toMatchObject({ body: 'Synthetic second edit' });
    expect(screen.getByText(/Draft saved at version/)).toBeTruthy();
  });

  it('does not autosave whitespace-only content', async () => {
    wrap();
    await screen.findByRole('button', { name: /Synthetic appointment change/ });
    fireEvent.change(bodyField(), { target: { value: '   \n\t ' } });
    await act(async () => {
      await Promise.resolve();
    });
    expect(descriptorsWhere((d) => methodOf(d) === 'PUT')).toHaveLength(0);
    expect(descriptorsWhere((d) => d.url.endsWith('/staff/mail/drafts'))).toHaveLength(0);
  });

  it('keeps typed work through a 412 and offers compare, reload and copy', async () => {
    plan({ save: () => Promise.reject(failure('conflict', 'corr-stale')) });
    wrap();
    await screen.findByRole('button', { name: /Synthetic appointment change/ });
    fireEvent.change(bodyField(), { target: { value: 'SENTINEL typed work' } });
    await waitFor(() =>
      expect(descriptorsWhere((d) => d.url.endsWith('/staff/mail/drafts') && d.method === 'POST')).toHaveLength(1),
    );
    fireEvent.change(bodyField(), { target: { value: 'SENTINEL typed work again' } });
    expect(await screen.findByText(stateViewText('en', 'conflict'))).toBeTruthy();
    expect(bodyField().value).toBe('SENTINEL typed work again');

    const compare = () => screen.getByTestId('mail-compare');
    fireEvent.click(screen.getByRole('button', { name: stateViewText('en', 'compare') }));
    expect(compare().textContent).toContain('SENTINEL typed work again');
    expect(bodyField().value).toBe('SENTINEL typed work again');

    fireEvent.click(screen.getByRole('button', { name: stateViewText('en', 'copyMyChanges') }));
    expect(screen.getByTestId('mail-working-copy-typed').textContent).toContain(
      'SENTINEL typed work again',
    );
    expect(bodyField().value).toBe('SENTINEL typed work again');

    const getsBefore = descriptorsWhere((d) => methodOf(d) === 'GET').length;
    fireEvent.click(screen.getByRole('button', { name: stateViewText('en', 'reload') }));
    await waitFor(() =>
      expect(descriptorsWhere((d) => methodOf(d) === 'GET').length).toBeGreaterThan(getsBefore),
    );
    expect(screen.getByTestId('mail-working-copy-typed').textContent).toContain(
      'SENTINEL typed work again',
    );
  });

  it('adds and removes recipients and stages a file through the page', async () => {
    wrap();
    await screen.findByRole('button', { name: /Synthetic appointment change/ });
    fireEvent.change(screen.getByLabelText(mailText('en', 'toPickerLabel')), {
      target: { value: 'synthetic-role-triage' },
    });
    fireEvent.click(screen.getByRole('button', { name: mailText('en', 'addTo') }));
    expect(screen.getByRole('button', { name: 'Remove recipient: synthetic-role-triage' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Remove recipient: synthetic-role-triage' }));
    fireEvent.change(screen.getByLabelText(mailText('en', 'ccPickerLabel')), {
      target: { value: 'synthetic-person-nora' },
    });
    fireEvent.click(screen.getByRole('button', { name: mailText('en', 'addCc') }));
    expect(screen.getByRole('button', { name: 'Remove recipient: synthetic-person-nora' })).toBeTruthy();
    fireEvent.change(screen.getByLabelText(mailText('en', 'attachLabel')), {
      target: { value: 'synthetic-document-001' },
    });
    const attachButtons = screen.getAllByRole('button', { name: mailText('en', 'attachLabel') });
    fireEvent.click(defined(attachButtons[0], 'attach button'));
    expect(screen.getByTestId('mail-attachment-hint').textContent).toContain('visit-summary.pdf');
  });

  it('maps a non-stale save failure to the error state and retries it', async () => {
    plan({ save: () => Promise.reject(failure('server', 'corr-save')) });
    wrap();
    await screen.findByRole('button', { name: /Synthetic appointment change/ });
    fireEvent.change(bodyField(), { target: { value: 'first edit' } });
    await waitFor(() =>
      expect(descriptorsWhere((d) => d.url.endsWith('/staff/mail/drafts')).length).toBe(1),
    );
    fireEvent.change(bodyField(), { target: { value: 'retry edit' } });
    expect(await screen.findByTestId('mail-command-error')).toBeTruthy();
    expect(screen.getByText('corr-save')).toBeTruthy();
    plan();
    fireEvent.click(within(screen.getByTestId('mail-command-error')).getByRole('button', { name: mailText('en', 'retryCommand') }));
    await waitFor(() => expect(descriptorsWhere((d) => methodOf(d) === 'PUT').length).toBe(2));
  });
});

describe('MailPage send and confirmation', () => {
  it('refuses to send before a draft exists', async () => {
    wrap();
    await screen.findByRole('button', { name: /Synthetic appointment change/ });
    fireEvent.click(screen.getByRole('button', { name: mailText('en', 'send') }));
    expect(screen.getByText(mailText('en', 'validationNoDraft'))).toBeTruthy();
    expect(descriptorsWhere((d) => d.url.endsWith('/send'))).toHaveLength(0);
  });

  it('opens the recipient dialog on 428 and confirms with the resolved list and one key', async () => {
    plan({
      publish: (descriptor) =>
        descriptor.body?.confirmedRecipients === undefined
          ? Promise.reject(failure('precondition-required', 'corr-428'))
          : Promise.resolve(defined(validWireListing[0], 'first wire view')),
    });
    wrap();
    await screen.findByRole('button', { name: /Synthetic appointment change/ });
    fireEvent.change(bodyField(), { target: { value: 'send me' } });
    await waitFor(() =>
      expect(descriptorsWhere((d) => d.url.endsWith('/staff/mail/drafts')).length).toBe(1),
    );
    fireEvent.click(screen.getByRole('button', { name: mailText('en', 'send') }));
    const firstSend = () => descriptorsWhere((d) => d.url.endsWith('/send'));
    await waitFor(() => expect(firstSend()).toHaveLength(1));
    expect(firstSend()[0]?.body?.confirmedRecipients).toBeUndefined();
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByTestId('mail-confirmation-recipients').textContent).toContain(
      'synthetic-person-nora',
    );
    expect(within(dialog).getByTestId('mail-confirmation-files').textContent).toBeTruthy();
    fireEvent.click(within(dialog).getByRole('button', { name: mailText('en', 'confirm') }));
    await waitFor(() => expect(firstSend()).toHaveLength(2));
    expect(firstSend()[1]?.body?.confirmedRecipients).toEqual(['synthetic-person-nora']);
    expect(firstSend()[1]?.headers['Idempotency-Key']).toBe(firstSend()[0]?.headers['Idempotency-Key']);
    expect(await screen.findByText(/Mail sent to/)).toBeTruthy();
  });

  it('cancels confirmation without publishing', async () => {
    plan({
      publish: () => Promise.reject(failure('precondition-required')),
    });
    wrap();
    await screen.findByRole('button', { name: /Synthetic appointment change/ });
    fireEvent.change(bodyField(), { target: { value: 'send me' } });
    await waitFor(() =>
      expect(descriptorsWhere((d) => d.url.endsWith('/staff/mail/drafts')).length).toBe(1),
    );
    fireEvent.click(screen.getByRole('button', { name: mailText('en', 'send') }));
    const dialog = await screen.findByRole('dialog');
    const cancels = within(dialog).getAllByRole('button', { name: mailText('en', 'cancel') });
    fireEvent.click(defined(cancels[0], 'cancel'));
    expect(descriptorsWhere((d) => d.url.endsWith('/send'))).toHaveLength(1);
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('shows a command error for a non-confirmation send failure', async () => {
    plan({ publish: () => Promise.reject(failure('server', 'corr-send')) });
    wrap();
    await screen.findByRole('button', { name: /Synthetic appointment change/ });
    fireEvent.change(bodyField(), { target: { value: 'send me' } });
    await waitFor(() =>
      expect(descriptorsWhere((d) => d.url.endsWith('/staff/mail/drafts')).length).toBe(1),
    );
    fireEvent.click(screen.getByRole('button', { name: mailText('en', 'send') }));
    expect(await screen.findByTestId('mail-command-error')).toBeTruthy();
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(screen.getByText('corr-send')).toBeTruthy();
  });

  it('primes reply-all with every unique participant except the author', async () => {
    wrap();
    await screen.findByRole('button', { name: /Synthetic appointment change/ });
    fireEvent.click(screen.getByRole('button', { name: mailText('en', 'replyAll') }));
    await waitFor(() =>
      expect(descriptorsWhere((d) => d.url.endsWith('/staff/mail/drafts')).length).toBe(1),
    );
    const created = descriptorsWhere((d) => d.url.endsWith('/staff/mail/drafts'))[0];
    expect(created?.body?.to).toEqual([
      { kind: 'person', value: 'synthetic-patient-001' },
      { kind: 'person', value: 'synthetic-person-nora' },
    ]);
  });

  it('primes a reply to the sender alone and a forward with its attachment', async () => {
    wrap();
    await screen.findByRole('button', { name: /Synthetic appointment change/ });
    fireEvent.click(screen.getByRole('button', { name: mailText('en', 'reply') }));
    await waitFor(() =>
      expect(descriptorsWhere((d) => d.url.endsWith('/staff/mail/drafts')).length).toBe(1),
    );
    const reply = descriptorsWhere((d) => d.url.endsWith('/staff/mail/drafts'))[0];
    expect(reply?.body?.to).toEqual([{ kind: 'person', value: 'synthetic-patient-001' }]);
    fireEvent.click(screen.getByRole('button', { name: mailText('en', 'forward') }));
    await waitFor(() =>
      expect(descriptorsWhere((d) => d.url.endsWith('/staff/mail/drafts')).length).toBe(2),
    );
    const forward = descriptorsWhere((d) => d.url.endsWith('/staff/mail/drafts'))[1];
    expect(forward?.body?.subject).toBe('Fwd: Synthetic appointment change');
    expect(forward?.body?.attachmentDocumentIds).toEqual(['synthetic-document-001']);
  });
});

describe('MailPage tenant isolation and localization', () => {
  it('clears a typed draft and confirmation when the active tenant changes', async () => {
    const { rerender } = wrap();
    await screen.findByRole('button', { name: /Synthetic appointment change/ });
    fireEvent.change(bodyField(), { target: { value: 'alpha private draft' } });
    await waitFor(() =>
      expect(descriptorsWhere((d) => d.url.endsWith('/staff/mail/drafts')).length).toBe(1),
    );
    rerender(element(<MailPage />, 'org-beta', 'en'));
    await waitFor(() =>
      expect(descriptorsWhere((d) => d.headers['X-Active-Organization'] === 'org-beta').length).toBeGreaterThan(0),
    );
    expect(bodyField().value).toBe('');
    expect(screen.queryByText('alpha private draft')).toBeNull();
  });

  it('accepts an injected directory from the owning shell', async () => {
    wrap({ directory: [defined(alphaDirectory[0], 'person entry')] });
    await screen.findByRole('button', { name: /Synthetic appointment change/ });
    const options = within(screen.getByLabelText(mailText('en', 'toPickerLabel'))).getAllByRole('option');
    expect(options).toHaveLength(2);
    expect(options.map((option) => option.getAttribute('value'))).toEqual([
      '',
      'synthetic-person-nora',
    ]);
  });

  it('renders Arabic labels in a right-to-left document', async () => {
    wrap({}, 'org-alpha', 'ar');
    expect(document.documentElement.dir).toBe('rtl');
    expect(await screen.findByText(mailText('ar', 'title'))).toBeTruthy();
    expect(screen.getByRole('button', { name: new RegExp(mailText('ar', 'send')) })).toBeTruthy();
  });
});
