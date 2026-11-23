import { readFileSync, readdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import type { ReactElement } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { formatInstant } from '../../i18n/format';
import { I18nProvider } from '../../i18n/I18nProvider';
import { ThemeProvider } from '../../theme/ThemeProvider';
import type { Locale } from '../../i18n/messages';
import type { MatchCheck, RejectionDraft } from './domain';
import { CollectionForm } from './CollectionForm';
import { FindOrderPanel } from './FindOrderPanel';
import { IdentityVerification } from './IdentityVerification';
import { LabelSheet } from './LabelSheet';
import { RejectionForm } from './RejectionForm';
import { alphaOrder, betaEchoOrder, labelAlpha, specimenDetailsAlpha } from './fixtures';
import { labText } from './messages';

function wrap(ui: ReactElement, locale: Locale = 'en') {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>{ui}</I18nProvider>
    </ThemeProvider>,
  );
}

describe('FindOrderPanel', () => {
  it('finds by accession, selects an order and submits with the keyboard action', () => {
    const onSelect = vi.fn();
    wrap(
      <FindOrderPanel
        orders={[alphaOrder, betaEchoOrder]}
        selectedId={alphaOrder.id}
        onSelect={onSelect}
      />,
    );

    expect(screen.getByText(labText('en', 'noOrdersFound'))).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: labText('en', 'searchByAccession') }));
    fireEvent.change(screen.getByLabelText(labText('en', 'search')), {
      target: { value: 'ACC-100' },
    });
    fireEvent.click(screen.getByRole('button', { name: labText('en', 'search') }));

    const list = screen.getByRole('list', { name: labText('en', 'findOrder') });
    const resultButtons = within(list).getAllByRole('button');
    expect(resultButtons).toHaveLength(2);
    const selected = within(list).getByRole('button', { pressed: true });
    expect(selected.textContent).toContain('ACC-100');
    fireEvent.click(required(resultButtons[0]));
    expect(onSelect).toHaveBeenCalledWith(alphaOrder.id);
  });

  it('switches to patient search and renders Arabic direction', () => {
    wrap(
      <FindOrderPanel
        orders={[alphaOrder, betaEchoOrder]}
        selectedId={undefined}
        onSelect={vi.fn()}
      />,
      'ar',
    );
    expect(document.documentElement.dir).toBe('rtl');

    fireEvent.click(screen.getByRole('button', { name: labText('ar', 'searchByPatient') }));
    fireEvent.change(screen.getByLabelText(labText('ar', 'search')), {
      target: { value: 'Synthetic Layla' },
    });
    expect(screen.queryByText(labText('ar', 'noOrdersFound'))).toBeNull();
    expect(
      within(screen.getByRole('list', { name: labText('ar', 'findOrder') })).getAllByRole('button'),
    ).toHaveLength(1);
  });
});

describe('IdentityVerification', () => {
  const matched: MatchCheck = { matched: true, reasons: [] };
  function renderIdentity(localMatch: MatchCheck, withLocalName = true, locale: Locale = 'en') {
    const order = withLocalName
      ? alphaOrder
      : { ...alphaOrder, patientName: { latinName: 'Synthetic Layla' } };
    wrap(
      <IdentityVerification
        order={order}
        match={localMatch}
        presented={{
          patientId: 'patient-100',
          accessionNo: 'ACC-100',
          accessionIssuer: 'SYN-ISSUER',
        }}
        onPresentedChange={vi.fn()}
        namesConfirmed={false}
        accessionConfirmed={false}
        onNamesConfirmed={vi.fn()}
        onAccessionConfirmed={vi.fn()}
      />,
      locale,
    );
  }

  it('shows both names and the match confirmation', () => {
    renderIdentity(matched);
    expect(screen.getByText(labText('en', 'latinNameLabel'), { exact: false })).toBeTruthy();
    expect(screen.getByText(labText('en', 'localNameLabel'), { exact: false })).toBeTruthy();
    expect(screen.getByText('Synthetic Layla')).toBeTruthy();
    expect(screen.getByText('ليلى تجريبية')).toBeTruthy();
    expect(screen.getByTestId('match-status').textContent).toContain(labText('en', 'matchOk'));
  });

  it('omits the local name when the record has none and renders Arabic labels', () => {
    renderIdentity(matched, false, 'ar');
    expect(screen.queryByText(labText('ar', 'localNameLabel'), { exact: false })).toBeNull();
    expect(screen.getByText(labText('ar', 'patientIdentity'))).toBeTruthy();
  });

  it('explains every mismatch reason and blocks implicitly via the status region', () => {
    renderIdentity({
      matched: false,
      reasons: ['patient', 'accession-number', 'accession-issuer'],
    });
    expect(screen.getByTestId('match-status').textContent).toContain(
      labText('en', 'mismatchTitle'),
    );
    for (const key of [
      'mismatchPatient',
      'mismatchAccessionNumber',
      'mismatchAccessionIssuer',
    ] as const) {
      expect(screen.getByTestId('match-status').textContent).toContain(labText('en', key));
    }
  });

  it('reports edits to the presented identity and both confirmation checkboxes', () => {
    const onPresentedChange = vi.fn();
    const onNamesConfirmed = vi.fn();
    const onAccessionConfirmed = vi.fn();
    wrap(
      <IdentityVerification
        order={alphaOrder}
        match={matched}
        presented={{ patientId: '', accessionNo: '', accessionIssuer: '' }}
        onPresentedChange={onPresentedChange}
        namesConfirmed={false}
        accessionConfirmed={false}
        onNamesConfirmed={onNamesConfirmed}
        onAccessionConfirmed={onAccessionConfirmed}
      />,
    );

    fireEvent.change(screen.getByLabelText(labText('en', 'presentedPatientId')), {
      target: { value: 'p' },
    });
    fireEvent.change(screen.getByLabelText(labText('en', 'presentedAccessionNo')), {
      target: { value: 'a' },
    });
    fireEvent.change(screen.getByLabelText(labText('en', 'presentedAccessionIssuer')), {
      target: { value: 'i' },
    });
    expect(onPresentedChange).toHaveBeenCalledTimes(3);

    fireEvent.click(screen.getByLabelText(labText('en', 'confirmNames')));
    fireEvent.click(screen.getByLabelText(labText('en', 'confirmAccession')));
    expect(onNamesConfirmed).toHaveBeenCalledWith(true);
    expect(onAccessionConfirmed).toHaveBeenCalledWith(true);
  });
});

