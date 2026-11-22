import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { DocumentList } from '../../../src/features/chart/DocumentList';
import { syntheticAlphaChart } from '../../../src/features/chart/fixtures';
import { chartText } from '../../../src/features/chart/messages';
import type { DocumentAssociation } from '../../../src/features/chart/domain';
import { BREAKPOINT_MEDIA_QUERIES } from '../../../src/theme/breakpoints';
import { ThemeProvider } from '../../../src/theme/ThemeProvider';

type ChangeListener = (event: MediaQueryListEvent) => void;

interface QueryState {
  matches: boolean;
  listeners: Set<ChangeListener>;
}

/**
 * Deterministic matchMedia stand-in keyed by media query string. One state
 * object is reused per query so the hook's initializer and effect see the same
 * listeners. All document data below is synthetic.
 */
function installMatchMedia(initial: Record<string, boolean>) {
  const states = new Map<string, QueryState>();
  const state = (query: string): QueryState => {
    let current = states.get(query);
    if (!current) {
      current = { matches: initial[query] ?? false, listeners: new Set() };
      states.set(query, current);
    }
    return current;
  };
  vi.stubGlobal(
    'matchMedia',
    vi.fn((query: string) => {
      const current = state(query);
      return {
        get matches() {
          return current.matches;
        },
        addEventListener: (_type: string, listener: ChangeListener) => {
          current.listeners.add(listener);
        },
        removeEventListener: (_type: string, listener: ChangeListener) => {
          current.listeners.delete(listener);
        },
      };
    }),
  );
  return {
    fire(query: string, matches: boolean) {
      const current = state(query);
      current.matches = matches;
      for (const listener of current.listeners) {
        listener({ matches } as MediaQueryListEvent);
      }
    },
  };
}

const layouts = {
  compact: { medium: false, expanded: false },
  medium: { medium: true, expanded: false },
  expanded: { medium: true, expanded: true },
} as const;

type Layout = keyof typeof layouts;

function queriesFor(layout: Layout): Record<string, boolean> {
  return {
    [BREAKPOINT_MEDIA_QUERIES.medium]: layouts[layout].medium,
    [BREAKPOINT_MEDIA_QUERIES.expanded]: layouts[layout].expanded,
  };
}

const LONG_ENGLISH =
  'A deliberately long synthetic document title that must wrap at 320 px and at 200 percent zoom without clipping or overlap';
const LONG_ARABIC = 'عنوان مستند تجريبي طويل جداً يجب أن يلتف دون قص أو تداخل عند العرض الضيق';

const documents: readonly DocumentAssociation[] = syntheticAlphaChart.documents;
const noop = () => undefined;

function renderList(items: readonly DocumentAssociation[] = documents, locale: 'en' | 'ar' = 'en') {
  return render(
    <ThemeProvider>
      <DocumentList documents={items} locale={locale} onVoid={noop} onRotate={noop} />
    </ThemeProvider>,
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
  document.documentElement.removeAttribute('dir');
  document.documentElement.removeAttribute('lang');
});

/** Assert no inline style hides clinical content or introduces a physical side. */
function expectLogicalAndVisible(root: HTMLElement) {
  const elements = [root, ...Array.from(root.querySelectorAll('*'))] as HTMLElement[];
  for (const element of elements) {
    const style = element.getAttribute('style') ?? '';
    expect(style).not.toContain('display: none');
    expect(style).not.toMatch(/(?:margin|padding|border|inset)-(?:left|right)\s*:/);
    expect(style).not.toMatch(/text-align\s*:\s*(?:left|right)/);
    expect(element.style.direction).toBe('');
  }
}

