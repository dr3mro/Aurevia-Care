import type { ReactElement } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { I18nProvider } from '../../i18n/I18nProvider';
import { ThemeProvider } from '../../theme/ThemeProvider';
import type { Locale } from '../../i18n/messages';
import type { CollectionOrder } from './domain';
import { CollectionDeskScreen } from './CollectionDeskScreen';
import type { CollectionDeskScreenProps } from './CollectionDeskScreen';
import { alphaOrder, labelAlpha, presentedAlpha } from './fixtures';
import { labText } from './messages';

function wrap(ui: ReactElement, locale: Locale = 'en') {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>{ui}</I18nProvider>
    </ThemeProvider>,
  );
}

function screenFor(overrides: Partial<CollectionDeskScreenProps> = {}): CollectionDeskScreenProps {
  return {
    orders: [alphaOrder],
    selected: alphaOrder,
    onSelect: vi.fn(),
    label: undefined,
    statusMessage: '',
    organizationId: alphaOrder.organizationId,
    onCollect: vi.fn(),
    onPrint: vi.fn(),
    onReject: vi.fn(),
    ...overrides,
  };
}

function fillPresented() {
  fireEvent.change(screen.getByLabelText(labText('en', 'presentedPatientId')), {
    target: { value: presentedAlpha.patientId },
  });
  fireEvent.change(screen.getByLabelText(labText('en', 'presentedAccessionNo')), {
    target: { value: presentedAlpha.accessionNo },
  });
  fireEvent.change(screen.getByLabelText(labText('en', 'presentedAccessionIssuer')), {
    target: { value: presentedAlpha.accessionIssuer },
  });
}

function confirmBoth() {
  fireEvent.click(screen.getByLabelText(labText('en', 'confirmNames')));
  fireEvent.click(screen.getByLabelText(labText('en', 'confirmAccession')));
}

function fillSpecimen() {
  fireEvent.change(screen.getByLabelText(labText('en', 'specimenType')), {
    target: { value: 'SERUM' },
  });
  fireEvent.change(screen.getByLabelText(labText('en', 'siteCode')), { target: { value: 'MAIN' } });
  fireEvent.change(screen.getByLabelText(labText('en', 'collector')), {
    target: { value: 'synthetic.collector' },
  });
  fireEvent.change(screen.getByLabelText(labText('en', 'collectedAt')), {
    target: { value: '2026-03-09T09:00:00Z' },
  });
  fireEvent.change(screen.getByLabelText(labText('en', 'quality')), {
    target: { value: 'ACCEPTABLE' },
  });
}

function collectButton(): HTMLButtonElement {
  return screen.getByRole<HTMLButtonElement>('button', { name: labText('en', 'recordCollection') });
}