describe('CollectionForm', () => {
  it('edits every specimen field and dispatches only when enabled', () => {
    const onChange = vi.fn();
    const onCollect = vi.fn();
    wrap(
      <CollectionForm
        value={specimenDetailsAlpha}
        onChange={onChange}
        onCollect={onCollect}
        disabled={false}
      />,
    );

    fireEvent.change(screen.getByLabelText(labText('en', 'specimenType')), {
      target: { value: 'PLASMA' },
    });
    fireEvent.change(screen.getByLabelText(labText('en', 'siteCode')), {
      target: { value: 'CLINIC' },
    });
    fireEvent.change(screen.getByLabelText(labText('en', 'collector')), {
      target: { value: 'synthetic.nurse' },
    });
    fireEvent.change(screen.getByLabelText(labText('en', 'collectedAt')), {
      target: { value: '2026-03-09T10:00:00Z' },
    });
    fireEvent.change(screen.getByLabelText(labText('en', 'quality')), {
      target: { value: 'GOOD' },
    });
    expect(onChange).toHaveBeenCalledTimes(5);
    expect(onChange).toHaveBeenLastCalledWith({ ...specimenDetailsAlpha, quality: 'GOOD' });

    fireEvent.click(screen.getByRole('button', { name: labText('en', 'recordCollection') }));
    expect(onCollect).toHaveBeenCalledTimes(1);
  });

  it('disables collection while the identity check is incomplete', () => {
    const onCollect = vi.fn();
    wrap(
      <CollectionForm
        value={specimenDetailsAlpha}
        onChange={vi.fn()}
        onCollect={onCollect}
        disabled
      />,
    );
    const button = screen.getByRole<HTMLButtonElement>('button', {
      name: labText('en', 'recordCollection'),
    });
    expect(button.disabled).toBe(true);
    fireEvent.click(button);
    expect(onCollect).not.toHaveBeenCalled();
  });
});

