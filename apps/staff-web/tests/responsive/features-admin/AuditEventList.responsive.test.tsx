import { act, fireEvent, render, screen, within } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AuditEventList } from '../../../src/features/admin/AuditEventList';
import type { AuditEvent } from '../../../src/features/admin/domain';
import { adminText } from '../../../src/features/admin/messages';
import type { AdminMessageKey } from '../../../src/features/admin/messages';
import { I18nProvider } from '../../../src/i18n/I18nProvider';
import type { Locale } from '../../../src/i18n/messages';
import { BREAKPOINT_MEDIA_QUERIES } from '../../../src/theme/breakpoints';
import { ThemeProvider } from '../../../src/theme/ThemeProvider';
import { must } from '../../must';

type ChangeListener = (event: MediaQueryListEvent) => void;

interface QueryState {
  matches: boolean;
  listeners: Set<ChangeListener>;
}

/**
 * Deterministic matchMedia stand-in keyed by media query string. One state
 * object is reused per query so the hook's initializer and effect see the same
 * listeners. All data below is synthetic.
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
    vi.fn((query: string) => ({
      get matches() {
        return state(query).matches;
      },
      addEventListener: (_type: string, listener: ChangeListener) => {
        state(query).listeners.add(listener);
      },
      removeEventListener: (_type: string, listener: ChangeListener) => {
        state(query).listeners.delete(listener);
      },
    })),
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

const ORGANIZATION_ID = 'synthetic-org-alpha';
const LONG_ENGLISH =
  'A deliberately long synthetic audit actor name that must wrap at 320 px and at 200 percent zoom without clipping or overlap';
const LONG_ARABIC = 'اسم فاعل تدقيق تجريبي طويل جداً يجب أن يلتف دون قص أو تداخل عند العرض الضيق';

/** Synthetic audit chain; the second event has no instant to exercise "unknown". */
function syntheticEvents(actor: string, purpose: string): readonly AuditEvent[] {
  return [
    {
      organizationId: ORGANIZATION_ID,
      sequence: 1,
      actor,
      purpose,
      scope: 'organization:alpha',
      correlationId: 'synthetic-correlation-one',
      action: 'CONFIG_CREATE',
      resourceId: 'synthetic-resource-one',
      outcome: 'SUCCESS',
      occurredAt: '2026-03-08T06:30:00Z',
      previousHash: '',
      hash: '',
    },
    {
      organizationId: ORGANIZATION_ID,
      sequence: 2,
      actor: 'Synthetic actor two',
      purpose: 'Synthetic purpose two',
      scope: 'facility:MAIN',
      correlationId: 'synthetic-correlation-two',
      action: 'STAFF_DEACTIVATE',
      resourceId: 'synthetic-resource-two',
      outcome: 'DENIED',
      occurredAt: '',
      previousHash: '',
      hash: '',
    },
  ];
}

const FIELD_LABEL_KEYS = [
  'sequence',
  'actor',
  'purpose',
  'scope',
  'correlationId',
  'outcome',
  'action',
  'resource',
  'occurredAt',
] as const satisfies readonly AdminMessageKey[];

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

function wrap(ui: ReactElement, locale: Locale = 'en') {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>{ui}</I18nProvider>
    </ThemeProvider>,
  );
}

function list(locale: Locale) {
  const node = screen.getByRole('list', { name: adminText(locale, 'auditEvents') });
  expect(node.className).toContain('card-list');
  return node;
}

/** The labelled table region is the sole element with an explicit region role. */
function tableRegion(locale: Locale): HTMLElement {
  const node = document.querySelector<HTMLElement>('[role="region"]');
  expect(node).toBeTruthy();
  expect(node?.getAttribute('aria-label')).toBe(adminText(locale, 'auditEvents'));
  return must(node);
}

afterEach(() => {
  vi.unstubAllGlobals();
  document.documentElement.removeAttribute('dir');
  document.documentElement.removeAttribute('lang');
});

