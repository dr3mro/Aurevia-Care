import type { ReactElement } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { stateViewText } from '../../../components/stateViewMessages';
import type { Locale } from '../../../i18n/messages';
import { I18nProvider } from '../../../i18n/I18nProvider';
import { ThemeProvider } from '../../../theme/ThemeProvider';
import { OrderConflict } from './domain';
import type { OrderAllergy } from './domain';
import type { SavedOrderDraft } from './domain';
import {
  entryAlphaOrganizationId,
  entryBetaOrganizationId,
  syntheticActiveOrders,
  syntheticAllergies,
  syntheticCatalog,
  syntheticFacilityId,
  syntheticPatientLocalId,
  syntheticSavedDraft,
} from './fixtures';
import { entryText } from './messages';
import { OrderEntryPanel } from './OrderEntryPanel';
import type { OrderEntryPanelProps } from './OrderEntryPanel';

const clinicianRole: OrderEntryPanelProps['role'] = 'clinician';

const clipboard = vi.hoisted(() => ({ writeText: vi.fn(() => Promise.resolve()) }));
Object.defineProperty(navigator, 'clipboard', { configurable: true, value: clipboard });

function wrap(ui: ReactElement, locale: Locale = 'en') {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>{ui}</I18nProvider>
    </ThemeProvider>,
  );
}

function renderPanel(overrides: Partial<OrderEntryPanelProps> = {}, locale: Locale = 'en') {
  const props: OrderEntryPanelProps = {
    organizationId: entryAlphaOrganizationId,
    patientLocalId: syntheticPatientLocalId,
    encounterLocalId: 'enc-001',
    facilityId: syntheticFacilityId,
    role: 'clinician',
    catalog: syntheticCatalog,
    allergies: syntheticAllergies,
    activeOrders: syntheticActiveOrders,
    savedDraft: syntheticSavedDraft,
    onSaveDraft: vi.fn(() => Promise.resolve(syntheticSavedDraft)),
    onReload: vi.fn(() => Promise.resolve(syntheticSavedDraft)),
    ...overrides,
  };
  return { ...wrap(<OrderEntryPanel {...props} />, locale), props };
}

function questionField() {
  return screen.getByRole('textbox', { name: /clinical question/i });
}

function preparationField(locale: Locale = 'en') {
  return screen.getByRole('textbox', { name: entryText(locale, 'preparation') });
}

function searchField(locale: Locale = 'en') {
  return screen.getByRole('textbox', { name: entryText(locale, 'catalogSearch') });
}

function addItem(name: string, locale: Locale = 'en') {
  fireEvent.click(screen.getByRole('button', { name: `${entryText(locale, 'add')}: ${name}` }));
}

