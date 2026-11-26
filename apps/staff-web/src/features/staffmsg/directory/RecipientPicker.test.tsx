import { readFileSync, readdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import type { ReactElement } from 'react';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { I18nProvider } from '../../../i18n/I18nProvider';
import type { Locale } from '../../../i18n/messages';
import { ThemeProvider } from '../../../theme/ThemeProvider';
import * as directory from './index';
import { DirectoryPage } from './DirectoryPage';
import {
  CROSS_FACILITY_MESSAGING_DISABLED,
  CROSS_ORGANIZATION_MESSAGING_DISABLED,
} from './domain';
import {
  crossOrgContact,
  directoryContacts,
  latinOnlyContact,
  mainActor,
  otherFacilityContact,
  policyClosed,
  policyOpen,
  sameFacilityContact,
} from './fixtures';
import { directoryText } from './labels';
import type { RecipientPickerProps } from './RecipientPicker';
import { RecipientPicker } from './RecipientPicker';

/** Narrow an optional DOM lookup without a non-null assertion. */
function required<T>(value: T | null | undefined): T {
  if (value === null || value === undefined) {
    throw new Error('Expected a rendered element');
  }
  return value;
}

/** Mount any component under the providers its theme and i18n hooks require. */
function wrap(ui: ReactElement, locale: Locale = 'en') {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>{ui}</I18nProvider>
    </ThemeProvider>,
  );
}

/** Build the provider tree so both initial render and rerender share one locale. */
function pickerTree(props: RecipientPickerProps, locale: Locale): ReactElement {
  return (
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <RecipientPicker {...props} />
      </I18nProvider>
    </ThemeProvider>
  );
}

/** Render the picker with synthetic defaults and return typed controls. */
function renderPicker(locale: Locale = 'en', overrides: Partial<RecipientPickerProps> = {}) {
  const onSelect = vi.fn<RecipientPickerProps['onSelect']>();
  const props: RecipientPickerProps = {
    contacts: directoryContacts,
    actor: mainActor,
    policy: policyOpen,
    selectedSubject: undefined,
    onSelect,
    ...overrides,
  };
  const view = render(pickerTree(props, locale));
  return {
    onSelect,
    rerender: (next: RecipientPickerProps) => view.rerender(pickerTree(next, locale)),
  };
}

/** All rendered option rows, scoped to the listbox. */
function rows(): HTMLElement[] {
  return within(screen.getByRole('listbox')).getAllByRole('option');
}

/** Look up one synthetic option by its stable subject id. */
function rowFor(subject: string): HTMLElement {
  return required(document.getElementById(`recipient-option-${subject}`));
}

/** The focusable combobox that owns keyboard and active-descendant semantics. */
function combobox(): HTMLInputElement {
  return screen.getByRole<HTMLInputElement>('combobox');
}