describe('responsive DocumentList (WEB-07, UX-01)', () => {
  it('renders compact rows as stacked cards with every clinical field and a wrapped file name', () => {
    installMatchMedia(queriesFor('compact'));
    const longTitle: DocumentAssociation = {
      organizationId: 'SYN-ORG-01',
      id: 'doc-long',
      chartLocalId: 'chart-001',
      kind: 'pdf',
      title: LONG_ENGLISH,
      version: 3,
      language: 'en',
      checksum: 'synthetic:doc-long',
      linkedTo: 'chart:chart-001',
      rotationDegrees: 0,
    };
    renderList([longTitle, ...documents]);

    // Cards replace the table on a phone; no clinical column is hidden.
    expect(screen.queryByRole('table')).toBeNull();
    const list = screen.getByRole('list', {
      name: chartText('en', 'documentList'),
    }) as HTMLElement;
    expect(list.className).toContain('card-list');

    const cards = within(list).getAllByRole('listitem') as HTMLElement[];
    expect(cards).toHaveLength(documents.length + 1);
    for (const card of cards) {
      expect(card.className).toContain('stack');
      for (const label of [
        'documentKind',
        'documentVersion',
        'documentLanguage',
        'documentChecksum',
        'documentLinkedTo',
        'documentRotation',
        'documentStatus',
      ] as const) {
        expect(within(card).getByText(chartText('en', label))).toBeTruthy();
      }
      // Each field label and value is its own stacked row, not a hidden column.
      for (const group of within(card).getAllByRole('term')) {
        expect((group.parentElement as HTMLElement).className).toContain('stack');
      }
    }

    const wrappedName = screen.getByRole('heading', { level: 4, name: LONG_ENGLISH }) as HTMLElement;
    const run = within(wrappedName).getByText(LONG_ENGLISH) as HTMLElement;
    expect(run.tagName).toBe('BDI');
    expect(run.getAttribute('dir')).toBe('auto');
    expect(run.getAttribute('lang')).toBe('en');
    expect(run.style.whiteSpace).toBe('normal');
    expect(run.style.overflowWrap).toBe('anywhere');

    // Synthetic values are readable, not truncated, and both actions survive.
    const firstCard = cards[0]!;
    expect(within(firstCard).getByText('PDF')).toBeTruthy();
    expect(within(firstCard).getByText('3')).toBeTruthy();
    expect(within(firstCard).getByText('English')).toBeTruthy();
    expect(within(firstCard).getByText('synthetic:doc-long')).toBeTruthy();
    expect(within(firstCard).getByText('chart:chart-001')).toBeTruthy();
    expect(within(firstCard).getByText('0°')).toBeTruthy();
    expect(within(firstCard).getByText(chartText('en', 'documentLive'))).toBeTruthy();
    expect(
      within(firstCard).getAllByRole('button').map((button) => button.textContent),
    ).toEqual([chartText('en', 'rotate'), chartText('en', 'void')]);

    // A voided document keeps its reason and its disabled void action.
    const voidedCard = cards.at(-1)!;
    expect(
      within(voidedCard).getByText(
        (content, element) =>
          element?.tagName === 'DD' && content.startsWith(chartText('en', 'documentVoided')),
      ),
    ).toBeTruthy();
    expect(
      (within(voidedCard).getByRole('button', { name: chartText('en', 'void') }) as HTMLButtonElement)
        .disabled,
    ).toBe(true);
    expectLogicalAndVisible(firstCard);
  });

  it('routes the card rotate and void actions to the owning document', () => {
    installMatchMedia(queriesFor('compact'));
    const onVoid = vi.fn();
    const onRotate = vi.fn();
    render(
      <ThemeProvider>
        <DocumentList
          documents={[documents[0]!]}
          locale="en"
          onVoid={onVoid}
          onRotate={onRotate}
        />
      </ThemeProvider>,
    );

    const card = screen.getByRole('listitem');
    fireEvent.click(within(card).getByRole('button', { name: chartText('en', 'rotate') }));
    fireEvent.click(within(card).getByRole('button', { name: chartText('en', 'void') }));
    expect(onRotate).toHaveBeenCalledWith('doc-001');
    expect(onVoid).toHaveBeenCalledWith('doc-001');
  });

  it('keeps the defined empty state as a card on compact', () => {
    installMatchMedia(queriesFor('compact'));
    renderList([]);

    expect(screen.queryByRole('table')).toBeNull();
    const list = screen.getByRole('list', { name: chartText('en', 'documentList') });
    expect(within(list).getAllByRole('listitem')).toHaveLength(1);
    expect(screen.getByText(chartText('en', 'noEntries'))).toBeTruthy();
  });

  it('keeps the full, unhidden table on medium tablets and expanded desktops', () => {
    for (const layout of ['medium', 'expanded'] as const) {
      installMatchMedia(queriesFor(layout));
      const { unmount } = renderList();

      expect(screen.queryByRole('list', { name: chartText('en', 'documentList') })).toBeNull();
      const table = screen.getByRole('table', { name: chartText('en', 'documentList') });
      const headers = within(table)
        .getAllByRole('columnheader')
        .map((node) => node.textContent);
      expect(headers).toEqual([
        chartText('en', 'documentTitle'),
        chartText('en', 'documentKind'),
        chartText('en', 'documentVersion'),
        chartText('en', 'documentLanguage'),
        chartText('en', 'documentChecksum'),
        chartText('en', 'documentLinkedTo'),
        chartText('en', 'documentRotation'),
        chartText('en', 'documentStatus'),
        chartText('en', 'actions'),
      ]);
      for (const cell of within(table).getAllByRole('cell')) {
        expect((cell as HTMLElement).style.display).not.toBe('none');
        expect((cell as HTMLElement).style.whiteSpace).toBe('normal');
      }
      unmount();
    }
  });

  it('preserves actions and focus order when the viewport crosses a breakpoint', () => {
    const media = installMatchMedia(queriesFor('compact'));
    renderList();
    const cards = screen.getAllByRole('listitem');
    expect(cards).toHaveLength(documents.length);
    expect(screen.queryByRole('table')).toBeNull();

    act(() => media.fire(BREAKPOINT_MEDIA_QUERIES.medium, true));
    expect(screen.queryByRole('list', { name: chartText('en', 'documentList') })).toBeNull();
    const table = screen.getByRole('table', { name: chartText('en', 'documentList') });
    // Keyboard order stays the DOM order: rotate then void for each document.
    const buttons = within(table).getAllByRole('button').map((button) => button.textContent);
    expect(buttons).toEqual([
      chartText('en', 'rotate'),
      chartText('en', 'void'),
      chartText('en', 'rotate'),
      chartText('en', 'void'),
      chartText('en', 'rotate'),
      chartText('en', 'void'),
      chartText('en', 'rotate'),
      chartText('en', 'void'),
    ]);
  });

  it('keeps Arabic titles isolated and logical styling under right-to-left', () => {
    document.documentElement.setAttribute('dir', 'rtl');
    document.documentElement.setAttribute('lang', 'ar');
    installMatchMedia(queriesFor('compact'));
    const arabicTitle: DocumentAssociation = {
      organizationId: 'SYN-ORG-02',
      id: 'doc-ar',
      chartLocalId: 'chart-001',
      kind: 'pdf',
      title: 'Synthetic Latin title',
      titleLocal: LONG_ARABIC,
      version: 2,
      language: 'ar',
      checksum: 'synthetic:doc-ar',
      linkedTo: 'chart:chart-001',
      rotationDegrees: 0,
    };
    renderList([arabicTitle, documents[0]!], 'ar');

    expect(document.documentElement.dir).toBe('rtl');
    expect(document.documentElement.lang).toBe('ar');
    expect(screen.queryByRole('table')).toBeNull();
    const list = screen.getByRole('list', {
      name: chartText('ar', 'documentList'),
    }) as HTMLElement;

    const localized = screen.getByRole('heading', { level: 4, name: LONG_ARABIC });
    const localizedRun = within(localized).getByText(LONG_ARABIC) as HTMLElement;
    expect(localizedRun.tagName).toBe('BDI');
    expect(localizedRun.getAttribute('dir')).toBe('auto');
    expect(localizedRun.getAttribute('lang')).toBe('ar');
    // A document without a local title still shows its stored Latin title.
    expect(screen.getByRole('heading', { level: 4, name: 'English PDF' })).toBeTruthy();

    const firstCard = within(list).getAllByRole('listitem')[0]!;
    expect(within(firstCard).getByText(chartText('ar', 'documentKind'))).toBeTruthy();
    expect(
      within(firstCard)
        .getAllByRole('button')
        .map((button) => button.textContent),
    ).toEqual([chartText('ar', 'rotate'), chartText('ar', 'void')]);
    expectLogicalAndVisible(list);
  });
});
