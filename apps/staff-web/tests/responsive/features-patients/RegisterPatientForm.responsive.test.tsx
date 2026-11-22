import { afterEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import type { Locale } from '../../../src/i18n/messages';
import { ThemeProvider } from '../../../src/theme/ThemeProvider';
import { BREAKPOINT_MEDIA_QUERIES } from '../../../src/theme/breakpoints';
import { EMPTY_REGISTRATION } from '../../../src/features/patients/domain';
import type {
  RegistrationDraft,
  RegistrationFieldErrors,
} from '../../../src/features/patients/domain';
import { RegisterPatientForm } from '../../../src/features/patients/RegisterPatientForm';

type ChangeListener = (event: MediaQueryListEvent) => void;

interface WidthCase {
  width: number;
  breakpoint: 'compact' | 'medium' | 'expanded';
  coarse: boolean;
}

/** Synthetic viewport cases mirroring the responsive Playwright matrix (WEB-07). */
const widthCases: readonly WidthCase[] = [
  { width: 320, breakpoint: 'compact', coarse: true },
  { width: 360, breakpoint: 'compact', coarse: true },
  { width: 390, breakpoint: 'compact', coarse: true },
  { width: 768, breakpoint: 'medium', coarse: true },
  { width: 820, breakpoint: 'medium', coarse: true },
  { width: 1024, breakpoint: 'medium', coarse: true },
  { width: 1280, breakpoint: 'expanded', coarse: false },
];

/** Physical inline styles are defects; every component style must be logical. */
const physicalStyleKeys: readonly string[] = [
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
];

/**
 * jsdom cannot evaluate media queries or paint pixels. This deterministic
 * stand-in answers the two layout queries and the coarse-pointer query from a
 * synthetic viewport width, so the real breakpoint hook resolves per case.
 */
function installMatchMedia(width: number, coarse: boolean): void {
  vi.stubGlobal(
    'matchMedia',
    vi.fn((query: string) => {
      let matches = false;
      if (query === BREAKPOINT_MEDIA_QUERIES.medium) {
        matches = width >= 768;
      } else if (query === BREAKPOINT_MEDIA_QUERIES.expanded) {
        matches = width >= 1280;
      } else if (query === '(pointer: coarse)') {
        matches = coarse;
      }
      return {
        get matches() {
          return matches;
        },
        media: query,
        onchange: null,
        addEventListener: (_type: string, listener: ChangeListener) => {
          void listener;
        },
        removeEventListener: (_type: string, listener: ChangeListener) => {
          void listener;
        },
        addListener: () => undefined,
        removeListener: () => undefined,
        dispatchEvent: () => false,
      } as unknown as MediaQueryList;
    }),
  );
}

interface RenderOptions {
  locale?: Locale;
  draft?: RegistrationDraft;
  errors?: RegistrationFieldErrors;
  rtl?: boolean;
}

/** Render the presentational form with synthetic data and no network access. */
function renderForm(options: RenderOptions = {}) {
  const {
    locale = 'en',
    draft = EMPTY_REGISTRATION,
    errors = {},
    rtl = false,
  } = options;
  const onFieldChange = vi.fn();
  const onSubmit = vi.fn();
  const onCancel = vi.fn();
  const view = render(
    <div dir={rtl ? 'rtl' : 'ltr'} lang={rtl ? 'ar' : 'en'}>
      <ThemeProvider>
        <RegisterPatientForm
          locale={locale}
          draft={draft}
          errors={errors}
          onFieldChange={onFieldChange}
          onSubmit={onSubmit}
          onCancel={onCancel}
        />
      </ThemeProvider>
    </div>,
  );
  return { ...view, onFieldChange, onSubmit, onCancel };
}

/**
 * Physical inline styles on the elements this component owns. Shared children
 * (FormField, Button) set a direction-neutral `border` shorthand that jsdom
 * expands into physical sides; they have their own responsive tests.
 */
function physicalStyleFindings(root: HTMLElement): string[] {
  const owned = [root, ...Array.from(root.querySelectorAll<HTMLElement>('[data-part]'))];
  const findings: string[] = [];
  for (const element of owned) {
    const style = element.style as unknown as Record<string, string>;
    for (const key of physicalStyleKeys) {
      if (style[key] !== '') {
        findings.push(`${key} on ${element.tagName}`);
      }
    }
  }
  return findings;
}

/** No field or control may be hidden, and no subtree may override direction. */
function hiddenOrDirectionFindings(root: HTMLElement): string[] {
  const findings: string[] = [];
  for (const element of Array.from(root.querySelectorAll<HTMLElement>('[style]'))) {
    const style = element.style as unknown as Record<string, string>;
    if (style['direction'] !== '') {
      findings.push(`direction on ${element.tagName}`);
    }
    if (style['display'] === 'none') {
      findings.push(`display:none on ${element.tagName}`);
    }
  }
  return findings;
}

const allErrors: RegistrationFieldErrors = {
  displayName: 'errorDisplayName',
  mrn: 'errorMrn',
  issuer: 'errorIssuer',
  dob: 'errorDob',
  dobUnknownReason: 'errorDobReason',
  language: 'errorLanguage',
};

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('responsive RegisterPatientForm (WEB-07, UX-01)', () => {
  it.each(widthCases)(
    'keeps every field and logical page layout at $width px ($breakpoint)',
    ({ width, breakpoint, coarse }) => {
      installMatchMedia(width, coarse);
      renderForm();

      const dialog = screen.getByRole('dialog', { name: 'Register patient' });
      expect(dialog.classList.contains('page')).toBe(true);
      expect(dialog.getAttribute('data-breakpoint')).toBe(breakpoint);
      expect(dialog.getAttribute('data-coarse-pointer')).toBe(String(coarse));

      const form = within(dialog).getByRole('form', { name: 'Register patient' });
      expect(form.getAttribute('data-breakpoint')).toBe(breakpoint);

      // Long forms group into labelled sections that stack on phones and use
      // the shared two-column grid from medium upward.
      const sections = dialog.querySelectorAll('[data-part="section"]');
      expect(sections).toHaveLength(3);
      for (const section of Array.from(sections)) {
        expect(section.getAttribute('role')).toBe('group');
        expect(section.getAttribute('aria-label')).toBeTruthy();
        expect(section.classList.contains('grid-2')).toBe(true);
        expect(section.getAttribute('data-layout')).toBe(
          breakpoint === 'compact' ? 'single-column' : 'two-column',
        );
      }

      // No clinical field is hidden or removed to achieve the layout, and all
      // controls keep the shared 16px / 44px responsive classes.
      expect(dialog.querySelectorAll('input')).toHaveLength(6);
      for (const input of Array.from(dialog.querySelectorAll('input'))) {
        expect(input.classList.contains('field')).toBe(true);
        expect(input.classList.contains('touch')).toBe(true);
      }
      for (const label of [
        'Display name',
        'MRN',
        'MRN issuer',
        'Date of birth (YYYY-MM-DD)',
        'Unknown-date reason (code)',
        'Language code',
      ]) {
        expect(screen.getByLabelText(label)).toBeTruthy();
      }
      for (const button of ['Save registration', 'Cancel']) {
        expect(screen.getByRole('button', { name: button }).classList.contains('touch')).toBe(true);
      }

      expect(physicalStyleFindings(dialog)).toEqual([]);
      expect(hiddenOrDirectionFindings(dialog)).toEqual([]);
      // The stylesheet also hides by class; none of those classes may be used
      // to remove a clinical field at any breakpoint.
      for (const element of Array.from(dialog.querySelectorAll<HTMLElement>('[class]'))) {
        expect(element.classList.contains('hide-compact')).toBe(false);
        expect(element.classList.contains('only-compact')).toBe(false);
      }
      expect(dialog.querySelector('[data-part="warning"]')).toBeTruthy();
    },
  );

  it.each([
    {
      direction: 'LTR',
      locale: 'en' as const,
      rtl: false,
      dialogName: 'Register patient',
      summaryName: 'Fix the following fields',
      submitName: 'Save registration',
      cancelName: 'Cancel',
    },
    {
      direction: 'RTL',
      locale: 'ar' as const,
      rtl: true,
      dialogName: 'تسجيل مريض',
      summaryName: 'صحّح الحقول التالية',
      submitName: 'حفظ التسجيل',
      cancelName: 'إلغاء',
    },
  ])(
    'keeps the warning above the submit bar and the $direction keyboard order stable',
    ({ locale, rtl, dialogName, summaryName, submitName, cancelName }) => {
      installMatchMedia(390, true);
      renderForm({ locale, rtl, errors: allErrors });

      const dialog = screen.getByRole('dialog', { name: dialogName });
      const warning = dialog.querySelector('[data-part="warning"]') as HTMLElement;
      expect(warning).toBeTruthy();
      const submit = screen.getByRole('button', { name: submitName });
      expect(warning.compareDocumentPosition(submit) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();

      const summary = within(warning).getByRole('alert', { name: summaryName });
      const links = Array.from(summary.querySelectorAll('a'));
      expect(links).toHaveLength(6);
      const fieldIds = [
        'patient-displayName',
        'patient-mrn',
        'patient-issuer',
        'patient-dob',
        'patient-dobUnknownReason',
        'patient-language',
      ];
      for (const id of fieldIds) {
        expect(summary.querySelector(`a[href="#${id}"]`)).toBeTruthy();
        expect(
          screen.getByLabelText(
            (document.getElementById(id) as HTMLInputElement).labels?.[0]?.textContent ?? '',
          ).getAttribute('aria-invalid'),
        ).toBe('true');
      }
      // The jump list must follow the rendered field and tab order; grouping
      // into sections must not reorder a field relative to the summary.
      expect(links.map((link) => link.getAttribute('href')?.slice(1))).toEqual(fieldIds);
      expect(
        Array.from(dialog.querySelectorAll<HTMLInputElement>('input')).map((input) => input.id),
      ).toEqual(fieldIds);

      const focusables = Array.from(
        dialog.querySelectorAll<HTMLElement>('a[href^="#"], input, button'),
      );
      expect(focusables.slice(0, 6)).toEqual(links);
      expect(focusables.slice(6, 12).map((element) => element.tagName)).toEqual(
        Array(6).fill('INPUT'),
      );
      expect(focusables.at(-2)).toBe(submit);
      expect(focusables.at(-1)).toBe(screen.getByRole('button', { name: cancelName }));
    },
  );

  it('wraps a long English draft without truncation at the narrowest width', () => {
    installMatchMedia(320, true);
    const longName =
      'Synthetic Extremely Long Display Name For A Narrow Clinical Registration Form Field';
    renderForm({ draft: { ...EMPTY_REGISTRATION, displayName: longName } });

    const input = screen.getByLabelText('Display name') as HTMLInputElement;
    expect(input.value).toBe(longName);
    expect(input.classList.contains('field')).toBe(true);
    expect(input.style.textOverflow).toBe('');
    expect(input.style.whiteSpace).toBe('');
    const dialog = screen.getByRole('dialog');
    expect(physicalStyleFindings(dialog)).toEqual([]);
    expect(hiddenOrDirectionFindings(dialog)).toEqual([]);
  });

  it('mirrors under RTL at phone and desktop widths without a direction override', () => {
    for (const widthCase of [widthCases[0]!, widthCases[6]!]) {
      installMatchMedia(widthCase.width, widthCase.coarse);
      const { unmount } = renderForm({ locale: 'ar', rtl: true });

      const dialog = screen.getByRole('dialog', { name: 'تسجيل مريض' });
      expect(dialog.getAttribute('data-breakpoint')).toBe(widthCase.breakpoint);
      for (const section of Array.from(dialog.querySelectorAll('[data-part="section"]'))) {
        expect(section.getAttribute('data-layout')).toBe(
          widthCase.breakpoint === 'compact' ? 'single-column' : 'two-column',
        );
      }
      for (const label of [
        'الاسم المعروض',
        'رقم الملف',
        'جهة إصدار رقم الملف',
        'تاريخ الميلاد (YYYY-MM-DD)',
        'سبب تاريخ الميلاد غير المعروف (رمز)',
        'رمز اللغة',
      ]) {
        expect(screen.getByLabelText(label)).toBeTruthy();
      }
      expect(screen.getByRole('button', { name: 'حفظ التسجيل' })).toBeTruthy();
      expect(screen.getByRole('button', { name: 'إلغاء' })).toBeTruthy();
      expect(physicalStyleFindings(dialog)).toEqual([]);
      expect(hiddenOrDirectionFindings(dialog)).toEqual([]);
      expect(dialog.querySelectorAll('input')).toHaveLength(6);
      unmount();
    }
  });

  it('preserves field edits, submit and cancel through the normal DOM events', () => {
    installMatchMedia(320, true);
    const { onFieldChange, onSubmit, onCancel } = renderForm();

    fireEvent.change(screen.getByLabelText('Display name'), { target: { value: 'Synthetic Name' } });
    expect(onFieldChange).toHaveBeenCalledWith('displayName', 'Synthetic Name');

    fireEvent.submit(screen.getByRole('form', { name: 'Register patient' }));
    expect(onSubmit).toHaveBeenCalledTimes(1);

    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(onCancel).toHaveBeenCalledTimes(1);
  });
});