describe('RecipientPicker', () => {
  it('labels every row with a facility and shows the marker and refusal only where they apply', () => {
    const { onSelect } = renderPicker('en', { selectedSubject: sameFacilityContact.subject });

    expect(rows()).toHaveLength(4);
    for (const contact of directoryContacts) {
      expect(rowFor(contact.subject).textContent).toContain(directoryText('en', 'facilityLabel'));
      expect(rowFor(contact.subject).textContent).toContain(contact.facilityCode);
    }
    const marked = rows().filter((row) =>
      row.textContent.includes(directoryText('en', 'otherFacility')),
    );
    expect(marked).toHaveLength(1);
    expect(marked[0]).toBe(rowFor(otherFacilityContact.subject));

    const blocked = rowFor(crossOrgContact.subject);
    expect(blocked.textContent).toContain(directoryText('en', 'blockedOrgReason'));
    expect(blocked.textContent).toContain(CROSS_ORGANIZATION_MESSAGING_DISABLED);
    expect(blocked.getAttribute('aria-disabled')).toBe('true');
    expect(rowFor(sameFacilityContact.subject).getAttribute('aria-disabled')).toBe('false');
    expect(rowFor(sameFacilityContact.subject).getAttribute('aria-selected')).toBe('true');
    expect(rowFor(otherFacilityContact.subject).getAttribute('aria-selected')).toBe('false');
    expect(combobox().getAttribute('aria-expanded')).toBe('true');
    expect(combobox().getAttribute('aria-controls')).toBe('staff-directory-results');
    expect(rowFor(crossOrgContact.subject).style.cursor).toBe('not-allowed');
    expect(rowFor(sameFacilityContact.subject).style.cursor).toBe('pointer');
    expect(within(rowFor(sameFacilityContact.subject)).queryByRole('alert')).toBeNull();

    fireEvent.click(rowFor(sameFacilityContact.subject));
    expect(onSelect).toHaveBeenCalledExactlyOnceWith(sameFacilityContact.subject);
  });

  it('shows the empty state and collapses the listbox when nothing matches', () => {
    renderPicker();
    fireEvent.change(combobox(), { target: { value: 'zzzz-no-match' } });
    expect(screen.getByRole('status').textContent).toContain(directoryText('en', 'empty'));
    expect(screen.queryByRole('listbox')).toBeNull();
    expect(combobox().getAttribute('aria-expanded')).toBe('false');
    expect(combobox().hasAttribute('aria-controls')).toBe(false);
    expect(combobox().hasAttribute('aria-activedescendant')).toBe(false);
  });

  it('clears the query with Escape and restores every contact', () => {
    renderPicker();
    const input = combobox();
    fireEvent.change(input, { target: { value: 'zzzz-no-match' } });
    expect(screen.queryByRole('listbox')).toBeNull();
    fireEvent.keyDown(input, { key: 'Escape' });
    expect(input.value).toBe('');
    expect(rows()).toHaveLength(4);
  });

  it('advances and wraps with both arrow keys while tracking the active descendant', () => {
    renderPicker();
    const input = combobox();
    expect(input.getAttribute('aria-activedescendant')).toBe(
      `recipient-option-${sameFacilityContact.subject}`,
    );

    fireEvent.keyDown(input, { key: 'ArrowDown' });
    expect(input.getAttribute('aria-activedescendant')).toBe(
      `recipient-option-${otherFacilityContact.subject}`,
    );
    fireEvent.keyDown(input, { key: 'ArrowDown' });
    expect(input.getAttribute('aria-activedescendant')).toBe(
      `recipient-option-${crossOrgContact.subject}`,
    );
    fireEvent.keyDown(input, { key: 'ArrowDown' });
    expect(input.getAttribute('aria-activedescendant')).toBe(
      `recipient-option-${latinOnlyContact.subject}`,
    );
    fireEvent.keyDown(input, { key: 'ArrowDown' });
    expect(input.getAttribute('aria-activedescendant')).toBe(
      `recipient-option-${sameFacilityContact.subject}`,
    );
    fireEvent.keyDown(input, { key: 'ArrowUp' });
    expect(input.getAttribute('aria-activedescendant')).toBe(
      `recipient-option-${latinOnlyContact.subject}`,
    );
    fireEvent.keyDown(input, { key: 'ArrowUp' });
    expect(input.getAttribute('aria-activedescendant')).toBe(
      `recipient-option-${crossOrgContact.subject}`,
    );
  });

  it('keeps ArrowDown as the next item under Arabic right-to-left layout', () => {
    renderPicker('ar');
    expect(document.documentElement.dir).toBe('rtl');
    expect(document.documentElement.lang).toBe('ar');
    expect(screen.getAllByText(directoryText('ar', 'facilityLabel'), { exact: false })).toHaveLength(
      4,
    );
    expect(screen.getByText('ليلى تجريبية')).toBeTruthy();

    const input = combobox();
    fireEvent.keyDown(input, { key: 'ArrowDown' });
    expect(input.getAttribute('aria-activedescendant')).toBe(
      `recipient-option-${otherFacilityContact.subject}`,
    );
  });

  it('selects the active same-facility contact with Enter', () => {
    const { onSelect } = renderPicker();
    fireEvent.keyDown(combobox(), { key: 'Enter' });
    expect(onSelect).toHaveBeenCalledExactlyOnceWith(sameFacilityContact.subject);
  });

  it('selects an approved other-facility contact with Enter', () => {
    const { onSelect } = renderPicker();
    const input = combobox();
    fireEvent.keyDown(input, { key: 'ArrowDown' });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(onSelect).toHaveBeenCalledExactlyOnceWith(otherFacilityContact.subject);
  });

  it('never selects a blocked recipient by keyboard or pointer', () => {
    const { onSelect } = renderPicker();
    const input = combobox();
    fireEvent.keyDown(input, { key: 'ArrowDown' });
    fireEvent.keyDown(input, { key: 'ArrowDown' });
    expect(input.getAttribute('aria-activedescendant')).toBe(
      `recipient-option-${crossOrgContact.subject}`,
    );
    fireEvent.keyDown(input, { key: 'Enter' });
    fireEvent.click(rowFor(crossOrgContact.subject));
    expect(onSelect).not.toHaveBeenCalled();
  });

  it('drops a stale active descendant when contacts shrink and ignores Enter', () => {
    const { onSelect, rerender } = renderPicker();
    const input = combobox();
    fireEvent.keyDown(input, { key: 'ArrowDown' });
    rerender({
      contacts: [sameFacilityContact],
      actor: mainActor,
      policy: policyOpen,
      selectedSubject: undefined,
      onSelect,
    });
    expect(input.hasAttribute('aria-activedescendant')).toBe(false);
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(onSelect).not.toHaveBeenCalled();
  });

  it('ignores arrow and Enter keys with no results but still clears on Escape', () => {
    const { onSelect } = renderPicker();
    const input = combobox();
    fireEvent.change(input, { target: { value: 'zzzz-no-match' } });
    // The empty-list early return leaves the event unprevented.
    expect(fireEvent.keyDown(input, { key: 'ArrowDown' })).toBe(true);
    fireEvent.keyDown(input, { key: 'ArrowUp' });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(onSelect).not.toHaveBeenCalled();
    fireEvent.keyDown(input, { key: 'Escape' });
    expect(input.value).toBe('');
    expect(rows()).toHaveLength(4);
  });

});

