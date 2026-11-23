import type { ReactElement } from 'react';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { Locale } from '../../../i18n/messages';
import { I18nProvider } from '../../../i18n/I18nProvider';
import { ThemeProvider } from '../../../theme/ThemeProvider';
import type { DeliveryMode } from '../entry/domain';
import {
  ambiguousSend,
  failedSend,
  syntheticArabicPartnerName,
  syntheticPartnerName,
  syntheticPrintTimeline,
  syntheticTimeline,
  unsafeFailedSend,
} from './fixtures';
import { confirmKey, milestoneKey, receivesKey } from './domain';
import { deliveryText } from './labels';
import { DeliveryChoice } from './DeliveryChoice';
import type { DeliveryChoiceProps } from './DeliveryChoice';

/** Render under the providers the feature relies on, in the chosen language. */
function wrap(ui: ReactElement, locale: Locale = 'en') {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>{ui}</I18nProvider>
    </ThemeProvider>,
  );
}

/** Render the step with synthetic data and overridable callbacks. */
function renderChoice(overrides: Partial<DeliveryChoiceProps> = {}, locale: Locale = 'en') {
  const props: DeliveryChoiceProps = {
    organizationId: 'org-1',
    partnerName: syntheticPartnerName,
    mode: 'electronic',
    result: { state: 'idle' },
    onConfirm: vi.fn(),
    onChangeMode: vi.fn(),
    onRetry: vi.fn(),
    onReconcile: vi.fn(),
    onOpenPrintPreview: vi.fn(),
    ...overrides,
  };
  return { ...wrap(<DeliveryChoice {...props} />, locale), props };
}

const modes: readonly DeliveryMode[] = ['electronic', 'print', 'both'];

describe('DeliveryChoice modes', () => {
  it.each(modes)(
    'shows the partner, what the patient receives and a worded confirm for %s',
    (mode) => {
      renderChoice({ mode });
      expect(screen.getByText(syntheticPartnerName)).toBeTruthy();
      expect(screen.getByText(deliveryText('en', receivesKey[mode]))).toBeTruthy();
      expect(
        screen.getByRole('button', { name: deliveryText('en', confirmKey[mode]) }),
      ).toBeTruthy();
    },
  );

  it('submits the currently selected mode through the confirm callback', () => {
    const { props } = renderChoice({ mode: 'both' });
    fireEvent.click(screen.getByRole('button', { name: deliveryText('en', confirmKey.both) }));
    expect(props.onConfirm).toHaveBeenCalledTimes(1);
    expect(props.onConfirm).toHaveBeenCalledWith('both');
  });

  it('offers all three modes and reports a mode change without changing data itself', () => {
    const { props } = renderChoice({ mode: 'electronic' });
    const radios = screen.getAllByRole('radio');
    expect(radios).toHaveLength(modes.length);
    fireEvent.click(
      screen.getByRole('radio', { name: new RegExp(deliveryText('en', 'modePrint')) }),
    );
    expect(props.onChangeMode).toHaveBeenCalledTimes(1);
    expect(props.onChangeMode).toHaveBeenCalledWith('print');
    expect(props.onConfirm).not.toHaveBeenCalled();
  });

  it('labels a partner name for its own script, not the interface locale', () => {
    renderChoice({ partnerName: syntheticArabicPartnerName });
    expect(screen.getByText(syntheticArabicPartnerName).getAttribute('lang')).toBe('ar');
    expect(screen.getByText(syntheticArabicPartnerName).getAttribute('dir')).toBe('auto');
  });
});

describe('DeliveryChoice after placing', () => {
  it('shows a sending status and hides the confirm while the send is in flight', () => {
    renderChoice({ result: { state: 'sending' } });
    expect(screen.getByRole('status').textContent).toContain(deliveryText('en', 'sending'));
    expect(screen.queryByRole('button', { name: /^Confirm:/ })).toBeNull();
  });

  it.each([
    [syntheticTimeline, 'electronic'],
    [syntheticPrintTimeline, 'print'],
  ] as const)('shows the recorded delivery timeline after placing (%s)', (timeline, mode) => {
    renderChoice({ mode, result: { state: 'placed', timeline } });
    const region = screen.getByRole('region', { name: deliveryText('en', 'timelineTitle') });
    const steps = within(region).getAllByRole('listitem');
    expect(steps).toHaveLength(timeline.length);
    expect(steps.map((step) => step.textContent)).toEqual(
      timeline.map((milestone) => deliveryText('en', milestoneKey[milestone])),
    );
    expect(screen.queryByRole('button', { name: /^Confirm:/ })).toBeNull();
  });
});

