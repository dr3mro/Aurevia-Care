import { useState } from 'react';
import type { ReactElement } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { I18nProvider } from '../../../src/i18n/I18nProvider';
import type { Locale } from '../../../src/i18n/messages';
import { ThemeProvider } from '../../../src/theme/ThemeProvider';
import { BREAKPOINT_MEDIA_QUERIES } from '../../../src/theme/breakpoints';
import { currentRevision } from '../../../src/features/chart/domain';
import type { NoteRevision } from '../../../src/features/chart/domain';
import { syntheticAlphaChart, syntheticBetaChart } from '../../../src/features/chart/fixtures';
import { chartText } from '../../../src/features/chart/messages';
import { NoteEditor } from '../../../src/features/chart/NoteEditor';

/** The three layout bands the responsive hook resolves from `matchMedia`. */
type Band = 'compact' | 'medium' | 'expanded';
type ChangeListener = (event: { matches: boolean }) => void;
type QueryState = { matches: boolean; listeners: Set<ChangeListener> };

/** Handle used to simulate a viewport change after the first render. */
interface MediaHarness {
  setBand: (next: Band) => void;
}

/** Physical inline style members that must never appear in a mirrored layout. */
const PHYSICAL_STYLE_KEYS = [
  'marginLeft',
  'marginRight',
  'marginTop',
  'marginBottom',
  'paddingLeft',
  'paddingRight',
  'paddingTop',
  'paddingBottom',
  'borderLeft',
  'borderRight',
  'borderTop',
  'borderBottom',
  'left',
  'right',
  'top',
  'bottom',
] as const;

/** Deterministic answers for the two breakpoint queries and the pointer query. */
function answersFor(band: Band): Record<string, boolean> {
  return {
    [BREAKPOINT_MEDIA_QUERIES.medium]: band !== 'compact',
    [BREAKPOINT_MEDIA_QUERIES.expanded]: band === 'expanded',
    '(pointer: coarse)': false,
  };
}

/**
 * Install a `matchMedia` stand-in that jsdom can evaluate. The returned handle
 * flips the answers and notifies listeners, which is how a rotation is simulated
 * without remounting the component under test.
 */
function installMatchMedia(band: Band): MediaHarness {
  const state = new Map<string, QueryState>();
  let answers = answersFor(band);
  for (const query of Object.keys(answers)) {
    state.set(query, { matches: answers[query] ?? false, listeners: new Set<ChangeListener>() });
  }
  vi.stubGlobal(
    'matchMedia',
    vi.fn((query: string) => {
      let entry = state.get(query);
      if (!entry) {
        entry = { matches: false, listeners: new Set<ChangeListener>() };
        state.set(query, entry);
      }
      const current = entry;
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
        addListener: (listener: ChangeListener) => {
          current.listeners.add(listener);
        },
        removeListener: (listener: ChangeListener) => {
          current.listeners.delete(listener);
        },
        dispatchEvent: () => false,
      };
    }),
  );
  return {
    setBand(next: Band) {
      answers = answersFor(next);
      for (const [query, entry] of state) {
        entry.matches = answers[query] ?? false;
        for (const listener of entry.listeners) {
          listener({ matches: entry.matches });
        }
      }
    },
  };
}

/** Render a note editor with the shared theme and locale providers. */
function wrap(ui: ReactElement, locale: Locale = 'en') {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>{ui}</I18nProvider>
    </ThemeProvider>,
  );
}

/** Synthetic editable revision: no clinical data from a real patient. */
function draft(): NoteRevision {
  const revision = currentRevision(syntheticBetaChart.revisions);
  if (!revision) {
    throw new Error('missing synthetic draft fixture');
  }
  return revision;
}

/** Synthetic signed revision with provenance and history. */
function signed(): NoteRevision {
  const revision = syntheticAlphaChart.revisions[1];
  if (!revision) {
    throw new Error('missing synthetic signed fixture');
  }
  return revision;
}

/** Default callbacks; individual tests override the ones they observe. */
function editorProps(revision: NoteRevision) {
  return {
    revision,
    history: syntheticAlphaChart.revisions.filter((item) => item.id !== revision.id),
    locale: 'en' as Locale,
    onContentChange: () => undefined,
    onSign: () => undefined,
    onAmend: () => undefined,
    onMarkEnteredInError: () => undefined,
  };
}

