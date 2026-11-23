import type { ReactElement } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { ActiveOrganizationProvider } from '../../api/activeOrganization';
import { TransportError } from '../../api/transport';
import { stateViewText } from '../../components/stateViewMessages';
import { I18nProvider } from '../../i18n/I18nProvider';
import type { Locale } from '../../i18n/messages';
import { ThemeProvider } from '../../theme/ThemeProvider';
import type {
  ApprovalPanelProps,
  ReviewScreenProps,
  WorklistScreenProps,
} from './DuplicatesScreen';
import { ApprovalPanel, ReviewScreen, WorklistScreen } from './DuplicatesScreen';
import {
  alphaOrganizationId,
  depsResolved,
  depsUnambiguous,
  depsUnresolved,
  detailMinimal,
  reviewOpen,
  syntheticConsolidation,
  syntheticDetail,
  syntheticPair,
  syntheticPairTwo,
} from './fixtures';
import { must } from '../../../tests/must';

function wrap(ui: ReactElement, locale: Locale = 'en') {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <ActiveOrganizationProvider value={{ organizationId: alphaOrganizationId }}>
          {ui}
        </ActiveOrganizationProvider>
      </I18nProvider>
    </ThemeProvider>,
  );
}

const noop = () => undefined;

describe('WorklistScreen', () => {
  const props: WorklistScreenProps = {
    pairs: [syntheticPair, syntheticPairTwo],
    locale: 'en',
    onSelect: noop,
  };

  it('renders an accessible table keyed by the opaque pair ids', () => {
    wrap(<WorklistScreen {...props} />);

    const table = screen.getByRole('table', { name: 'Duplicate review worklist' });
    expect(
      within(table)
        .getAllByRole('columnheader')
        .map((cell) => cell.textContent),
    ).toEqual(['Surviving identity', 'Duplicate identity', 'Action']);
    expect(within(table).getByText('patient-survivor')).toBeTruthy();
    expect(within(table).getByText('patient-duplicate')).toBeTruthy();
    expect(
      within(table).getByRole('button', {
        name: 'Review pair patient-survivor and patient-duplicate',
      }),
    ).toBeTruthy();
  });

  it('isolates a mixed-script name and selects the chosen pair', () => {
    const onSelect = vi.fn();
    wrap(<WorklistScreen {...props} onSelect={onSelect} />);

    const arabicName = screen.getByText('ليلى حداد');
    expect(arabicName.tagName).toBe('BDI');
    expect(arabicName.getAttribute('dir')).toBe('auto');
    expect(arabicName.getAttribute('lang')).toBe('ar');

    fireEvent.click(
      screen.getByRole('button', {
        name: 'Review pair patient-survivor and patient-duplicate',
      }),
    );
    expect(onSelect).toHaveBeenCalledWith(syntheticPair);
  });

  it('renders Arabic labels in right-to-left mode', () => {
    wrap(<WorklistScreen {...props} locale="ar" />, 'ar');

    expect(document.documentElement.dir).toBe('rtl');
    expect(screen.getByRole('table', { name: 'قائمة مراجعة التكرارات' })).toBeTruthy();
    expect(
      screen.getByRole('button', {
        name: 'مراجعة الزوج patient-survivor وpatient-duplicate',
      }),
    ).toBeTruthy();
  });
});

describe('ReviewScreen', () => {
  it('shows both identities with conflicting demographics, dependencies and the dry run', () => {
    const props: ReviewScreenProps = { detail: detailWithDeps(), locale: 'en' };
    wrap(<ReviewScreen {...props} />);

    expect(screen.getByRole('region', { name: 'Surviving record' })).toBeTruthy();
    expect(screen.getByRole('region', { name: 'Duplicate record' })).toBeTruthy();
    expect(screen.getByText('MRN-1001@SYNTHETIC')).toBeTruthy();
    expect(screen.getByText('May 12, 1990')).toBeTruthy();
    expect(screen.getByText('Not disclosed')).toBeTruthy();

    const conflicts = screen.getByRole('table', { name: 'Conflicting demographics' });
    expect(within(conflicts).getByText('survivorValue')).toBeTruthy();
    expect(within(conflicts).getByText('duplicateValue')).toBeTruthy();

    const deps = screen.getByRole('list');
    expect(deps.textContent).toContain('order-9');
    expect(deps.textContent).toContain('Ambiguous');
    expect(screen.getByText(/Dry run: move 1 chart visibility association/)).toBeTruthy();
  });

  it('renders explicit empty states when no conflicts or dependencies exist', () => {
    wrap(<ReviewScreen detail={{ ...detailMinimal, dependencies: [] }} locale="en" />);

    expect(screen.getByText('No conflicting demographics recorded.')).toBeTruthy();
    expect(screen.getByText('No dependent orders, links or grants.')).toBeTruthy();
  });

  it('labels resolved and unambiguous dependencies without blocking text', () => {
    wrap(
      <ReviewScreen
        detail={{ ...detailMinimal, dependencies: [...depsResolved, ...depsUnambiguous] }}
        locale="en"
      />,
    );

    const deps = screen.getAllByRole('listitem');
    expect(must(deps[0]).textContent).toContain('Patient link');
    expect(must(deps[0]).textContent).toContain('Resolved');
    expect(must(deps[1]).textContent).toContain('Grant');
    expect(must(deps[1]).textContent).toContain('Unresolved');
  });

  it('renders the review detail in Arabic under RTL', () => {
    wrap(<ReviewScreen detail={syntheticDetail} locale="ar" />, 'ar');

    expect(document.documentElement.dir).toBe('rtl');
    expect(screen.getByRole('region', { name: 'الهويات جنبًا إلى جنب' })).toBeTruthy();
  });
});

