import type { ReactElement } from 'react';
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ActiveOrganizationProvider } from '../../../src/api/activeOrganization';
import type { RequestDescriptor } from '../../../src/api/client';
import { TransportError, send } from '../../../src/api/transport';
import type * as TransportModule from '../../../src/api/transport';
import { RouterProvider } from '../../../src/app/router';
import { stateViewText } from '../../../src/components/stateViewMessages';
import { NotePage } from '../../../src/features/chart/NotePage';
import type {
  ClinicalNoteRevisionResponse,
  NoteViewResponse,
  PatientViewResponse,
} from '../../../src/features/chart/api';
import { chartText } from '../../../src/features/chart/messages';
import { I18nProvider } from '../../../src/i18n/I18nProvider';
import { BREAKPOINT_MEDIA_QUERIES, BREAKPOINT_WIDTHS } from '../../../src/theme/breakpoints';
import { ThemeProvider } from '../../../src/theme/ThemeProvider';

vi.mock('../../../src/api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);

type ChangeListener = (event: MediaQueryListEvent) => void;

interface ViewportState {
  medium: boolean;
  expanded: boolean;
  coarse: boolean;
  portrait: boolean;
}

/** Synthetic viewport states mirroring the responsive Playwright matrix. */
const VIEWPORTS: Record<'compact' | 'medium' | 'expanded', ViewportState> = {
  compact: { medium: false, expanded: false, coarse: true, portrait: true },
  medium: { medium: true, expanded: false, coarse: true, portrait: false },
  expanded: { medium: true, expanded: true, coarse: false, portrait: false },
};

/** Long synthetic strings prove clinical text wraps instead of forcing a scroll. */
const LONG_ENGLISH_TITLE =
  'Synthetic follow-up assessment with an unusually long descriptive title that must wrap';
const LONG_ARABIC_TITLE = 'عنوان تجريبي طويل جداً لملاحظة سريرية يجب أن يلتف دون قص أو تداخل';

/** Build a synthetic note revision; every field is fabricated test data. */
function revision(overrides: Partial<ClinicalNoteRevisionResponse> = {}): ClinicalNoteRevisionResponse {
  return {
    id: 'note-1', version: 1, state: 'draft', patientId: 'patient-1', encounterId: 'encounter-1',
    title: LONG_ENGLISH_TITLE, content: 'Chief concern text', language: 'en', templateCode: 'soap',
    templateVersion: 3, clinicalAuthorSubject: 'dr-1', dataEntrySubject: 'reception-1',
    effectiveAt: '2026-03-09', signedAt: null, signerSubject: null, attestation: null,
    contentChecksum: null, supersedesVersion: null, enteredErrorReason: null,
    enteredErrorActor: null, affectedRecipients: null, createdAt: '2026-03-09T09:00:00Z', ...overrides,
  };
}

function noteView(current = revision(), history: readonly ClinicalNoteRevisionResponse[] = []): NoteViewResponse {
  return { current, history };
}

function patientView(): PatientViewResponse {
  return { patient: { id: 'patient-1', displayName: 'Synthetic Long Patient Name For Wrapping' }, duplicates: [] };
}

/** Route load reads to their response shape and writes to the command response. */
function resolveByRequest(view: NoteViewResponse = noteView()): void {
  sendMock.mockImplementation((request) => {
    const call = request as RequestDescriptor;
    if (call.url.startsWith('/api/v1/patients/')) {
      return Promise.resolve(patientView());
    }
    if (call.method === 'GET') {
      return Promise.resolve(view);
    }
    return Promise.resolve(revision());
  });
}

/**
 * jsdom cannot evaluate media queries. Install a deterministic stand-in whose
 * answers follow the synthetic viewport state so the real breakpoint hook
 * resolves at each width (WEB-07, UX-01). A change listener is retained so a
 * mid-test resize can be fired.
 */
function installMatchMedia(state: ViewportState) {
  const answers: Record<string, boolean> = {
    [BREAKPOINT_MEDIA_QUERIES.medium]: state.medium,
    [BREAKPOINT_MEDIA_QUERIES.expanded]: state.expanded,
    '(pointer: coarse)': state.coarse,
    '(orientation: portrait)': state.portrait,
  };
  const listeners = new Map<string, Set<ChangeListener>>();
  vi.stubGlobal(
    'matchMedia',
    vi.fn((query: string) => {
      const current = {
        matches: answers[query] ?? false,
        listeners: listeners.get(query) ?? new Set<ChangeListener>(),
      };
      listeners.set(query, current.listeners);
      return {
        get matches() {
          return current.matches;
        },
        media: query,
        onchange: null,
        addEventListener: (_type: string, listener: ChangeListener) => {
          current.listeners.add(listener);
        },
        removeEventListener: (_type: string, listener: ChangeListener) => {
          current.listeners.delete(listener);
        },
      } as unknown as MediaQueryList;
    }),
  );
  return {
    fire(query: string, matches: boolean) {
      answers[query] = matches;
      for (const listener of listeners.get(query) ?? []) {
        listener({ matches } as MediaQueryListEvent);
      }
    },
  };
}

function element(ui: ReactElement, locale: 'en' | 'ar' = 'en') {
  return (
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <ActiveOrganizationProvider value={{ organizationId: 'org-a' }}>
          <RouterProvider
            patterns={['/patients/:id/notes/:noteId']}
            initialPath="/patients/patient-1/notes/note-1"
          >
            {ui}
          </RouterProvider>
        </ActiveOrganizationProvider>
      </I18nProvider>
    </ThemeProvider>
  );
}

async function loadNote(locale: 'en' | 'ar' = 'en', view = noteView()) {
  resolveByRequest(view);
  render(element(<NotePage />, locale));
  return screen.findByRole('textbox', { name: chartText(locale, 'subjective') });
}

async function openSignDialog(locale: 'en' | 'ar' = 'en', view = noteView()) {
  await loadNote(locale, view);
  fireEvent.click(screen.getByRole('checkbox', { name: chartText(locale, 'attestation') }));
  fireEvent.click(screen.getByRole('button', { name: chartText(locale, 'sign') }));
  return screen.findByRole('dialog', { name: chartText(locale, 'signConfirmTitle') });
}

async function openEnteredErrorDialog(locale: 'en' | 'ar' = 'en') {
  await loadNote(locale, noteView(revision({ state: 'signed' })));
  fireEvent.click(screen.getByRole('button', { name: chartText(locale, 'markEnteredInError') }));
  return screen.findByRole('dialog', { name: chartText(locale, 'markEnteredInError') });
}

/** Unique responsive root: child components also expose data-breakpoint. */
function noteRoot(): HTMLElement {
  const root = document.querySelector('[data-note-page-view]');
  if (!root) {
    throw new Error('responsive NotePage root with data-note-page-view was not rendered');
  }
  return root as HTMLElement;
}

const physicalStyleKeys = [
  'marginLeft', 'marginRight', 'paddingLeft', 'paddingRight',
  'borderLeft', 'borderRight', 'left', 'right',
];

beforeEach(() => {
  sendMock.mockReset();
});

afterEach(() => {
  vi.unstubAllGlobals();
  document.documentElement.removeAttribute('dir');
  document.documentElement.removeAttribute('lang');
});

describe.each(['compact', 'medium', 'expanded'] as const)(
  'responsive NotePage at %s width (WEB-07, UX-01)',
  (breakpoint) => {
    it('resolves the breakpoint and renders the page grid with stacked or inline actions', async () => {
      installMatchMedia(VIEWPORTS[breakpoint]);
      await loadNote();

      const root = noteRoot();
      expect(root.getAttribute('data-breakpoint')).toBe(breakpoint);
      expect(root.classList.contains('page')).toBe(true);
      expect(root.getAttribute('data-note-page-view')).toBe(
        breakpoint === 'compact' ? 'sticky-save-action' : 'inline-save-action',
      );

      // The patient identity line is a wrapping cluster and the SOAP fields
      // stay stacked in the single-column page grid at every breakpoint.
      const identity = root.querySelector('header > p');
      expect(identity?.classList.contains('cluster')).toBe(true);
      expect(identity?.textContent).toContain('Synthetic Long Patient Name For Wrapping');
      for (const key of ['subjective', 'objective', 'assessment', 'plan'] as const) {
        expect(screen.getByRole('textbox', { name: chartText('en', key) })).toBeTruthy();
      }

      const saveBar = root.querySelector('[data-note-save-bar]') as HTMLElement | null;
      expect(saveBar).not.toBeNull();
      expect(saveBar?.style.position).toBe(breakpoint === 'compact' ? 'sticky' : '');
      const save = screen.getByRole('button', { name: chartText('en', 'saveDraft') });
      expect(save.classList.contains('touch')).toBe(true);
    });

    it('keeps labelled roles and the DOM keyboard order at this breakpoint', async () => {
      installMatchMedia(VIEWPORTS[breakpoint]);
      await loadNote();

      const root = noteRoot();
      expect(screen.getByRole('heading', { level: 2, name: chartText('en', 'clinicalNote') })).toBeTruthy();
      const textboxes = screen.getAllByRole('textbox');
      expect(textboxes.map((node) => node.getAttribute('aria-label'))).toEqual([
        chartText('en', 'subjective'),
        chartText('en', 'objective'),
        chartText('en', 'assessment'),
        chartText('en', 'plan'),
      ]);
      const focusables = Array.from(
        root.querySelectorAll<HTMLElement>('textarea, input, button:not([disabled])'),
      );
      expect(focusables.indexOf(textboxes[0] as HTMLElement)).toBeLessThan(
        focusables.indexOf(screen.getByRole('checkbox', { name: chartText('en', 'attestation') })),
      );
      for (const field of textboxes) {
        expect((field as HTMLElement).tabIndex).toBe(0);
        field.focus();
        expect(document.activeElement).toBe(field);
      }
    });

    it('uses logical properties only and hides no clinical field with display:none', async () => {
      installMatchMedia(VIEWPORTS[breakpoint]);
      await loadNote();

      const root = noteRoot();
      for (const element of Array.from(root.querySelectorAll<HTMLElement>('[style]')).concat(root)) {
        expect(element.style.direction).toBe('');
        expect(element.style.display).not.toBe('none');
        for (const key of physicalStyleKeys) {
          expect(element.style.getPropertyValue(key)).toBe('');
        }
      }
      expect(root.querySelector('.hide-compact')).toBeNull();
      expect(root.querySelector('.only-compact')).toBeNull();
      for (const key of ['subjective', 'objective', 'assessment', 'plan'] as const) {
        expect(screen.getByRole('textbox', { name: chartText('en', key) }).style.display).not.toBe('none');
      }
    });

    it('keeps long English clinical text wrapping without nowrap or a fixed block size', async () => {
      installMatchMedia(VIEWPORTS[breakpoint]);
      await loadNote();

      const identity = noteRoot().querySelector('header > p') as HTMLElement;
      expect(identity.textContent).toContain('Synthetic Long Patient Name For Wrapping');
      expect(identity.style.overflowWrap).toBe('anywhere');
      expect(identity.style.whiteSpace).not.toBe('nowrap');
      expect(identity.style.display).not.toBe('none');
    });
  },
);

describe('responsive NotePage viewport matrix', () => {
  const widths = [320, 360, 390, 768, 820, 1024, 1280] as const;

  it.each(widths)('resolves %ipx to the correct layout band', async (width) => {
    installMatchMedia({
      medium: width >= BREAKPOINT_WIDTHS.medium,
      expanded: width >= BREAKPOINT_WIDTHS.expanded,
      coarse: width < BREAKPOINT_WIDTHS.expanded,
      portrait: width < BREAKPOINT_WIDTHS.medium,
    });
    await loadNote();

    const expected = width >= BREAKPOINT_WIDTHS.expanded
      ? 'expanded'
      : width >= BREAKPOINT_WIDTHS.medium
        ? 'medium'
        : 'compact';
    expect(noteRoot().getAttribute('data-breakpoint')).toBe(expected);
  });
});

describe('responsive NotePage transitions, RTL and confirmations', () => {
  it('switches the action bar when the viewport grows past compact', async () => {
    const media = installMatchMedia(VIEWPORTS.compact);
    await loadNote();
    expect(noteRoot().getAttribute('data-note-page-view')).toBe('sticky-save-action');

    act(() => media.fire(BREAKPOINT_MEDIA_QUERIES.medium, true));
    expect(noteRoot().getAttribute('data-note-page-view')).toBe('inline-save-action');
    expect((noteRoot().querySelector('[data-note-save-bar]') as HTMLElement).style.position).toBe('');
  });

  it('mirrors under RTL with a long Arabic note without a local direction override', async () => {
    installMatchMedia(VIEWPORTS.compact);
    await loadNote('ar', noteView(revision({ title: LONG_ARABIC_TITLE, language: 'ar' })));

    const root = noteRoot();
    expect(document.documentElement.dir).toBe('rtl');
    expect(document.documentElement.lang).toBe('ar');
    expect(root.getAttribute('data-breakpoint')).toBe('compact');
    expect(screen.getAllByText(/ملاحظة|تجريبي|عنوان/).length).toBeGreaterThan(0);

    // Identifiers are atomic left-to-right runs and must not mirror; every
    // inline style stays logical so the document direction mirrors the flow.
    const identifier = root.querySelector('code');
    expect(identifier?.getAttribute('dir')).toBe('ltr');
    for (const element of Array.from(root.querySelectorAll<HTMLElement>('[style]')).concat(root)) {
      expect(element.style.direction).toBe('');
      expect(element.style.display).not.toBe('none');
      for (const key of physicalStyleKeys) {
        expect(element.style.getPropertyValue(key)).toBe('');
      }
    }
    const identity = root.querySelector('header > p') as HTMLElement;
    expect(identity.style.overflowWrap).toBe('anywhere');
  });

  it('keeps the sign confirmation keyboard reachable with a sticky action row on compact', async () => {
    installMatchMedia(VIEWPORTS.compact);
    const dialog = await openSignDialog();

    expect(dialog.getAttribute('data-note-confirmation-view')).toBe('sheet-actions');
    const actionRow = dialog.querySelector('[data-note-confirm-actions]') as HTMLElement;
    expect(actionRow.style.position).toBe('sticky');
    expect(actionRow.style.insetBlockEnd).toBe('0px');
    const cancel = within(dialog).getByRole('button', { name: chartText('en', 'cancel') });
    const sign = within(dialog).getByRole('button', { name: chartText('en', 'sign') });
    expect(sign.tagName).toBe('BUTTON');
    cancel.focus();
    expect(document.activeElement).toBe(cancel);
    sign.focus();
    expect(document.activeElement).toBe(sign);
    fireEvent.keyDown(document, { key: 'Tab' });
    expect(document.activeElement).toBe(sign);
  });

  it('keeps the sign confirmation actions inline on expanded desktops', async () => {
    installMatchMedia(VIEWPORTS.expanded);
    const dialog = await openSignDialog();

    expect(dialog.getAttribute('data-note-confirmation-view')).toBe('inline-actions');
    const actionRow = dialog.querySelector('[data-note-confirm-actions]') as HTMLElement;
    expect(actionRow.style.position).toBe('');
  });

  it('gives the entered-in-error reason field a 16px, touch-sized input at every band', async () => {
    installMatchMedia(VIEWPORTS.compact);
    const dialog = await openEnteredErrorDialog();
    const reason = screen.getByLabelText(chartText('en', 'enterErrorReason'));

    expect(reason.classList.contains('field')).toBe(true);
    expect(reason.classList.contains('touch')).toBe(true);
    expect(reason.style.display).not.toBe('none');
    expect(reason.getAttribute('style') ?? '').not.toMatch(/font-size\s*:\s*(?!16px)/);
    expect(dialog.getAttribute('data-note-confirmation-view')).toBe('sheet-actions');
  });

  it('wraps long compare text with logical white-space and no fixed block size', async () => {
    installMatchMedia(VIEWPORTS.compact);
    await loadNote();
    fireEvent.change(screen.getByRole('textbox', { name: chartText('en', 'subjective') }), {
      target: { value: LONG_ENGLISH_TITLE },
    });
    sendMock.mockRejectedValueOnce(
      new TransportError('conflict', {
        correlationId: 'corr-compare',
        code: undefined,
        retryAfterSeconds: undefined,
      }),
    );
    fireEvent.click(screen.getByRole('button', { name: chartText('en', 'saveDraft') }));
    fireEvent.click(await screen.findByRole('button', { name: stateViewText('en', 'compare') }));

    const panels = screen.getByTestId('note-compare').querySelectorAll('pre');
    expect(panels).toHaveLength(2);
    for (const panel of Array.from(panels)) {
      const pre = panel as HTMLElement;
      expect(pre.style.overflowWrap).toBe('anywhere');
      expect(pre.style.whiteSpace).toBe('pre-wrap');
      expect(pre.style.blockSize).toBe('');
    }
    expect(screen.getByTestId('note-compare').classList.contains('stack')).toBe(true);
  });
});