/** Assert that every styled node mirrors by logical properties and stays visible. */
function expectLogicalOnly(root: HTMLElement): void {
  const styled = [root, ...Array.from(root.querySelectorAll<HTMLElement>('[style]'))];
  for (const element of styled) {
    expect(element.style.display).not.toBe('none');
    expect(element.style.direction).toBe('');
    for (const key of PHYSICAL_STYLE_KEYS) {
      expect(element.style.getPropertyValue(key)).toBe('');
    }
  }
}

/**
 * Controlled host for the draft so typed text stays in parent state while the
 * breakpoint changes, as it does when a phone is rotated.
 */
function DraftHarness({ revision }: { revision: NoteRevision }) {
  const [content, setContent] = useState(revision.content);
  return (
    <NoteEditor
      {...editorProps(revision)}
      revision={{ ...revision, content }}
      onContentChange={(field, value) => {
        setContent((current) => ({ ...current, [field]: value }));
      }}
    />
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('responsive NoteEditor across breakpoints (WEB-07, UX-01)', () => {
  it('stacks every SOAP field and pins a touch-sized signature bar on compact', () => {
    installMatchMedia('compact');
    wrap(<NoteEditor {...editorProps(draft())} />);

    const editor = screen.getByRole('region', { name: chartText('en', 'clinicalNote') });
    expect(editor.className).toContain('page');
    expect(editor.className).not.toContain('hide-compact');
    expect(editor.getAttribute('data-breakpoint')).toBe('compact');
    expect(editor.getAttribute('data-fields-layout')).toBe('single-column');
    expect(editor.getAttribute('data-signature-layout')).toBe('sticky-footer');
    const editorStyle = editor.getAttribute('style') ?? '';
    expect(editorStyle).toContain('overflow-wrap: anywhere');
    expect(editorStyle).toContain('scroll-padding-block-end');

    const fields = editor.querySelector('[data-part="fields"]');
    expect(fields?.className).toContain('stack');
    expect(fields?.getAttribute('data-layout')).toBe('single-column');
    const textareas = screen.getAllByRole('textbox');
    expect(textareas).toHaveLength(4);
    for (const textarea of textareas) {
      expect(textarea.className).toContain('field');
      expect(textarea.className).toContain('touch');
      expect((textarea as HTMLTextAreaElement).readOnly).toBe(false);
    }

    const signature = editor.querySelector('[data-part="signature"]') as HTMLElement;
    expect(signature.className).toContain('touch');
    expect(signature.style.position).toBe('sticky');
    expect(signature.style.insetBlockEnd).toBe('0px');
    expect(signature.getAttribute('style')).toContain('padding-inline');
    expectLogicalOnly(editor);
  });

  it('uses an inline signature row with the same stacked fields on medium', () => {
    installMatchMedia('medium');
    wrap(<NoteEditor {...editorProps(draft())} />);

    const editor = screen.getByRole('region', { name: chartText('en', 'clinicalNote') });
    expect(editor.getAttribute('data-breakpoint')).toBe('medium');
    expect(editor.getAttribute('data-signature-layout')).toBe('inline');
    expect(editor.querySelector('[data-part="fields"]')?.getAttribute('data-layout')).toBe(
      'single-column',
    );
    const signature = editor.querySelector('[data-part="signature"]') as HTMLElement;
    expect(signature.style.position).toBe('');
    expect(screen.getAllByRole('textbox')).toHaveLength(4);
    expectLogicalOnly(editor);
  });

  it('keeps the inline signature row and every field on expanded', () => {
    installMatchMedia('expanded');
    wrap(<NoteEditor {...editorProps(draft())} />);

    const editor = screen.getByRole('region', { name: chartText('en', 'clinicalNote') });
    expect(editor.getAttribute('data-breakpoint')).toBe('expanded');
    expect(editor.getAttribute('data-signature-layout')).toBe('inline');
    expect(screen.getAllByRole('textbox')).toHaveLength(4);
    expect(editor.querySelector('[data-part="signature"]')).toBeTruthy();
    expectLogicalOnly(editor);
  });

  it('keeps provenance, history and touch-sized actions visible when signed', () => {
    installMatchMedia('compact');
    wrap(<NoteEditor {...editorProps(signed())} />);

    const editor = screen.getByRole('region', { name: chartText('en', 'clinicalNote') });
    const provenance = editor.querySelector('[data-part="provenance"]');
    expect(provenance?.textContent).toContain(signed().checksum);
    expect(editor.querySelector('[data-part="history"]')).toBeTruthy();
    expect(screen.getByRole('list', { name: chartText('en', 'history') })).toBeTruthy();
    expect(screen.getAllByRole('textbox')).toHaveLength(4);
    for (const textarea of screen.getAllByRole('textbox')) {
      expect((textarea as HTMLTextAreaElement).readOnly).toBe(true);
    }
    expect(screen.queryByRole('button', { name: chartText('en', 'sign') })).toBeNull();
    expect(editor.querySelector('[data-part="readonly-notice"]')).toBeTruthy();
    const actions = editor.querySelector('[data-part="actions"]');
    expect(actions?.className).toContain('cluster');
    for (const button of screen.getAllByRole('button')) {
      expect(button.className).toContain('touch');
    }
    expectLogicalOnly(editor);
  });

  it('mirrors under RTL with Arabic labels, logical styles and wrapping long text', () => {
    installMatchMedia('compact');
    const arabicDraft: NoteRevision = {
      ...draft(),
      content: {
        ...draft().content,
        subjective: 'ملاحظة سريرية طويلة جداً تحتاج إلى التفاف في شاشة الهاتف عند القراءة',
      },
    };
    wrap(<NoteEditor {...editorProps(arabicDraft)} locale="ar" />, 'ar');

    expect(document.documentElement.dir).toBe('rtl');
    expect(document.documentElement.lang).toBe('ar');
    const editor = screen.getByRole('region', { name: chartText('ar', 'clinicalNote') });
    expect(editor.getAttribute('data-breakpoint')).toBe('compact');
    for (const field of ['subjective', 'objective', 'assessment', 'plan'] as const) {
      expect(screen.getByText(chartText('ar', field))).toBeTruthy();
      expect(screen.getByRole('textbox', { name: chartText('ar', field) })).toBeTruthy();
    }
    const subjective = screen.getByRole('textbox', {
      name: chartText('ar', 'subjective'),
    }) as HTMLTextAreaElement;
    expect(subjective.value).toContain('ملاحظة سريرية طويلة');
    expect(subjective.className).toContain('field');
    expect(screen.getByRole('checkbox', { name: chartText('ar', 'attestation') })).toBeTruthy();
    expect(screen.getByRole('button', { name: chartText('ar', 'sign') })).toBeTruthy();
    expectLogicalOnly(editor);
  });

  it('keeps a stable SOAP-to-signature keyboard order at every band', () => {
    for (const band of ['compact', 'medium', 'expanded'] as const) {
      installMatchMedia(band);
      const view = wrap(<NoteEditor {...editorProps(draft())} />);
      // Attest first: a disabled sign action is not keyboard reachable.
      fireEvent.click(screen.getByRole('checkbox', { name: chartText('en', 'attestation') }));
      const editor = screen.getByRole('region', { name: chartText('en', 'clinicalNote') });
      const focusables = Array.from(
        editor.querySelectorAll<HTMLElement>('textarea, input, button'),
      ).filter((element) => !(element as HTMLButtonElement).disabled);
      expect(focusables.map((element) => element.tagName.toLowerCase())).toEqual([
        'textarea',
        'textarea',
        'textarea',
        'textarea',
        'input',
        'button',
      ]);
      expect(focusables.at(-1)?.textContent).toBe(chartText('en', 'sign'));
      view.unmount();
    }
  });

  it('preserves typed work, attestation and focus through a simulated rotation (WEB-09)', () => {
    const media = installMatchMedia('compact');
    wrap(<DraftHarness revision={draft()} />);

    const subjective = screen.getByRole('textbox', {
      name: chartText('en', 'subjective'),
    }) as HTMLTextAreaElement;
    const typed =
      'Synthetic long subjective note that must survive a breakpoint change without remounting.';
    fireEvent.change(subjective, { target: { value: typed } });
    expect(subjective.value).toBe(typed);
    fireEvent.click(screen.getByRole('checkbox', { name: chartText('en', 'attestation') }));
    subjective.focus();
    expect(document.activeElement).toBe(subjective);

    act(() => {
      media.setBand('expanded');
    });

    const editor = screen.getByRole('region', { name: chartText('en', 'clinicalNote') });
    expect(editor.getAttribute('data-breakpoint')).toBe('expanded');
    expect(editor.getAttribute('data-signature-layout')).toBe('inline');
    const after = screen.getByRole('textbox', {
      name: chartText('en', 'subjective'),
    }) as HTMLTextAreaElement;
    expect(after.value).toBe(typed);
    expect(
      (screen.getByRole('checkbox', { name: chartText('en', 'attestation') }) as HTMLInputElement)
        .checked,
    ).toBe(true);
    expect(
      (screen.getByRole('button', { name: chartText('en', 'sign') }) as HTMLButtonElement).disabled,
    ).toBe(false);
    expect(document.activeElement).toBe(after);
  });
});