describe('CollectionDeskScreen verification gate', () => {
  it('asks the collector to select an order first', () => {
    wrap(<CollectionDeskScreen {...screenFor({ selected: undefined })} />);
    expect(screen.getByText(labText('en', 'selectPrompt'))).toBeTruthy();
    expect(screen.queryByLabelText(labText('en', 'patientIdentity'))).toBeNull();
  });

  it('blocks and explains a mismatch before any command is available', () => {
    const onCollect = vi.fn();
    wrap(<CollectionDeskScreen {...screenFor({ onCollect })} />);

    fillPresented();
    fireEvent.change(screen.getByLabelText(labText('en', 'presentedPatientId')), {
      target: { value: 'wrong-patient' },
    });
    expect(screen.getByTestId('match-status').textContent).toContain(
      labText('en', 'mismatchPatient'),
    );
    expect(screen.getByText(labText('en', 'verificationRequired'))).toBeTruthy();
    expect(collectButton().disabled).toBe(true);

    fillSpecimen();
    fireEvent.click(collectButton());
    expect(onCollect).not.toHaveBeenCalled();
  });

  it('still blocks when the two names have not been confirmed', () => {
    wrap(<CollectionDeskScreen {...screenFor()} />);
    fillPresented();
    expect(screen.getByTestId('match-status').textContent).toContain(labText('en', 'matchOk'));
    expect(collectButton().disabled).toBe(true);
    expect(screen.getByText(labText('en', 'verificationRequired'))).toBeTruthy();
  });

  it('still blocks when only one confirmation is checked', () => {
    wrap(<CollectionDeskScreen {...screenFor()} />);
    fillPresented();
    fireEvent.click(screen.getByLabelText(labText('en', 'confirmNames')));
    expect(collectButton().disabled).toBe(true);
    expect(screen.getByText(labText('en', 'verificationRequired'))).toBeTruthy();
  });

  it('enables the commands once the names and accession are confirmed', () => {
    wrap(<CollectionDeskScreen {...screenFor()} />);
    fillPresented();
    confirmBoth();
    expect(screen.queryByText(labText('en', 'verificationRequired'))).toBeNull();
    expect(collectButton().disabled).toBe(false);
  });

  it('makes the Find action a focusable native submit control with an observable result', () => {
    wrap(<CollectionDeskScreen {...screenFor()} />);
    const form = screen.getByRole('form', { name: labText('en', 'findOrder') });
    const input = within(form).getByLabelText(labText('en', 'search'));
    const submit = within(form).getByRole('button', { name: labText('en', 'search') });
    expect(submit).toHaveProperty('type', 'submit');

    input.focus();
    expect(document.activeElement).toBe(input);
    fireEvent.change(input, { target: { value: 'ACC-100' } });
    submit.focus();
    expect(document.activeElement).toBe(submit);

    // jsdom does not synthesise a native button's Enter activation; clicking the
    // focused submit control is the DOM stand-in for that browser behaviour.
    fireEvent.click(submit);
    expect(screen.getByTestId('find-status').textContent).toContain('ACC-100');
    expect(screen.queryByText(labText('en', 'noOrdersFound'))).toBeNull();
  });

  it('clears the typed identity when a different order is opened', () => {
    const other: CollectionOrder = { ...alphaOrder, id: 'accession-101', accessionNo: 'ACC-101' };
    const { rerender } = wrap(<CollectionDeskScreen {...screenFor()} />);
    fillPresented();
    expect(screen.getByLabelText(labText('en', 'presentedPatientId'))).toHaveProperty(
      'value',
      presentedAlpha.patientId,
    );

    const view = screenFor({ selected: other });
    rerender(
      <ThemeProvider>
        <I18nProvider initialLocale="en">
          <CollectionDeskScreen {...view} />
        </I18nProvider>
      </ThemeProvider>,
    );
    expect(screen.getByLabelText(labText('en', 'presentedPatientId'))).toHaveProperty('value', '');
  });
});

describe('CollectionDeskScreen commands', () => {
  function ready(overrides: Partial<CollectionDeskScreenProps> = {}) {
    const props = screenFor(overrides);
    wrap(<CollectionDeskScreen {...props} />);
    fillPresented();
    confirmBoth();
    return props;
  }

  it('records the full specimen collection and renders the page-supplied outcome', () => {
    const props = ready({ statusMessage: labText('en', 'collectionRecorded') });
    fillSpecimen();
    fireEvent.click(collectButton());
    expect(props.onCollect).toHaveBeenCalledWith(alphaOrder, {
      patientId: presentedAlpha.patientId,
      accessionNo: presentedAlpha.accessionNo,
      accessionIssuer: presentedAlpha.accessionIssuer,
      specimenType: 'SERUM',
      siteCode: 'MAIN',
      collector: 'synthetic.collector',
      collectedAt: '2026-03-09T09:00:00Z',
      quality: 'ACCEPTABLE',
      expectedVersion: alphaOrder.version,
    });
    expect(screen.getByTestId('collection-status').textContent).toContain(
      labText('en', 'collectionRecorded'),
    );
  });

  it('prints labels and shows the sheet when label data is supplied', () => {
    const props = ready({ label: labelAlpha });
    expect(screen.getByTestId('label-sheet')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: labText('en', 'printLabels') }));
    expect(props.onPrint).toHaveBeenCalledWith(alphaOrder);
  });

  it('rejects with the selected reason and renders the page-supplied outcome', () => {
    const props = ready({ statusMessage: labText('en', 'specimenRejected') });
    fireEvent.change(screen.getByLabelText(labText('en', 'rejectionReason')), {
      target: { value: 'haemolysed' },
    });
    fireEvent.click(screen.getByRole('button', { name: labText('en', 'rejectSpecimen') }));
    expect(props.onReject).toHaveBeenCalledWith(alphaOrder, {
      reason: 'haemolysed',
      detail: '',
      orderingClinician: 'synthetic.ordering.clinician',
      notifyPatient: false,
      expectedVersion: alphaOrder.version,
    });
    expect(screen.getByTestId('collection-status').textContent).toContain(
      labText('en', 'specimenRejected'),
    );
  });

  it('renders the two-name check in Arabic right-to-left layout', () => {
    wrap(<CollectionDeskScreen {...screenFor()} />, 'ar');
    expect(document.documentElement.dir).toBe('rtl');
    expect(screen.getByText('Synthetic Layla')).toBeTruthy();
    expect(screen.getByText('ليلى تجريبية')).toBeTruthy();
    expect(screen.getByRole('heading', { name: labText('ar', 'collectionDesk') })).toBeTruthy();
  });
});
