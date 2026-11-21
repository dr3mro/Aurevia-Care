import type { ReactElement } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { I18nProvider } from '../../i18n/I18nProvider';
import type { Locale } from '../../i18n/messages';
import { ThemeProvider } from '../../theme/ThemeProvider';
import { AllergyWarning } from './AllergyWarning';
import { DrugCatalogSearch } from './DrugCatalogSearch';
import { DraftForm } from './DraftForm';
import { StatusTimeline } from './StatusTimeline';
import type { AllergyFinding } from './domain';
import { createSyntheticDraft, syntheticCatalog, syntheticEvents, syntheticSubmissionId } from './fixtures';
import { prescriptionEventMessageKey, prescriptionText } from './messages';

function wrap(ui: ReactElement, locale: Locale = 'en') {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>{ui}</I18nProvider>
    </ThemeProvider>,
  );
}

describe('DrugCatalogSearch', () => {
  const base = {
    results: syntheticCatalog,
    query: 'amox',
    formulation: 'tablet',
    selectedCode: 'DRUG-AMOX',
    onQueryChange: vi.fn(),
    onFormulationChange: vi.fn(),
    onSearch: vi.fn(),
    onSelect: vi.fn(),
  };

  it('lists tagged alternatives and reports the selected drug', () => {
    const onSelect = vi.fn();
    wrap(<DrugCatalogSearch {...base} onSelect={onSelect} />);
    const options = screen.getAllByTestId('drug-option');
    expect(options).toHaveLength(4);
    expect(options[0]?.getAttribute('data-selected')).toBe('true');
    expect(options[1]?.getAttribute('data-selected')).toBe('false');
    expect(screen.getAllByText(/Amoxicillin/).length).toBeGreaterThan(0);
    expect(options[1]?.querySelector('bdi')?.getAttribute('dir')).toBe('auto');

    fireEvent.click(
      screen.getAllByRole('button', { name: prescriptionText('en', 'select') })[1]!,
    );
    expect(onSelect).toHaveBeenCalledWith(syntheticCatalog[1]);
  });

  it('shows the no-matches message for an empty result set', () => {
    wrap(<DrugCatalogSearch {...base} results={[]} />);
    expect(screen.getByTestId('drug-no-matches').textContent).toBe(
      prescriptionText('en', 'noMatches'),
    );
  });

  it('reports query, formulation and search actions', () => {
    const onQueryChange = vi.fn();
    const onFormulationChange = vi.fn();
    const onSearch = vi.fn();
    wrap(
      <DrugCatalogSearch
        {...base}
        onQueryChange={onQueryChange}
        onFormulationChange={onFormulationChange}
        onSearch={onSearch}
      />,
    );
    fireEvent.change(screen.getByLabelText(prescriptionText('en', 'searchQuery')), {
      target: { value: 'cefalexin' },
    });
    fireEvent.change(screen.getByLabelText(prescriptionText('en', 'formulation')), {
      target: { value: 'capsule' },
    });
    fireEvent.click(screen.getByRole('button', { name: prescriptionText('en', 'search') }));
    expect(onQueryChange).toHaveBeenCalledWith('cefalexin');
    expect(onFormulationChange).toHaveBeenCalledWith('capsule');
    expect(onSearch).toHaveBeenCalledTimes(1);
  });

  it('uses Arabic labels under RTL', () => {
    wrap(<DrugCatalogSearch {...base} />, 'ar');
    expect(document.documentElement.dir).toBe('rtl');
    expect(screen.getByRole('button', { name: prescriptionText('ar', 'search') })).toBeTruthy();
  });
});

describe('AllergyWarning', () => {
  const findings: AllergyFinding[] = [
    { kind: 'match', ingredient: 'penicillin' },
    { kind: 'unknown', ingredient: 'clavulanate' },
  ];

  it('shows a match and an unknown ingredient with distinct shapes and words', () => {
    wrap(<AllergyWarning findings={findings} />);
    const banner = screen.getByTestId('allergy-warning');
    expect(banner.getAttribute('role')).toBe('status');
    const items = banner.querySelectorAll('li');
    expect(items).toHaveLength(2);
    expect(items[0]?.getAttribute('data-kind')).toBe('match');
    expect(items[0]?.querySelector('[data-allergy-icon]')?.textContent).toBe('!');
    expect(screen.getByText(prescriptionText('en', 'allergyMatch'))).toBeTruthy();
    expect(items[1]?.getAttribute('data-kind')).toBe('unknown');
    expect(items[1]?.querySelector('[data-allergy-icon]')?.textContent).toBe('?');
    expect(screen.getByText(prescriptionText('en', 'allergyUnknown'))).toBeTruthy();
    expect(banner.querySelector('bdi')?.getAttribute('dir')).toBe('auto');
  });

  it('renders nothing when the check is clear', () => {
    const { container } = wrap(<AllergyWarning findings={[]} />);
    expect(container.firstChild).toBeNull();
  });
});

