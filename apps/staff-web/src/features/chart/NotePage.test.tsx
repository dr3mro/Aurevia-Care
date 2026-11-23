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
import { ThemeProvider } from '../../theme/ThemeProvider';
import { NotePage } from './NotePage';
import type { ClinicalNoteRevisionResponse, NoteViewResponse, PatientViewResponse } from './api';
import { chartText } from './messages';
import { must } from '../../../tests/must';

vi.mock('../../api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);

function revision(
  overrides: Partial<ClinicalNoteRevisionResponse> = {},
): ClinicalNoteRevisionResponse {
  return {
    id: 'note-1',
    version: 1,
    state: 'draft',
    patientId: 'patient-1',
    encounterId: 'encounter-1',
    title: 'Follow-up note',
    content: 'Chief concern text',
    language: 'en',
    templateCode: 'soap',
    templateVersion: 3,
    clinicalAuthorSubject: 'dr-1',
    dataEntrySubject: 'reception-1',
    effectiveAt: '2026-03-09',
    signedAt: null,
    signerSubject: null,
    attestation: null,
    contentChecksum: null,
    supersedesVersion: null,
    enteredErrorReason: null,
    enteredErrorActor: null,
    affectedRecipients: null,
    createdAt: '2026-03-09T09:00:00Z',
    ...overrides,
  };
}

function noteView(
  current = revision(),
  history: readonly ClinicalNoteRevisionResponse[] = [],
): NoteViewResponse {
  return { current, history };
}

function patientView(): PatientViewResponse {
  return { patient: { id: 'patient-1', displayName: 'Layla Haddad' }, duplicates: [] };
}

function transportFailure(
  kind: ConstructorParameters<typeof TransportError>[0],
  correlationId = 'corr-1',
) {
  return new TransportError(kind, { correlationId, code: undefined, retryAfterSeconds: undefined });
}

function descriptorAt(index: number): RequestDescriptor {
  return must(sendMock.mock.calls[index])[0];
}

/** Route load reads to their response shape and writes to the command response. */
function resolveByRequest(
  view: NoteViewResponse = noteView(),
  command: ClinicalNoteRevisionResponse = revision(),
): void {
  sendMock.mockImplementation((request) => {
    const call = request;
    if (call.url.startsWith('/api/v1/patients/')) {
      return Promise.resolve(patientView());
    }
    if (call.method === 'GET') {
      return Promise.resolve(view);
    }
    return Promise.resolve(command);
  });
}

const notePatterns = ['/patients/:id/notes/:noteId'];

function element(
  ui: ReactElement,
  organizationId = 'org-a',
  locale: 'en' | 'ar' = 'en',
  path = '/patients/patient-1/notes/note-1',
) {
  return (
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <ActiveOrganizationProvider value={{ organizationId }}>
          <RouterProvider patterns={notePatterns} initialPath={path}>
            {ui}
          </RouterProvider>
        </ActiveOrganizationProvider>
      </I18nProvider>
    </ThemeProvider>
  );
}

/** Load a note, then check the attestation box so the sign action is enabled. */
async function loadedEditor(view = noteView()) {
  resolveByRequest(view);
  render(element(<NotePage />));
  expect(await screen.findByRole('textbox', { name: chartText('en', 'subjective') })).toBeTruthy();
}

beforeEach(() => {
  sendMock.mockReset();
});

describe('NotePage load states', () => {
  it('shows the loading state until the note resolves', () => {
    const pending = new Promise<NoteViewResponse>(() => undefined);
    sendMock.mockImplementation((request) => {
      const call = request;
      if (call.url.startsWith('/api/v1/patients/')) {
        return Promise.resolve(patientView());
      }
      return pending;
    });
    render(element(<NotePage />));
    expect(screen.getByRole('heading', { name: stateViewText('en', 'loading') })).toBeTruthy();
  });

  it.each(['server', 'timeout', 'offline', 'invalid', 'rate-limited'] as const)(
    'shows an error with a correlation id for %s',
    async (kind) => {
      sendMock.mockRejectedValueOnce(transportFailure(kind, `corr-${kind}`));
      render(element(<NotePage />));
      expect(await screen.findByText(stateViewText('en', 'error'))).toBeTruthy();
      expect(screen.getByText(`corr-${kind}`)).toBeTruthy();
    },
  );

  it('shows the forbidden state for a forbidden load', async () => {
    sendMock.mockRejectedValueOnce(transportFailure('forbidden'));
    render(element(<NotePage />));
    expect(
      await screen.findByRole('heading', { name: stateViewText('en', 'forbidden') }),
    ).toBeTruthy();
  });

  it('shows the patient identity and the note editor with a disabled save', async () => {
    await loadedEditor();
    expect((await screen.findAllByText('Layla Haddad')).length).toBeGreaterThan(0);
    expect(screen.getByText('patient-1')).toBeTruthy();
    expect(
      screen.getByRole<HTMLButtonElement>('button', { name: chartText('en', 'saveDraft') })
        .disabled,
    ).toBe(true);
  });

  it('renders in Arabic with a right-to-left document', async () => {
    resolveByRequest();
    render(element(<NotePage />, 'org-a', 'ar'));
    expect(
      await screen.findByRole('textbox', { name: chartText('ar', 'subjective') }),
    ).toBeTruthy();
    expect(document.documentElement.dir).toBe('rtl');
  });

  it('renders with an empty note id when route params are missing', async () => {
    resolveByRequest();
    render(element(<NotePage />, 'org-a', 'en', '/patients'));
    expect(
      await screen.findByRole('heading', { level: 2, name: chartText('en', 'clinicalNote') }),
    ).toBeTruthy();
  });

  it('renders the append-only revision history returned by the API', async () => {
    await loadedEditor(
      noteView(revision({ state: 'signed', version: 2 }), [
        revision({ id: 'note-1', version: 1, state: 'draft' }),
      ]),
    );
    const history = screen.getByRole('list', { name: chartText('en', 'history') });
    expect(history.textContent).toContain('v1');
    expect(history.textContent).toContain(chartText('en', 'noteDraft'));
  });

  it('shows the entered-in-error banner from the API revision', async () => {
    await loadedEditor(
      noteView(
        revision({
          state: 'entered_in_error',
          enteredErrorReason: 'wrong patient',
          enteredErrorActor: 'auditor-1',
          affectedRecipients: '["referrer"]',
        }),
      ),
    );
    expect(screen.getByTestId('entered-in-error').textContent).toContain('wrong patient');
  });
});

describe('NotePage save and conflict', () => {
  it('saves with If-Match and the edited SOAP content', async () => {
    await loadedEditor();
    fireEvent.change(screen.getByRole('textbox', { name: chartText('en', 'subjective') }), {
      target: { value: 'edited concern' },
    });
    fireEvent.click(screen.getByRole('button', { name: chartText('en', 'saveDraft') }));

    await waitFor(() =>
      expect(screen.getByTestId('note-status').textContent).toContain(
        chartText('en', 'savedNotice'),
      ),
    );
    const put = must(
      sendMock.mock.calls
        .map(([request]) => request)
        .find((request) => (request.method as string) === 'PUT'),
    );
    expect(put.url).toBe('/api/v1/clinical-notes/note-1');
    expect(put.headers['If-Match']).toBe('1');
    expect(put.body).toEqual({ title: 'Follow-up note', content: 'edited concern' });
  });

  it('keeps typed text and shows compare, reload and copy on a 409 conflict', async () => {
    await loadedEditor();
    const field = screen.getByRole<HTMLTextAreaElement>('textbox', {
      name: chartText('en', 'subjective'),
    });
    fireEvent.change(field, { target: { value: 'my typed changes' } });
    const putIndex = sendMock.mock.calls.length;
    sendMock.mockRejectedValueOnce(transportFailure('conflict'));

    fireEvent.click(screen.getByRole('button', { name: chartText('en', 'saveDraft') }));
    expect(
      await screen.findByRole('heading', { name: stateViewText('en', 'conflict') }),
    ).toBeTruthy();
    expect(field.value).toBe('my typed changes');
    expect(screen.getByRole('button', { name: stateViewText('en', 'compare') })).toBeTruthy();
    expect(screen.getByRole('button', { name: stateViewText('en', 'reload') })).toBeTruthy();
    expect(screen.getByRole('button', { name: stateViewText('en', 'copyMyChanges') })).toBeTruthy();
    expect(descriptorAt(putIndex).method).toBe('PUT');
  });

  it('keeps the conflict recovery surface inside the note card and keeps typed text', async () => {
    await loadedEditor();
    const field = screen.getByRole<HTMLTextAreaElement>('textbox', {
      name: chartText('en', 'subjective'),
    });
    fireEvent.change(field, { target: { value: 'kept through conflict' } });
    sendMock.mockRejectedValueOnce(transportFailure('conflict'));
    fireEvent.click(screen.getByRole('button', { name: chartText('en', 'saveDraft') }));
    const noteCard = await screen.findByRole('region', { name: chartText('en', 'clinicalNote') });
    expect(
      within(noteCard).getByRole('heading', { name: stateViewText('en', 'conflict') }),
    ).toBeTruthy();
    expect(field.value).toBe('kept through conflict');
    for (const key of ['compare', 'reload', 'copyMyChanges'] as const) {
      expect(
        within(noteCard).getByRole('button', { name: stateViewText('en', key) }),
      ).toBeTruthy();
    }
  });

  it('shows the sign help text for an editable wire-backed draft', async () => {
    await loadedEditor();
    expect(screen.getByText(chartText('en', 'signHelp'))).toBeTruthy();
    expect(screen.queryByText(chartText('en', 'planError'))).toBeNull();
  });

  it('treats a 428 precondition failure as a conflict', async () => {
    await loadedEditor();
    fireEvent.change(screen.getByRole('textbox', { name: chartText('en', 'assessment') }), {
      target: { value: 'typed' },
    });
    sendMock.mockRejectedValueOnce(transportFailure('precondition-required'));
    fireEvent.click(screen.getByRole('button', { name: chartText('en', 'saveDraft') }));
    expect(
      await screen.findByRole('heading', { name: stateViewText('en', 'conflict') }),
    ).toBeTruthy();
  });

  it('shows an error state but keeps typed text for a non-conflict failure', async () => {
    await loadedEditor();
    const field = screen.getByRole<HTMLTextAreaElement>('textbox', {
      name: chartText('en', 'plan'),
    });
    fireEvent.change(field, { target: { value: 'kept plan' } });
    sendMock.mockRejectedValueOnce(transportFailure('server'));
    fireEvent.click(screen.getByRole('button', { name: chartText('en', 'saveDraft') }));
    expect(await screen.findByRole('heading', { name: stateViewText('en', 'error') })).toBeTruthy();
    expect(field.value).toBe('kept plan');
  });

  it('normalises an unexpected rejection into a server state', async () => {
    await loadedEditor();
    fireEvent.change(screen.getByRole('textbox', { name: chartText('en', 'plan') }), {
      target: { value: 'text' },
    });
    sendMock.mockRejectedValueOnce(new Error('unexpected'));
    fireEvent.click(screen.getByRole('button', { name: chartText('en', 'saveDraft') }));
    expect(await screen.findByRole('heading', { name: stateViewText('en', 'error') })).toBeTruthy();
  });

  it('compares the saved revision with typed changes', async () => {
    await loadedEditor();
    fireEvent.change(screen.getByRole('textbox', { name: chartText('en', 'subjective') }), {
      target: { value: 'my version' },
    });
    sendMock.mockRejectedValueOnce(transportFailure('conflict'));
    fireEvent.click(screen.getByRole('button', { name: chartText('en', 'saveDraft') }));
    fireEvent.click(await screen.findByRole('button', { name: stateViewText('en', 'compare') }));
    const panel = screen.getByTestId('note-compare');
    expect(panel.textContent).toContain('Chief concern text');
    expect(panel.textContent).toContain('my version');
  });

  it('copies typed changes when the clipboard is available', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    await loadedEditor();
    fireEvent.change(screen.getByRole('textbox', { name: chartText('en', 'subjective') }), {
      target: { value: 'copy me' },
    });
    sendMock.mockRejectedValueOnce(transportFailure('conflict'));
    fireEvent.click(screen.getByRole('button', { name: chartText('en', 'saveDraft') }));
    fireEvent.click(
      await screen.findByRole('button', { name: stateViewText('en', 'copyMyChanges') }),
    );
    expect(writeText).toHaveBeenCalledWith('copy me');
    expect(screen.getByText(chartText('en', 'copiedNotice'))).toBeTruthy();
    delete (navigator as { clipboard?: unknown }).clipboard;
  });

  it('copies safely when the clipboard API is unavailable', async () => {
    await loadedEditor();
    fireEvent.change(screen.getByRole('textbox', { name: chartText('en', 'subjective') }), {
      target: { value: 'no clipboard' },
    });
    sendMock.mockRejectedValueOnce(transportFailure('conflict'));
    fireEvent.click(screen.getByRole('button', { name: chartText('en', 'saveDraft') }));
    fireEvent.click(
      await screen.findByRole('button', { name: stateViewText('en', 'copyMyChanges') }),
    );
    expect(screen.getByText(chartText('en', 'copiedNotice'))).toBeTruthy();
  });

  it('reloads the authoritative revision from the conflict state', async () => {
    await loadedEditor();
    fireEvent.change(screen.getByRole('textbox', { name: chartText('en', 'subjective') }), {
      target: { value: 'discard me' },
    });
    sendMock.mockRejectedValueOnce(transportFailure('conflict'));
    fireEvent.click(screen.getByRole('button', { name: chartText('en', 'saveDraft') }));
    const before = sendMock.mock.calls.length;
    fireEvent.click(await screen.findByRole('button', { name: stateViewText('en', 'reload') }));
    await waitFor(() => expect(sendMock.mock.calls.length).toBeGreaterThan(before));
    expect(screen.queryByRole('heading', { name: stateViewText('en', 'conflict') })).toBeNull();
  });
});

