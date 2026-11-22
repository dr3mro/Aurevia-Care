import type { ReactElement } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { ActiveOrganizationProvider } from '../../../src/api/activeOrganization';
import { send } from '../../../src/api/transport';
import type * as TransportModule from '../../../src/api/transport';
import { alphaOrganizationId } from '../../../src/features/admin/fixtures';
import type { StaffPageResponse } from '../../../src/features/admin/api';
import { StaffPage } from '../../../src/features/admin/StaffPage';
import { I18nProvider } from '../../../src/i18n/I18nProvider';
import type { Locale } from '../../../src/i18n/messages';
import { BREAKPOINT_MEDIA_QUERIES } from '../../../src/theme/breakpoints';
import { ThemeProvider } from '../../../src/theme/ThemeProvider';

vi.mock('../../../src/api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);

/** Synthetic long values: long English and Arabic runs must wrap, not clip. */
const LONG_ENGLISH = 'A deliberately long synthetic display name that must wrap on a small phone';
const LONG_ARABIC = 'اسم عرض تجريبي طويل جداً يجب أن يلتف على شاشة الهاتف';

type Band = 'compact' | 'medium' | 'expanded';

/**
 * Install a deterministic matchMedia stand-in keyed by media query so the
 * breakpoint hook can be exercised in jsdom, which cannot evaluate real media
 * queries. The pointer query stays false; coarse-pointer sizing is owned by the
 * shared stylesheet and proven in the Playwright projects.
 */
function installMatchMedia(band: Band): void {
  const answers: Record<string, boolean> = {
    [BREAKPOINT_MEDIA_QUERIES.medium]: band !== 'compact',
    [BREAKPOINT_MEDIA_QUERIES.expanded]: band === 'expanded',
  };
  vi.stubGlobal(
    'matchMedia',
    vi.fn((query: string) => ({
      matches: answers[query] ?? false,
      media: query,
      onchange: null,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
      addListener: () => undefined,
      removeListener: () => undefined,
      dispatchEvent: () => false,
    })),
  );
}

function member(displayName: string): StaffPageResponse['items'][number] {
  return {
    subject: 'synthetic.reception',
    displayName,
    status: 'active',
    roles: ['reception', 'privacy_auditor'],
  };
}

function renderPage(locale: Locale = 'en') {
  const element: ReactElement = (
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <ActiveOrganizationProvider value={{ organizationId: alphaOrganizationId }}>
          <StaffPage />
        </ActiveOrganizationProvider>
      </I18nProvider>
    </ThemeProvider>
  );
  return render(element);
}

/** The page root is the element that carries the shared responsive grid. */
function pageRoot(): HTMLElement {
  return document.querySelector('[data-part="staff-page"]') as HTMLElement;
}

/** The invite form's field region, whose layout class changes at medium. */
function inviteFields(): HTMLElement {
  return document.querySelector('[data-part="invite-fields"]') as HTMLElement;
}

/** The compact-only labelled scroll region; the page region shares the name. */
function tableRegion(): HTMLElement | null {
  return document.querySelector('.table-region') as HTMLElement | null;
}

const ENGLISH_HEADERS = ['Staff subject', 'Display name', 'Status', 'Roles', 'Actions'];
const ARABIC_HEADERS = ['معرّف الموظف', 'الاسم المعروض', 'الحالة', 'الأدوار', 'الإجراءات'];

beforeEach(() => {
  sendMock.mockReset();
  sendMock.mockResolvedValue({ items: [member(LONG_ENGLISH)], nextCursor: null });
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('responsive StaffPage (WEB-07, UX-01)', () => {
  it('stacks the page and fields and labels the scroll region on a compact phone', async () => {
    installMatchMedia('compact');
    renderPage();
    await screen.findByText(LONG_ENGLISH);

    const page = pageRoot();
    expect(page.classList.contains('page')).toBe(true);
    expect(page.getAttribute('data-breakpoint')).toBe('compact');

    const invite = screen.getByRole('region', { name: 'Invite staff' }) as HTMLElement;
    expect(invite.classList.contains('stack')).toBe(true);
    expect(invite.getAttribute('data-part')).toBe('invite');

    const fields = inviteFields();
    expect(fields.className).toBe('stack');
    expect(fields.getAttribute('data-layout')).toBe('single-column');

    const region = tableRegion() as HTMLElement;
    expect(region.getAttribute('role')).toBe('region');
    expect(region.getAttribute('aria-label')).toBe('Staff administration');
    expect(region.classList.contains('table-region')).toBe(true);
    expect(region.tabIndex).toBe(0);
    expect(region.getAttribute('data-breakpoint')).toBe('compact');

    const table = within(region).getByRole('table', { name: 'Staff administration' });
    expect(within(table).getAllByRole('columnheader').map((node) => node.textContent)).toEqual(
      ENGLISH_HEADERS,
    );
    expect((within(table).getByRole('cell', { name: LONG_ENGLISH }) as HTMLElement).style.overflowWrap).toBe(
      'anywhere',
    );
  });

  it('uses two invite columns and drops the compact scroll region on medium tablets', async () => {
    installMatchMedia('medium');
    renderPage();
    await screen.findByText(LONG_ENGLISH);

    expect(pageRoot().getAttribute('data-breakpoint')).toBe('medium');
    const fields = inviteFields();
    expect(fields.className).toBe('grid-2');
    expect(fields.getAttribute('data-layout')).toBe('two-column');

    expect(tableRegion()).toBeNull();
    const table = screen.getByRole('table', { name: 'Staff administration' });
    expect((table as HTMLElement).getAttribute('data-breakpoint')).toBe('medium');
    expect(within(table).getAllByRole('columnheader').map((node) => node.textContent)).toEqual(
      ENGLISH_HEADERS,
    );
    for (const header of within(table).getAllByRole('columnheader')) {
      expect(header.getAttribute('scope')).toBe('col');
    }
  });

  it('keeps the full table and two-column fields on expanded desktops', async () => {
    installMatchMedia('expanded');
    renderPage();
    await screen.findByText(LONG_ENGLISH);

    expect(pageRoot().getAttribute('data-breakpoint')).toBe('expanded');
    expect(inviteFields().className).toBe('grid-2');
    expect(tableRegion()).toBeNull();
    const table = screen.getByRole('table', { name: 'Staff administration' });
    expect((table as HTMLElement).getAttribute('data-breakpoint')).toBe('expanded');
    expect(within(table).getAllByRole('columnheader')).toHaveLength(5);
    for (const cell of within(table).getAllByRole('cell')) {
      expect((cell as HTMLElement).style.display).not.toBe('none');
    }
  });

  it('keeps every clinical column and wraps long values at every band', async () => {
    for (const band of ['compact', 'medium', 'expanded'] as const) {
      installMatchMedia(band);
      const view = renderPage();
      await screen.findByText(LONG_ENGLISH);

      const table = screen.getByRole('table', { name: 'Staff administration' });
      expect(within(table).getAllByRole('columnheader').map((node) => node.textContent)).toEqual(
        ENGLISH_HEADERS,
      );
      const longCell = within(table).getByRole('cell', { name: LONG_ENGLISH }) as HTMLElement;
      expect(longCell.style.overflowWrap).toBe('anywhere');
      expect(longCell.style.whiteSpace).toBe('normal');
      expect(longCell.firstElementChild?.getAttribute('dir')).toBe('auto');
      view.unmount();
    }
  });

  it('keeps a logical keyboard order and coarse-pointer-ready controls', async () => {
    installMatchMedia('compact');
    renderPage();
    await screen.findByText(LONG_ENGLISH);

    const form = screen.getByRole('form', { name: 'Invite staff' });
    const focusables = [...form.querySelectorAll('input, button')] as HTMLElement[];
    expect(focusables.map((element) => element.tagName)).toEqual(['INPUT', 'INPUT', 'INPUT', 'BUTTON']);
    for (const element of focusables) {
      expect(element.className).toContain('touch');
    }
    expect((focusables.at(-1) as HTMLButtonElement).type).toBe('submit');

    fireEvent.click(screen.getByRole('button', { name: 'Deactivate' }));
    const dialog = screen.getByRole('dialog', { name: `Deactivate ${LONG_ENGLISH}?` });
    expect(dialog.getAttribute('data-breakpoint')).toBe('compact');
    const actions = dialog.querySelector('[data-part="dialog-actions"]') as HTMLElement;
    expect(actions.classList.contains('cluster')).toBe(true);
    const buttons = within(dialog).getAllByRole('button');
    expect(buttons.map((button) => button.textContent)).toEqual(['Cancel', 'Deactivate']);
    expect(document.activeElement).toBe(buttons[0]);
  });

  it('mirrors only the flow with logical styles and full columns under right-to-left', async () => {
    installMatchMedia('compact');
    sendMock.mockResolvedValue({ items: [member(LONG_ARABIC)], nextCursor: null });
    renderPage('ar');
    await screen.findByText(LONG_ARABIC);
    expect(document.documentElement.dir).toBe('rtl');
    expect(document.documentElement.lang).toBe('ar');

    const region = tableRegion() as HTMLElement;
    expect(region.getAttribute('role')).toBe('region');
    expect(region.getAttribute('aria-label')).toBe('إدارة الموظفين');
    expect(region.classList.contains('table-region')).toBe(true);
    const table = within(region).getByRole('table', { name: 'إدارة الموظفين' });
    expect(within(table).getAllByRole('columnheader').map((node) => node.textContent)).toEqual(
      ARABIC_HEADERS,
    );
    expect(
      (within(table).getByRole('cell', { name: LONG_ARABIC }) as HTMLElement).style.overflowWrap,
    ).toBe('anywhere');

    // No component sets a physical side, physical text alignment, an inline
    // direction override, or hides a field to achieve the layout. An isolated
    // <bdi dir="auto"> is an attribute, not a CSS direction override.
    const page = pageRoot();
    for (const element of [page, ...page.querySelectorAll('*')] as HTMLElement[]) {
      const style = element.getAttribute('style') ?? '';
      expect(style).not.toMatch(/(?:margin|padding|border)-(?:left|right)\s*:/);
      expect(style).not.toMatch(/text-align\s*:\s*(?:left|right)/);
      expect(element.style.direction).toBe('');
      expect(style).not.toContain('display: none');
    }
    expect(inviteFields().className).toBe('stack');
  });
});