// Keep the conflict values synthetic and explicit in one place for the tests.
function detailWithDeps() {
  const detail = syntheticDetail;
  return {
    ...detail,
    conflicts: [
      { field: 'survivorValue', survivorValue: 'Layla Haddad', duplicateValue: 'ليلى حداد' },
      { field: 'duplicateValue', survivorValue: 'en', duplicateValue: 'ar' },
    ],
    dependencies: depsUnresolved,
  };
}

function panelProps(overrides: Partial<ApprovalPanelProps> = {}): ApprovalPanelProps {
  return {
    locale: 'en',
    detail: syntheticDetail,
    review: reviewOpen,
    reason: '',
    confirmed: [],
    alternateProcess: '',
    errors: {},
    notice: undefined,
    openError: undefined,
    approvalError: undefined,
    approved: undefined,
    onOpenReview: noop,
    onReasonChange: noop,
    onToggleReference: noop,
    onAlternateChange: noop,
    onApprove: noop,
    ...overrides,
  };
}

describe('ApprovalPanel', () => {
  it('offers only the open action before a ticket exists', () => {
    const onOpenReview = vi.fn();
    wrap(<ApprovalPanel {...panelProps({ review: null, onOpenReview })} />);

    expect(screen.getByRole('button', { name: 'Open review' })).toBeTruthy();
    expect(screen.getByText('Open a review before approving.')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Approve consolidation' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Open review' }));
    expect(onOpenReview).toHaveBeenCalled();
  });

  it('maps a failed open to the shared error state', () => {
    wrap(
      <ApprovalPanel
        {...panelProps({
          review: null,
          openError: new TransportError('conflict', {
            correlationId: 'corr-open',
            code: 'CONFLICT',
            retryAfterSeconds: undefined,
          }),
        })}
      />,
    );

    expect(screen.getByText(stateViewText('en', 'conflict'))).toBeTruthy();
  });

  it('shows a single refusal notice, an approval error and success together', () => {
    wrap(
      <ApprovalPanel
        {...panelProps({
          notice: 'refusalSecondReviewer',
          approved: syntheticConsolidation,
          approvalError: new TransportError('conflict', {
            correlationId: 'corr-approve',
            code: 'SECOND_REVIEWER_REQUIRED',
            retryAfterSeconds: undefined,
          }),
        })}
      />,
    );

    expect(
      screen.getAllByText('Refused: a different authorized reviewer must approve this request.'),
    ).toHaveLength(1);
    expect(screen.getByText('Consolidation approved and chart visibility moved.')).toBeTruthy();
    expect(screen.getByText(stateViewText('en', 'conflict'))).toBeTruthy();
  });

  it('links both validation errors to their fields and keeps the typed values', () => {
    wrap(
      <ApprovalPanel
        {...panelProps({
          reason: 'x',
          alternateProcess: 'BAD CODE',
          errors: { reason: 'reason', confirmation: 'confirmation' },
        })}
      />,
    );

    expect(screen.getByLabelText('Reason for consolidation').getAttribute('aria-invalid')).toBe(
      'true',
    );
    expect(
      screen.getByLabelText('Approved alternate-process code').getAttribute('aria-invalid'),
    ).toBe('true');
    expect(screen.getByLabelText<HTMLInputElement>('Reason for consolidation').value).toBe('x');
  });

  it('toggles identifier checkboxes and activates approval with a native submit', () => {
    const onToggleReference = vi.fn();
    const onApprove = vi.fn();
    wrap(<ApprovalPanel {...panelProps({ onToggleReference, onApprove })} />);

    fireEvent.click(screen.getByLabelText('MRN-1001@SYNTHETIC'));
    expect(onToggleReference).toHaveBeenCalledWith('MRN-1001@SYNTHETIC');
    expect(screen.getByText(/MRN-1002@SYNTHETIC/)).toBeTruthy();

    const submit = screen.getByRole<HTMLButtonElement>('button', { name: 'Approve consolidation' });
    expect(submit.type).toBe('submit');
    fireEvent.submit(must(submit.closest<HTMLElement>('form')));
    expect(onApprove).toHaveBeenCalledWith(reviewOpen);
  });

  it('renders the approval form in Arabic under RTL', () => {
    wrap(<ApprovalPanel {...panelProps({ locale: 'ar' })} />, 'ar');

    expect(document.documentElement.dir).toBe('rtl');
    expect(screen.getByRole('button', { name: 'الموافقة على الدمج' })).toBeTruthy();
  });
});