describe('OrderEntryPanel', () => {
  it('forbids a role without ordering privilege before rendering any entry control', () => {
    renderPanel({ role: 'reception' });
    expect(screen.getByRole('alert').textContent).toContain(stateViewText('en', 'forbidden'));
    expect(screen.queryByRole('textbox', { name: entryText('en', 'catalogSearch') })).toBeNull();
    expect(screen.queryByRole('button', { name: entryText('en', 'saveDraft') })).toBeNull();
  });

  it('shows allergies and active orders and searches only active, in-tenant catalog items', () => {
    renderPanel();
    const allergies = screen.getByRole('region', { name: entryText('en', 'allergies') });
    const orders = screen.getByRole('region', { name: entryText('en', 'activeOrders') });
    expect(within(allergies).getByText('Iodinated contrast')).toBeTruthy();
    expect(within(orders).getByText('CT abdomen with contrast')).toBeTruthy();

    fireEvent.change(searchField(), { target: { value: 'LAB-CBC' } });
    expect(
      screen.getByRole('button', { name: `${entryText('en', 'add')}: Complete blood count` }),
    ).toBeTruthy();
    expect(
      screen.queryByRole('button', { name: `${entryText('en', 'add')}: Retired test` }),
    ).toBeNull();
    expect(
      screen.queryByRole('button', { name: `${entryText('en', 'add')}: Beta tenant test` }),
    ).toBeNull();
  });

  it('warns when a selected item duplicates an existing active order in the same tenant', () => {
    renderPanel();
    expect(screen.queryByTestId('entry-duplicate')).toBeNull();
    addItem('CT abdomen with contrast');
    expect(screen.getByTestId('entry-duplicate').textContent).toContain('CT abdomen with contrast');
  });

  it('requires a clinical question for imaging but not for a lab-only draft', async () => {
    const { props } = renderPanel();
    fireEvent.click(screen.getByRole('button', { name: entryText('en', 'saveDraft') }));
    await waitFor(() => expect(props.onSaveDraft).toHaveBeenCalledTimes(1));
    expect(screen.getByTestId('entry-status').textContent).toContain(
      entryText('en', 'savedNotice'),
    );

    addItem('CT abdomen with contrast');
    fireEvent.click(screen.getByRole('button', { name: entryText('en', 'saveDraft') }));
    expect(screen.getByRole('alert').textContent).toContain(
      entryText('en', 'clinicalQuestionRequired'),
    );
    expect(props.onSaveDraft).toHaveBeenCalledTimes(1);

    fireEvent.change(questionField(), { target: { value: 'Synthetic question for review.' } });
    fireEvent.click(screen.getByRole('button', { name: entryText('en', 'saveDraft') }));
    await waitFor(() => expect(props.onSaveDraft).toHaveBeenCalledTimes(2));
  });

  it('saves a draft with the version it opened and announces the result', async () => {
    const next = { ...syntheticSavedDraft, version: 5 };
    const onSaveDraft = vi.fn(() => Promise.resolve(next));
    const { props } = renderPanel({ onSaveDraft });
    fireEvent.click(screen.getByRole('button', { name: entryText('en', 'saveDraft') }));
    await waitFor(() => expect(props.onSaveDraft).toHaveBeenCalledTimes(1));
    expect(props.onSaveDraft).toHaveBeenCalledWith(
      expect.objectContaining({ patientId: syntheticPatientLocalId }),
      syntheticSavedDraft.version,
    );
    expect(screen.getByTestId('entry-status').textContent).toContain(
      entryText('en', 'savedNotice'),
    );
  });

  it('keeps typed work on a stale version and offers compare, reload and copy', async () => {
    const server: SavedOrderDraft = {
      ...syntheticSavedDraft,
      version: 9,
      content: { ...syntheticSavedDraft.content, preparationInstructions: 'Server preparation.' },
    };
    const onSaveDraft = vi.fn(() => Promise.reject(new OrderConflict(server)));
    const onReload = vi.fn(() => Promise.resolve(server));
    const { props } = renderPanel({ onSaveDraft, onReload });

    fireEvent.change(preparationField(), { target: { value: 'Clinician typed preparation.' } });
    fireEvent.click(screen.getByRole('button', { name: entryText('en', 'saveDraft') }));
    await waitFor(() =>
      expect(screen.getByRole('alert').textContent).toContain(stateViewText('en', 'conflict')),
    );
    expect((preparationField() as HTMLTextAreaElement).value).toBe('Clinician typed preparation.');

    fireEvent.click(screen.getByRole('button', { name: stateViewText('en', 'compare') }));
    const compare = screen.getByRole('region', { name: entryText('en', 'compareTitle') });
    const panes = compare.querySelectorAll('pre');
    expect(panes[0]?.textContent).toContain('Server preparation.');
    expect(panes[1]?.textContent).toContain('Clinician typed preparation.');
    fireEvent.click(screen.getByRole('button', { name: stateViewText('en', 'compare') }));
    expect(screen.queryByRole('region', { name: entryText('en', 'compareTitle') })).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: stateViewText('en', 'copyMyChanges') }));
    expect(clipboard.writeText).toHaveBeenCalledWith(
      expect.stringContaining('Clinician typed preparation.'),
    );

    fireEvent.click(screen.getByRole('button', { name: stateViewText('en', 'reload') }));
    await waitFor(() => expect(props.onReload).toHaveBeenCalledTimes(1));
    expect((preparationField() as HTMLTextAreaElement).value).toBe('Server preparation.');
    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.getByTestId('entry-status').textContent).toContain(
      entryText('en', 'reloadNotice'),
    );
  });
});

