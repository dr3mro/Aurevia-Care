import type { ReactElement } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { I18nProvider } from '../../i18n/I18nProvider';
import { ThemeProvider } from '../../theme/ThemeProvider';
import type { PrescriptionsScreenProps } from './PrescriptionsScreen';
import { PrescriptionsScreen } from './PrescriptionsScreen';
import type { AllergyFinding } from './domain';
import {
  createSyntheticDraft,
  syntheticCatalog,
  syntheticEvents,
  syntheticSubmissionId,
} from './fixtures';
import { prescriptionText } from './messages';

function wrap(ui: ReactElement) {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale="en">{ui}</I18nProvider>
    </ThemeProvider>,
  );
}

function screenProps(overrides: Partial<PrescriptionsScreenProps> = {}): PrescriptionsScreenProps {
  return {
    catalog: syntheticCatalog,
    findings: [],
    draft: createSyntheticDraft(),
    events: syntheticEvents.slice(0, 3),
    submissionId: syntheticSubmissionId,
    transmission: 'confirmed',
    announcement: '',
    query: 'amox',
    formulation: 'tablet',
    onQueryChange: vi.fn(),
    onFormulationChange: vi.fn(),
    onSearch: vi.fn(),
    onSelect: vi.fn(),
    onDraftChange: vi.fn(),
    onSubmit: vi.fn(),
    onReconcile: vi.fn(),
    ...overrides,
  };
}

describe('PrescriptionsScreen', () => {
  it('composes the search, draft form and status timeline', () => {
    wrap(<PrescriptionsScreen {...screenProps()} />);
    expect(screen.getByRole('heading', { name: prescriptionText('en', 'prescriptions') })).toBeTruthy();
    expect(screen.getByRole('textbox', { name: prescriptionText('en', 'searchQuery') })).toBeTruthy();
    expect(screen.queryByRole('table')).toBeNull();
    expect(screen.getByText(prescriptionText('en', 'eventAccepted'))).toBeTruthy();
    expect(screen.queryByTestId('ambiguous-notice')).toBeNull();
  });

  it('offers a reconcile action only for an ambiguous transmission', () => {
    const onReconcile = vi.fn();
    wrap(<PrescriptionsScreen {...screenProps({ transmission: 'ambiguous', onReconcile })} />);
    expect(screen.getByTestId('ambiguous-notice').textContent).toBe(
      prescriptionText('en', 'ambiguousNotice'),
    );
    fireEvent.click(screen.getByRole('button', { name: prescriptionText('en', 'reconcile') }));
    expect(onReconcile).toHaveBeenCalledTimes(1);
  });

  it('does not offer reconciliation while no response exists', () => {
    wrap(<PrescriptionsScreen {...screenProps({ transmission: null, events: [], submissionId: null })} />);
    expect(screen.queryByRole('button', { name: prescriptionText('en', 'reconcile') })).toBeNull();
    expect(screen.getByTestId('rx-empty-timeline')).toBeTruthy();
  });

  it('shows allergy warnings beside the draft and gates submission', () => {
    const findings: AllergyFinding[] = [{ kind: 'match', ingredient: 'penicillin' }];
    wrap(<PrescriptionsScreen {...screenProps({ findings })} />);
    expect(screen.getByTestId('allergy-warning')).toBeTruthy();
    expect(
      (screen.getByRole('button', { name: prescriptionText('en', 'submit') }) as HTMLButtonElement)
        .disabled,
    ).toBe(true);
  });
});