describe('DraftForm', () => {
  const base = {
    draft: createSyntheticDraft(),
    findings: [] as AllergyFinding[],
    submitDisabled: false,
    onChange: vi.fn(),
    onSubmit: vi.fn(),
  };

  it('edits draft fields, attests, chooses a channel and submits', () => {
    const onChange = vi.fn();
    const onSubmit = vi.fn();
    const { rerender } = wrap(<DraftForm {...base} onChange={onChange} onSubmit={onSubmit} />);
    fireEvent.change(screen.getByLabelText(prescriptionText('en', 'patientId')), {
      target: { value: 'synthetic-patient-002' },
    });
    expect(onChange).toHaveBeenCalledWith({ patientId: 'synthetic-patient-002' });

    fireEvent.click(screen.getByRole('radio', { name: prescriptionText('en', 'channelPrintable') }));
    expect(onChange).toHaveBeenCalledWith({ channel: 'printable' });
    // The radio is controlled, so render the new value before choosing the
    // other channel to exercise both change handlers.
    rerender(
      <ThemeProvider>
        <I18nProvider initialLocale="en">
          <DraftForm
            {...base}
            draft={createSyntheticDraft({ channel: 'printable' })}
            onChange={onChange}
            onSubmit={onSubmit}
          />
        </I18nProvider>
      </ThemeProvider>,
    );
    fireEvent.click(screen.getByRole('radio', { name: prescriptionText('en', 'channelEPrescribing') }));
    expect(onChange).toHaveBeenCalledWith({ channel: 'e-prescribing' });
    fireEvent.click(screen.getByRole('checkbox', { name: prescriptionText('en', 'attest') }));
    expect(onChange).toHaveBeenCalledWith({ attested: false });

    fireEvent.click(screen.getByRole('button', { name: prescriptionText('en', 'submit') }));
    expect(onSubmit).toHaveBeenCalledTimes(1);
  });

  it('edits the refills field and names the not-selected state', () => {
    const onChange = vi.fn();
    const { rerender } = wrap(<DraftForm {...base} onChange={onChange} />);
    fireEvent.change(screen.getByLabelText(prescriptionText('en', 'refills')), {
      target: { value: '3' },
    });
    expect(onChange).toHaveBeenCalledWith({ refills: '3' });

    rerender(
      <ThemeProvider>
        <I18nProvider initialLocale="en">
          <DraftForm {...base} draft={createSyntheticDraft({ drugCode: '' })} />
        </I18nProvider>
      </ThemeProvider>,
    );
    expect(screen.getByText(prescriptionText('en', 'notSelected'))).toBeTruthy();
  });

  it('shows refills for e-prescribing only', () => {
    const { rerender } = wrap(<DraftForm {...base} />);
    expect(screen.getByLabelText(prescriptionText('en', 'refills'))).toBeTruthy();
    rerender(
      <ThemeProvider>
        <I18nProvider initialLocale="en">
          <DraftForm {...base} draft={createSyntheticDraft({ channel: 'printable' })} />
        </I18nProvider>
      </ThemeProvider>,
    );
    expect(screen.queryByLabelText(prescriptionText('en', 'refills'))).toBeNull();
    expect(screen.getByText(prescriptionText('en', 'refillsNotSupported'))).toBeTruthy();
  });

  it('requires an override reason when allergy findings exist', () => {
    const onChange = vi.fn();
    wrap(
      <DraftForm
        {...base}
        onChange={onChange}
        findings={[{ kind: 'match', ingredient: 'penicillin' }]}
      />,
    );
    fireEvent.change(screen.getByLabelText(prescriptionText('en', 'overrideReason')), {
      target: { value: 'synthetic review' },
    });
    expect(onChange).toHaveBeenCalledWith({ overrideReason: 'synthetic review' });
  });

  it('does not submit a disabled draft', () => {
    const onSubmit = vi.fn();
    wrap(<DraftForm {...base} submitDisabled onSubmit={onSubmit} />);
    expect((screen.getByRole('button', { name: prescriptionText('en', 'submit') }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(screen.getByRole('button', { name: prescriptionText('en', 'submit') }));
    // A disabled button cannot be clicked, so submit the form directly to
    // exercise the guard inside the submit handler.
    fireEvent.submit(screen.getByRole('form', { name: prescriptionText('en', 'draftForm') }));
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('renders Arabic labels under RTL', () => {
    wrap(<DraftForm {...base} />, 'ar');
    expect(document.documentElement.dir).toBe('rtl');
    expect(screen.getByRole('button', { name: prescriptionText('ar', 'submit') })).toBeTruthy();
  });
});

describe('StatusTimeline', () => {
  it('renders every event kind with its word, instant and submission id', () => {
    wrap(<StatusTimeline events={syntheticEvents} submissionId={syntheticSubmissionId} />);
    for (const event of syntheticEvents) {
      expect(screen.getByText(prescriptionText('en', prescriptionEventMessageKey[event.kind]))).toBeTruthy();
    }
    expect(screen.getAllByRole('listitem')).toHaveLength(syntheticEvents.length);
    expect(screen.getAllByText(syntheticSubmissionId).length).toBeGreaterThan(0);
    expect(screen.getAllByRole('time')[0]?.getAttribute('datetime')).toBe('2026-03-09T09:00:00Z');
  });

  it('shows the empty message when there are no events', () => {
    wrap(<StatusTimeline events={[]} submissionId={null} />);
    expect(screen.getByTestId('rx-empty-timeline').textContent).toBe(
      prescriptionText('en', 'emptyTimeline'),
    );
  });

  it('omits the submission id when the API has not issued one', () => {
    wrap(<StatusTimeline events={syntheticEvents.slice(0, 1)} submissionId={null} />);
    expect(screen.queryByText(syntheticSubmissionId)).toBeNull();
  });

  it('uses Arabic event words under RTL', () => {
    wrap(<StatusTimeline events={syntheticEvents.slice(0, 1)} submissionId={null} />, 'ar');
    expect(document.documentElement.dir).toBe('rtl');
    expect(screen.getByText(prescriptionText('ar', 'eventSubmitted'))).toBeTruthy();
  });
});