async function openSignDialog(view = noteView()) {
  await loadedEditor(view);
  fireEvent.click(screen.getByRole('checkbox', { name: chartText('en', 'attestation') }));
  fireEvent.click(screen.getByRole('button', { name: chartText('en', 'sign') }));
  return screen.findByRole('dialog', { name: chartText('en', 'signConfirmTitle') });
}

describe('NotePage clinical commands', () => {
  it('asks for a confirmation naming the note, the effect and the attestation', async () => {
    const dialog = await openSignDialog();
    expect(dialog.textContent).toContain('Follow-up note');
    expect(dialog.textContent).toContain(chartText('en', 'signEffect'));
    expect(dialog.textContent).toContain(chartText('en', 'attestation'));
    expect(within(dialog).getByRole('button', { name: chartText('en', 'sign') })).toBeTruthy();
  });

  it('keeps the confirmation actions keyboard reachable', async () => {
    const dialog = await openSignDialog();
    const confirm = within(dialog).getByRole('button', { name: chartText('en', 'sign') });
    expect(confirm.tagName).toBe('BUTTON');
    confirm.focus();
    expect(document.activeElement).toBe(confirm);
    // No document-level Tab trap may steal focus back to Cancel.
    fireEvent.keyDown(document, { key: 'Tab' });
    expect(document.activeElement).toBe(confirm);
    fireEvent.click(confirm);
    await waitFor(() =>
      expect(screen.getByTestId('note-status').textContent).toContain(
        chartText('en', 'signedNotice'),
      ),
    );
  });

  it('warns about unsaved edits and refuses to sign them silently', async () => {
    await loadedEditor();
    fireEvent.change(screen.getByRole('textbox', { name: chartText('en', 'subjective') }), {
      target: { value: 'unsaved text' },
    });
    expect(screen.getByTestId('unsaved-changes').textContent).toContain(
      chartText('en', 'unsavedChanges'),
    );
    fireEvent.click(screen.getByRole('checkbox', { name: chartText('en', 'attestation') }));
    fireEvent.click(screen.getByRole('button', { name: chartText('en', 'sign') }));
    expect(screen.queryByRole('dialog', { name: chartText('en', 'signConfirmTitle') })).toBeNull();
    expect(screen.getByTestId('note-status').textContent).toContain(
      chartText('en', 'saveBeforeSign'),
    );
    expect(sendMock.mock.calls.some(([request]) => request.url.endsWith('/sign'))).toBe(false);
  });

  it('closes the sign confirmation and exposes conflict recovery after a stale sign', async () => {
    await openSignDialog();
    sendMock.mockRejectedValueOnce(transportFailure('conflict'));
    const dialog = screen.getByRole('dialog', { name: chartText('en', 'signConfirmTitle') });
    fireEvent.click(within(dialog).getByRole('button', { name: chartText('en', 'sign') }));
    expect(
      await screen.findByRole('heading', { name: stateViewText('en', 'conflict') }),
    ).toBeTruthy();
    expect(screen.queryByRole('dialog', { name: chartText('en', 'signConfirmTitle') })).toBeNull();
    expect(screen.getByRole('button', { name: stateViewText('en', 'copyMyChanges') })).toBeTruthy();
  });

  it('signs the draft and shows the attestation returned by the API', async () => {
    resolveByRequest(noteView(), revision({ state: 'signed', attestation: 'signed by dr-1' }));
    render(element(<NotePage />));
    await screen.findByRole('textbox', { name: chartText('en', 'subjective') });
    fireEvent.click(screen.getByRole('checkbox', { name: chartText('en', 'attestation') }));
    fireEvent.click(screen.getByRole('button', { name: chartText('en', 'sign') }));
    const dialog = await screen.findByRole('dialog', { name: chartText('en', 'signConfirmTitle') });
    fireEvent.click(within(dialog).getByRole('button', { name: chartText('en', 'sign') }));

    await waitFor(() =>
      expect(screen.getByTestId('signer-attestation').textContent).toContain('signed by dr-1'),
    );
    const post = must(
      sendMock.mock.calls
        .map(([request]) => request)
        .find((request) => request.url.endsWith('/sign')),
    );
    expect(post.method).toBe('POST');
    expect(post.headers['If-Match']).toBe('1');
    expect(post.body).toEqual({ attestation: chartText('en', 'attestation') });
  });

  it('signs an amendment through the addendum command', async () => {
    const dialog = await openSignDialog(
      noteView(revision({ state: 'amendment_draft', supersedesVersion: 1 })),
    );
    fireEvent.click(within(dialog).getByRole('button', { name: chartText('en', 'sign') }));
    await waitFor(() =>
      expect(sendMock.mock.calls.some(([request]) => request.url.endsWith('/sign-addendum'))).toBe(
        true,
      ),
    );
  });

  it('omits the attestation panel when the API returns none', async () => {
    const dialog = await openSignDialog();
    fireEvent.click(within(dialog).getByRole('button', { name: chartText('en', 'sign') }));
    await waitFor(() =>
      expect(screen.getByTestId('note-status').textContent).toContain(
        chartText('en', 'signedNotice'),
      ),
    );
    expect(screen.queryByTestId('signer-attestation')).toBeNull();
  });

  it('shows a conflict when signing a stale revision', async () => {
    await openSignDialog();
    const dialog = screen.getByRole('dialog', { name: chartText('en', 'signConfirmTitle') });
    sendMock.mockRejectedValueOnce(transportFailure('conflict'));
    fireEvent.click(within(dialog).getByRole('button', { name: chartText('en', 'sign') }));
    expect(
      await screen.findByRole('heading', { name: stateViewText('en', 'conflict') }),
    ).toBeTruthy();
  });
});