describe('RecipientPicker policy refusals', () => {
  it('marks an unapproved cross-facility person and explains the policy refusal together', () => {
    renderPicker('en', { policy: policyClosed });
    const row = rowFor(otherFacilityContact.subject);
    expect(row.textContent).toContain(directoryText('en', 'otherFacility'));
    expect(row.textContent).toContain(directoryText('en', 'blockedFacilityReason'));
    expect(row.textContent).toContain(CROSS_FACILITY_MESSAGING_DISABLED);
    expect(row.getAttribute('aria-disabled')).toBe('true');
    expect(rowFor(sameFacilityContact.subject).textContent).not.toContain(
      directoryText('en', 'otherFacility'),
    );
  });
});

describe('DirectoryPage and feature exports', () => {
  it('renders the picker through the real providers in Arabic', () => {
    wrap(<DirectoryPage />, 'ar');
    expect(document.documentElement.dir).toBe('rtl');
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe(directoryText('ar', 'title'));
    expect(rows()).toHaveLength(4);
    expect(screen.queryByRole('status')).toBeNull();
    fireEvent.keyDown(combobox(), { key: 'Enter' });
    expect(rowFor(sameFacilityContact.subject).getAttribute('aria-selected')).toBe('true');
    expect(screen.getByRole('status').textContent).toContain(
      directoryText('ar', 'selected'),
    );
    expect(screen.getByRole('status').textContent).toContain(sameFacilityContact.subject);
  });

  it('exports exactly the two public components', () => {
    expect(Object.keys(directory).sort()).toEqual(['DirectoryPage', 'RecipientPicker']);
  });
});

describe('RTL-safe layout', () => {
  it('uses logical styling properties in every feature component', () => {
    const featureDir = resolve(dirname(fileURLToPath(import.meta.url)));
    const componentFiles = readdirSync(featureDir).filter(
      (name) => name.endsWith('.tsx') && !name.includes('.test.'),
    );
    expect(componentFiles.length).toBeGreaterThan(0);
    for (const name of componentFiles) {
      const source = readFileSync(`${featureDir}/${name}`, 'utf8');
      expect(source).not.toMatch(
        /margin(Left|Right)|padding(Left|Right)|border(Left|Right)|textAlign:\s*['"](?:left|right)['"]/,
      );
    }
  });
});