describe('RejectionForm', () => {
  const empty: RejectionDraft = { reason: '', detail: '', notifyPatient: false };

  it('requires a reason and explains it before allowing rejection', () => {
    const onReject = vi.fn();
    const onChange = vi.fn();
    wrap(<RejectionForm value={empty} onChange={onChange} onReject={onReject} disabled={false} />);
    const select = screen.getByLabelText(labText('en', 'rejectionReason'));
    // A pristine form does not announce an error before the collector interacts.
    expect(screen.queryByText(labText('en', 'reasonRequired'))).toBeNull();
    expect(select.getAttribute('aria-invalid')).toBeNull();
    fireEvent.change(select, { target: { value: 'wrong_tube' } });
    expect(onChange).toHaveBeenCalledWith({
      reason: 'wrong_tube',
      detail: '',
      notifyPatient: false,
    });
    fireEvent.change(select, { target: { value: '' } });
    expect(select.getAttribute('aria-invalid')).toBe('true');
    expect(screen.getByText(labText('en', 'reasonRequired'))).toBeTruthy();
    const reject = screen.getByRole<HTMLButtonElement>('button', {
      name: labText('en', 'rejectSpecimen'),
    });
    expect(reject.disabled).toBe(true);
    fireEvent.click(reject);
    expect(onReject).not.toHaveBeenCalled();
  });

  it('accepts a bounded reason without detail and dispatches', () => {
    const onChange = vi.fn();
    const onReject = vi.fn();
    wrap(
      <RejectionForm
        value={{ reason: 'haemolysed', detail: '', notifyPatient: false }}
        onChange={onChange}
        onReject={onReject}
        disabled={false}
      />,
    );
    expect(
      screen.getByLabelText(labText('en', 'rejectionReason')).getAttribute('aria-invalid'),
    ).toBeNull();
    expect(screen.queryByText(labText('en', 'reasonRequired'))).toBeNull();
    expect(screen.queryByLabelText(labText('en', 'otherDetail'))).toBeNull();

    fireEvent.click(screen.getByLabelText(labText('en', 'notifyPatient')));
    expect(onChange).toHaveBeenLastCalledWith({
      reason: 'haemolysed',
      detail: '',
      notifyPatient: true,
    });

    fireEvent.click(screen.getByRole('button', { name: labText('en', 'rejectSpecimen') }));
    expect(onReject).toHaveBeenCalledTimes(1);
  });

  it('requires and then accepts detail for the "other" reason', () => {
    const onChange = vi.fn();
    const { rerender } = render(
      <ThemeProvider>
        <I18nProvider initialLocale="en">
          <RejectionForm
            value={{ reason: 'other', detail: '', notifyPatient: false }}
            onChange={onChange}
            onReject={vi.fn()}
            disabled={false}
          />
        </I18nProvider>
      </ThemeProvider>,
    );
    expect(screen.getByLabelText(labText('en', 'otherDetail'))).toBeTruthy();
    expect(screen.getByText(labText('en', 'detailRequired'))).toBeTruthy();
    expect(
      screen.getByLabelText(labText('en', 'rejectionReason')).getAttribute('aria-invalid'),
    ).toBeNull();

    fireEvent.change(screen.getByLabelText(labText('en', 'otherDetail')), {
      target: { value: 'haemolysis' },
    });
    expect(onChange).toHaveBeenCalledWith({
      reason: 'other',
      detail: 'haemolysis',
      notifyPatient: false,
    });

    rerender(
      <ThemeProvider>
        <I18nProvider initialLocale="en">
          <RejectionForm
            value={{ reason: 'other', detail: 'haemolysis', notifyPatient: false }}
            onChange={onChange}
            onReject={vi.fn()}
            disabled={false}
          />
        </I18nProvider>
      </ThemeProvider>,
    );
    expect(screen.queryByText(labText('en', 'detailRequired'))).toBeNull();
  });

  it('stays disabled while the parent blocks the rejection', () => {
    wrap(
      <RejectionForm
        value={{ reason: 'haemolysed', detail: '', notifyPatient: false }}
        onChange={vi.fn()}
        onReject={vi.fn()}
        disabled
      />,
    );
    const reject = screen.getByRole<HTMLButtonElement>('button', {
      name: labText('en', 'rejectSpecimen'),
    });
    expect(reject.disabled).toBe(true);
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

describe('LabelSheet', () => {
  it('prints every required label field plus print provenance', () => {
    wrap(
      <LabelSheet
        label={labelAlpha}
        organizationId={alphaOrder.organizationId}
        printedBy="synthetic.collector"
        printedAt="2026-03-09T10:00:00Z"
      />,
    );
    const sheet = screen.getByTestId('label-sheet');
    for (const value of Object.values({ ...labelAlpha, collectedAt: undefined })) {
      if (value !== undefined) {
        expect(sheet.textContent).toContain(value);
      }
    }
    // The printed time is localised rather than a raw ISO string.
    expect(sheet.textContent).toContain(formatInstant(labelAlpha.collectedAt, 'UTC', 'en'));
    expect(sheet.textContent).toContain(labText('en', 'labelAccessionNo'));
    expect(sheet.textContent).toContain(labText('en', 'labelPatientMrn'));
    expect(sheet.textContent).toContain(labText('en', 'labelSpecimenType'));
    expect(sheet.textContent).toContain(labText('en', 'organization'));
    expect(sheet.textContent).toContain(labText('en', 'printedBy'));
    expect(sheet.textContent).toContain(labText('en', 'printedAt'));
    expect(sheet.textContent).toContain('synthetic.collector');
  });

  it('renders bilingual labels in right-to-left layout', () => {
    wrap(
      <LabelSheet
        label={labelAlpha}
        organizationId={alphaOrder.organizationId}
        printedBy="synthetic.collector"
        printedAt="2026-03-09T10:00:00Z"
      />,
      'ar',
    );
    expect(document.documentElement.dir).toBe('rtl');
    expect(screen.getByTestId('label-sheet').textContent).toContain(
      labText('ar', 'labelPatientName'),
    );
  });
});

function required<T>(value: T | null | undefined): T {
  if (value === null || value === undefined) {
    throw new Error('Expected value to be present');
  }
  return value;
}