describe('NotePage amendment and entered-in-error commands', () => {
  it('opens a separate amendment draft', async () => {
    await loadedEditor(noteView(revision({ state: 'signed' })));
    fireEvent.click(screen.getByRole('button', { name: chartText('en', 'amend') }));
    await waitFor(() =>
      expect(sendMock.mock.calls.some(([request]) => request.url.endsWith('/amend'))).toBe(true),
    );
    expect(screen.getByTestId('note-status').textContent).toContain(chartText('en', 'amendNotice'));
  });

  it('closes the sign confirmation without signing', async () => {
    const dialog = await openSignDialog();
    fireEvent.click(within(dialog).getByRole('button', { name: chartText('en', 'cancel') }));
    await waitFor(() =>
      expect(
        screen.queryByRole('dialog', { name: chartText('en', 'signConfirmTitle') }),
      ).toBeNull(),
    );
    expect(sendMock.mock.calls.some(([request]) => request.url.endsWith('/sign'))).toBe(false);
  });

  it('closes the entered-in-error confirmation without recording', async () => {
    await loadedEditor(noteView(revision({ state: 'signed' })));
    fireEvent.click(screen.getByRole('button', { name: chartText('en', 'markEnteredInError') }));
    const dialog = await screen.findByRole('dialog', {
      name: chartText('en', 'markEnteredInError'),
    });
    fireEvent.click(within(dialog).getByRole('button', { name: chartText('en', 'cancel') }));
    await waitFor(() =>
      expect(
        screen.queryByRole('dialog', { name: chartText('en', 'markEnteredInError') }),
      ).toBeNull(),
    );
  });

  it('shows a conflict when opening an amendment from a stale revision', async () => {
    await loadedEditor(noteView(revision({ state: 'signed' })));
    sendMock.mockRejectedValueOnce(transportFailure('conflict'));
    fireEvent.click(screen.getByRole('button', { name: chartText('en', 'amend') }));
    expect(
      await screen.findByRole('heading', { name: stateViewText('en', 'conflict') }),
    ).toBeTruthy();
  });

  it('requires a reason before recording entered-in-error', async () => {
    await loadedEditor(noteView(revision({ state: 'signed' })));
    fireEvent.click(screen.getByRole('button', { name: chartText('en', 'markEnteredInError') }));
    const dialog = await screen.findByRole('dialog', {
      name: chartText('en', 'markEnteredInError'),
    });
    const confirm = within(dialog).getByRole<HTMLButtonElement>('button', {
      name: chartText('en', 'markEnteredInError'),
    });
    expect(confirm.disabled).toBe(true);
    fireEvent.change(screen.getByLabelText(chartText('en', 'enterErrorReason')), {
      target: { value: 'wrong patient' },
    });
    expect(confirm.disabled).toBe(false);
    fireEvent.click(confirm);
    await waitFor(() =>
      expect(screen.getByTestId('note-status').textContent).toContain(
        chartText('en', 'enterErrorNotice'),
      ),
    );
    const post = must(
      sendMock.mock.calls
        .map(([request]) => request)
        .find((request) => request.url.endsWith('/entered-in-error')),
    );
    expect(post.body).toEqual({ reason: 'wrong patient', affectedRecipients: '["reception-1"]' });
  });

  it('shows an error when the entered-in-error command fails and closes its confirmation', async () => {
    await loadedEditor(noteView(revision({ state: 'signed' })));
    fireEvent.click(screen.getByRole('button', { name: chartText('en', 'markEnteredInError') }));
    const dialog = await screen.findByRole('dialog', {
      name: chartText('en', 'markEnteredInError'),
    });
    fireEvent.change(screen.getByLabelText(chartText('en', 'enterErrorReason')), {
      target: { value: 'wrong patient' },
    });
    sendMock.mockRejectedValueOnce(transportFailure('server'));
    fireEvent.click(
      within(dialog).getByRole('button', { name: chartText('en', 'markEnteredInError') }),
    );
    expect(await screen.findByRole('heading', { name: stateViewText('en', 'error') })).toBeTruthy();
    expect(
      screen.queryByRole('dialog', { name: chartText('en', 'markEnteredInError') }),
    ).toBeNull();
  });
});