describe('DeliveryChoice send recovery', () => {
  it('shows a clear failure with retry and print instead, and opens the preview through the callback', () => {
    const { props } = renderChoice({ result: failedSend });
    const alert = screen.getByRole('alert');
    expect(alert.textContent).toContain(deliveryText('en', 'sendFailedTitle'));
    expect(alert.textContent).toContain(deliveryText('en', 'sendFailedDetail'));
    expect(alert.textContent).toContain('SYN-CORR-42');
    fireEvent.click(screen.getByRole('button', { name: deliveryText('en', 'retry') }));
    expect(props.onRetry).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole('button', { name: deliveryText('en', 'printInstead') }));
    expect(props.onOpenPrintPreview).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('button', { name: /^Confirm:/ })).toBeNull();
  });

  it('omits the reference line when the failure carries no correlation id', () => {
    renderChoice({ result: { state: 'failed', retrySafe: true } });
    expect(screen.queryByText(deliveryText('en', 'reference'))).toBeNull();
    expect(screen.getByRole('button', { name: deliveryText('en', 'retry') })).toBeTruthy();
  });

  it('withholds retry for a failure that is not safe to resend, still offering print instead', () => {
    const { props } = renderChoice({ result: unsafeFailedSend });
    expect(screen.getByText(deliveryText('en', 'retryUnavailable'))).toBeTruthy();
    expect(screen.queryByRole('button', { name: deliveryText('en', 'retry') })).toBeNull();
    expect(screen.getByRole('button', { name: deliveryText('en', 'printInstead') })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: deliveryText('en', 'printInstead') }));
    expect(props.onOpenPrintPreview).toHaveBeenCalledTimes(1);
    expect(props.onRetry).not.toHaveBeenCalled();
  });

  it('shows reconcile and no resend button for an ambiguous send', () => {
    const { props } = renderChoice({ result: ambiguousSend });
    const alert = screen.getByRole('alert');
    expect(alert.textContent).toContain(deliveryText('en', 'sendAmbiguousTitle'));
    expect(alert.textContent).toContain(deliveryText('en', 'sendAmbiguousDetail'));
    expect(alert.textContent).toContain('SYN-CORR-43');
    expect(screen.getByRole('button', { name: deliveryText('en', 'reconcile') })).toBeTruthy();
    expect(screen.queryByRole('button', { name: deliveryText('en', 'retry') })).toBeNull();
    expect(screen.queryByRole('button', { name: /resend/i })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: deliveryText('en', 'reconcile') }));
    expect(props.onReconcile).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole('button', { name: deliveryText('en', 'printInstead') }));
    expect(props.onOpenPrintPreview).toHaveBeenCalledTimes(1);
    expect(props.onRetry).not.toHaveBeenCalled();
  });
});

describe('DeliveryChoice localization', () => {
  it('renders the choice in English with left-to-right direction', () => {
    renderChoice({ mode: 'both' });
    expect(document.documentElement.dir).toBe('ltr');
    expect(document.documentElement.lang).toBe('en');
    expect(
      screen.getByRole('heading', { level: 2, name: deliveryText('en', 'title') }),
    ).toBeTruthy();
    expect(screen.getByRole('button', { name: deliveryText('en', confirmKey.both) })).toBeTruthy();
  });

  it('renders an Arabic right-to-left choice with translated modes and confirm', () => {
    renderChoice({ mode: 'print', partnerName: syntheticArabicPartnerName }, 'ar');
    expect(document.documentElement.dir).toBe('rtl');
    expect(document.documentElement.lang).toBe('ar');
    expect(
      screen.getByRole('heading', { level: 2, name: deliveryText('ar', 'title') }),
    ).toBeTruthy();
    expect(screen.getByText(deliveryText('ar', receivesKey.print))).toBeTruthy();
    expect(screen.getByRole('button', { name: deliveryText('ar', confirmKey.print) })).toBeTruthy();
    expect(screen.getByText(syntheticArabicPartnerName).getAttribute('lang')).toBe('ar');
  });

  it('shows the ambiguous recovery message in Arabic', () => {
    renderChoice({ result: ambiguousSend, partnerName: syntheticArabicPartnerName }, 'ar');
    expect(screen.getByRole('alert').textContent).toContain(
      deliveryText('ar', 'sendAmbiguousTitle'),
    );
    expect(screen.getByRole('button', { name: deliveryText('ar', 'reconcile') })).toBeTruthy();
    expect(screen.getByRole('button', { name: deliveryText('ar', 'printInstead') })).toBeTruthy();
    expect(screen.queryByRole('button', { name: deliveryText('ar', 'retry') })).toBeNull();
  });
});