describe('responsive AuditEventList (WEB-07, UX-01)', () => {
  it('renders event cards with every field, stacked, on compact phones', () => {
    installMatchMedia(queriesFor('compact'));
    wrap(
      <AuditEventList
        events={syntheticEvents(LONG_ENGLISH, 'Synthetic purpose one')}
        organizationId={ORGANIZATION_ID}
        showChain={false}
      />,
    );

    expect(screen.queryByRole('table')).toBeNull();
    const cards = list('en');
    const items = within(cards).getAllByRole('listitem');
    expect(items).toHaveLength(2);

    for (let index = 0; index < items.length; index += 1) {
      const item = must(items[index]);
      const card = within(item);
      // No clinical field is hidden: each label has a value on the card.
      for (const key of FIELD_LABEL_KEYS) {
        const label = card.getByText(adminText('en', key));
        expect(label.tagName).toBe('DT');
        const definition = label.nextElementSibling as HTMLElement;
        expect(definition.tagName).toBe('DD');
        expect(definition.style.display).not.toBe('none');
        // Long values wrap and have no fixed block size, so text at 200% zoom
        // stays readable and nothing is clipped.
        expect(definition.style.overflowWrap).toBe('anywhere');
        expect(definition.style.whiteSpace).toBe('normal');
      }
      expect(
        card.getByText(index === 0 ? 'synthetic-correlation-one' : 'synthetic-correlation-two'),
      ).toBeTruthy();
      expect(card.getByText(index === 0 ? 'CONFIG_CREATE' : 'STAFF_DEACTIVATE')).toBeTruthy();
      expect(
        card.getByText(index === 0 ? 'synthetic-resource-one' : 'synthetic-resource-two'),
      ).toBeTruthy();
      expect(
        card.getByText(
          index === 0 ? adminText('en', 'outcomeSuccess') : adminText('en', 'outcomeDenied'),
        ),
      ).toBeTruthy();
      expect(card.getByText(index === 0 ? LONG_ENGLISH : 'Synthetic actor two')).toBeTruthy();
    }
    expect(within(must(items[1])).getByText(adminText('en', 'unknown'))).toBeTruthy();
  });

  it('keeps only the time elements as tab stops in keyboard order on compact', () => {
    installMatchMedia(queriesFor('compact'));
    const { container } = wrap(
      <AuditEventList
        events={syntheticEvents('Synthetic actor one', 'Synthetic purpose one')}
        organizationId={ORGANIZATION_ID}
        showChain={false}
      />,
    );

    const stops = Array.from(container.querySelectorAll<HTMLElement>('[tabindex]'));
    expect(stops.map((node) => node.tagName)).toEqual(['TIME', 'TIME']);
    expect(stops.every((node) => node.tabIndex === 0)).toBe(true);
    expect(stops[0]?.getAttribute('datetime')).toBe('2026-03-08T06:30:00Z');
    expect(stops[1]?.getAttribute('datetime')).toBeNull();
  });

  it('shows the empty state inside the compact card list without hiding data', () => {
    installMatchMedia(queriesFor('compact'));
    wrap(<AuditEventList events={[]} organizationId={ORGANIZATION_ID} showChain={false} />);

    const cards = list('en');
    expect(within(cards).getByText(adminText('en', 'noEvents'))).toBeTruthy();
    expect(within(cards).queryAllByRole('listitem')).toHaveLength(1);
    expect(screen.queryByRole('table')).toBeNull();
  });

  it('reveals the UTC instant on focus inside a compact card', () => {
    installMatchMedia(queriesFor('compact'));
    wrap(
      <AuditEventList
        events={syntheticEvents('Synthetic actor one', 'Synthetic purpose one')}
        organizationId={ORGANIZATION_ID}
        showChain={false}
      />,
    );

    const card = within(must(within(list('en')).getAllByRole('listitem')[0]));
    const time = card.getByRole('time');
    fireEvent.focus(time);
    expect(card.getByText(`UTC: ${time.textContent ?? ''}`)).toBeTruthy();
    expect(time.getAttribute('aria-describedby')).toBe('audit-utc-1');
    fireEvent.blur(time);
    expect(card.queryByText(`UTC: ${time.textContent ?? ''}`)).toBeNull();
  });

  it.each(['medium', 'expanded'] as const)('keeps the full, unhidden table on %s', (layout) => {
    installMatchMedia(queriesFor(layout));
    const { container } = wrap(
      <AuditEventList
        events={syntheticEvents(LONG_ENGLISH, 'Synthetic purpose one')}
        organizationId={ORGANIZATION_ID}
        showChain={false}
      />,
    );

    expect(screen.queryByRole('list')).toBeNull();
    const region = tableRegion('en');
    const table = within(region).getByRole('table', { name: adminText('en', 'auditEvents') });
    expect(
      within(table)
        .getAllByRole('columnheader')
        .map((node) => node.textContent),
    ).toEqual(FIELD_LABEL_KEYS.map((key) => adminText('en', key)));
    for (const cell of within(table).getAllByRole('cell')) {
      expect(cell.style.display).not.toBe('none');
    }
    expect(within(table).getByText(LONG_ENGLISH)).toBeTruthy();

    const stops = Array.from(container.querySelectorAll<HTMLElement>('[tabindex]'));
    expect(stops[0]).toBe(region);
    expect(stops.slice(1).map((node) => node.tagName)).toEqual(['TIME', 'TIME']);
  });

  it('reflows from cards to a table as the viewport grows without losing fields', () => {
    const media = installMatchMedia(queriesFor('compact'));
    wrap(
      <AuditEventList
        events={syntheticEvents('Synthetic actor one', 'Synthetic purpose one')}
        organizationId={ORGANIZATION_ID}
        showChain={false}
      />,
    );
    expect(screen.getByRole('list')).toBeTruthy();
    const cards = screen.getByRole('list');
    expect(within(cards).getByText('synthetic-correlation-one')).toBeTruthy();

    act(() => media.fire(BREAKPOINT_MEDIA_QUERIES.medium, true));
    expect(screen.queryByRole('list')).toBeNull();
    const region = tableRegion('en');
    expect(within(region).getByText('synthetic-correlation-one')).toBeTruthy();

    act(() => media.fire(BREAKPOINT_MEDIA_QUERIES.expanded, true));
    expect(screen.queryByRole('list')).toBeNull();
    expect(tableRegion('en')).toBe(region);
  });

  it('keeps Arabic values, logical styling and labelled cards under right-to-left', () => {
    document.documentElement.setAttribute('dir', 'rtl');
    document.documentElement.setAttribute('lang', 'ar');
    installMatchMedia(queriesFor('compact'));
    wrap(
      <AuditEventList
        events={syntheticEvents(LONG_ARABIC, 'الغرض التجريبي')}
        organizationId={ORGANIZATION_ID}
        showChain={false}
      />,
      'ar',
    );

    expect(document.documentElement.dir).toBe('rtl');
    expect(document.documentElement.lang).toBe('ar');
    const cards = list('ar');
    const items = within(cards).getAllByRole('listitem');
    expect(items).toHaveLength(2);

    const arabicCard = within(must(items[0]));
    for (const key of FIELD_LABEL_KEYS) {
      expect(arabicCard.getByText(adminText('ar', key))).toBeTruthy();
    }
    const arabicValue = arabicCard.getByText(LONG_ARABIC);
    expect(arabicValue.tagName).toBe('BDI');
    expect(arabicValue.getAttribute('dir')).toBe('auto');
    // Pre-existing deviation (not introduced by the responsive change): the
    // MixedScriptText call sites pass lang="en" even for an Arabic value, so
    // isolation comes from dir="auto". This story must not change behaviour or
    // text; the correct-language hint is requested as a follow-up task.
    expect(arabicValue.getAttribute('lang')).toBe('en');
    // Localized badge labels do carry the active Arabic language.
    const outcomeValue = arabicCard.getByText(adminText('ar', 'outcomeSuccess'));
    expect(outcomeValue.tagName).toBe('BDI');
    expect(outcomeValue.getAttribute('lang')).toBe('ar');

    // Direction comes from the document; no component sets a physical side, a
    // physical alignment, a direction override or a hidden clinical value.
    for (const element of [cards, ...Array.from(cards.querySelectorAll('*'))] as HTMLElement[]) {
      const style = element.getAttribute('style') ?? '';
      expect(style).not.toMatch(/(?:margin|padding|border|inset)-(?:left|right)\s*:/);
      expect(style).not.toMatch(/text-align\s*:\s*(?:left|right)/);
      expect(style).not.toMatch(/(?:^|;)\s*(?:width|height|overflow-[xy])\s*:/);
      expect(style).not.toContain('display: none');
      expect(element.style.direction).toBe('');
    }
  });
});