describe('NotePage unsaved-changes guard and load failure', () => {
  it('asks before leaving only while the draft is dirty', async () => {
    await loadedEditor();
    const clean = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(clean);
    expect(clean.defaultPrevented).toBe(false);

    fireEvent.change(screen.getByRole('textbox', { name: chartText('en', 'subjective') }), {
      target: { value: 'unsaved' },
    });
    const dirty = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(dirty);
    expect(dirty.defaultPrevented).toBe(true);
  });

  it('keeps the editor and shows a load banner when a reload fails', async () => {
    await loadedEditor();
    sendMock.mockImplementation((request) => {
      const call = request;
      if (call.url.startsWith('/api/v1/patients/')) {
        return Promise.resolve(patientView());
      }
      if (call.method === 'GET') {
        return Promise.reject(transportFailure('server', 'reload-failed'));
      }
      return Promise.resolve(revision());
    });
    fireEvent.change(screen.getByRole('textbox', { name: chartText('en', 'subjective') }), {
      target: { value: 'save me' },
    });
    fireEvent.click(screen.getByRole('button', { name: chartText('en', 'saveDraft') }));
    expect(await screen.findByTestId('note-page-load-error')).toBeTruthy();
    expect(screen.getByRole('textbox', { name: chartText('en', 'subjective') })).toBeTruthy();
  });
});

describe('NotePage route registration', () => {
  it('lazily loads the registered note page', async () => {
    const route = must(
      createRoutes(false).find((candidate) => candidate.pattern === '/patients/:id/notes/:noteId'),
    );
    resolveByRequest();
    render(
      element(
        <Suspense fallback={<p>Loading</p>}>
          <route.component />
        </Suspense>,
      ),
    );
    expect(
      await screen.findByRole('textbox', { name: chartText('en', 'subjective') }),
    ).toBeTruthy();
  });
});