describe('OrderEntryPanel states and locale', () => {
  it('reports a failed save without clearing the typed draft', async () => {
    const onSaveDraft = vi.fn(() => Promise.reject(new Error('network')));
    renderPanel({ onSaveDraft });
    fireEvent.change(preparationField(), { target: { value: 'Still here.' } });
    fireEvent.click(screen.getByRole('button', { name: entryText('en', 'saveDraft') }));
    await waitFor(() =>
      expect(screen.getByRole('alert').textContent).toContain(entryText('en', 'saveError')),
    );
    expect((preparationField() as HTMLTextAreaElement).value).toBe('Still here.');
  });

  it('shows empty states for absent allergies, orders and search results', () => {
    renderPanel({ allergies: [], activeOrders: [] });
    expect(screen.getByText(entryText('en', 'noAllergies'))).toBeTruthy();
    expect(screen.getByText(entryText('en', 'noActiveOrders'))).toBeTruthy();
    fireEvent.change(searchField(), { target: { value: 'does not exist' } });
    expect(screen.getByText(entryText('en', 'catalogEmpty'))).toBeTruthy();
  });

  it('starts with an empty selection when there is no saved draft and submits with the form', () => {
    const { props } = renderPanel({ savedDraft: undefined });
    expect(screen.getByText(entryText('en', 'noSelectedItems'))).toBeTruthy();
    const save = screen.getByRole<HTMLButtonElement>('button', {
      name: entryText('en', 'saveDraft'),
    });
    expect(save.type).toBe('submit');
    fireEvent.submit(required(save.closest('form')));
    expect(screen.getByRole('alert').textContent).toContain(entryText('en', 'noItems'));
    expect(props.onSaveDraft).not.toHaveBeenCalled();
  });

  it('discards typed work only when the clinician explicitly changes organization', () => {
    const { rerender } = renderPanel();
    fireEvent.change(preparationField(), { target: { value: 'Typed alpha preparation.' } });
    expect((preparationField() as HTMLTextAreaElement).value).toBe('Typed alpha preparation.');

    rerender(
      <ThemeProvider>
        <I18nProvider initialLocale="en">
          <OrderEntryPanel
            organizationId={entryBetaOrganizationId}
            patientLocalId={syntheticPatientLocalId}
            encounterLocalId="enc-001"
            facilityId={syntheticFacilityId}
            role={clinicianRole}
            catalog={syntheticCatalog}
            allergies={syntheticAllergies}
            activeOrders={syntheticActiveOrders}
            savedDraft={syntheticSavedDraft}
            onSaveDraft={vi.fn(() => Promise.resolve(syntheticSavedDraft))}
            onReload={vi.fn(() => Promise.resolve(syntheticSavedDraft))}
          />
        </I18nProvider>
      </ThemeProvider>,
    );
    expect((preparationField() as HTMLTextAreaElement).value).toBe('');
    expect(screen.queryByTestId('entry-duplicate')).toBeNull();
  });

  it('removes a selected item and edits priority and delivery mode', () => {
    renderPanel();
    const selected = screen.getByRole('region', { name: entryText('en', 'selectedItems') });
    expect(within(selected).getAllByRole('listitem')).toHaveLength(1);
    fireEvent.click(
      screen.getByRole('button', { name: `${entryText('en', 'remove')}: Complete blood count` }),
    );
    expect(screen.getByText(entryText('en', 'noSelectedItems'))).toBeTruthy();

    fireEvent.change(screen.getByRole('combobox', { name: entryText('en', 'priority') }), {
      target: { value: 'stat' },
    });
    fireEvent.change(screen.getByRole('combobox', { name: entryText('en', 'deliveryMode') }), {
      target: { value: 'print' },
    });
    fireEvent.click(screen.getByRole('button', { name: entryText('en', 'saveDraft') }));
    expect(screen.getByRole('alert').textContent).toContain(entryText('en', 'noItems'));
  });

  it('falls back to the Latin allergy display when no local name exists', () => {
    const withoutLocal: OrderAllergy[] = [
      {
        organizationId: entryAlphaOrganizationId,
        id: 'allergy-no-local',
        code: 'LATEX',
        originalText: 'Synthetic latex allergy',
        display: 'Latex',
        source: 'synthetic intake',
        effectiveOn: '2026-01-12',
        verification: 'verified',
        clinicalAuthor: 'synthetic.clinician',
        enteredBy: 'synthetic.assistant',
      },
    ];
    renderPanel({ allergies: withoutLocal });
    const allergies = screen.getByRole('region', { name: entryText('en', 'allergies') });
    expect(within(allergies).getByText('Latex')).toBeTruthy();
  });

  it('renders Arabic RTL copy with isolated mixed-script values', () => {
    renderPanel({}, 'ar');
    expect(document.documentElement.dir).toBe('rtl');
    expect(
      screen.getByRole('heading', { level: 2, name: entryText('ar', 'panelTitle') }),
    ).toBeTruthy();
    const orders = screen.getByRole('region', { name: entryText('ar', 'activeOrders') });
    expect(within(orders).getByText('CT abdomen with contrast').getAttribute('dir')).toBe('auto');
    expect(screen.getByRole('textbox', { name: entryText('ar', 'clinicalQuestion') })).toBeTruthy();
  });
});

function required<T>(value: T | null | undefined): T {
  if (value === null || value === undefined) {
    throw new Error('Expected value to be present');
  }
  return value;
}
