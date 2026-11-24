import { render, screen } from '@testing-library/react';
import { createElement } from 'react';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { clearRenderFailure, ErrorBoundary } from '../../../src/app/ErrorBoundary';
import { I18nProvider } from '../../../src/i18n/I18nProvider';
import { ThemeProvider } from '../../../src/theme/ThemeProvider';

// Synthetic markers only: no patient data, real identifiers or credentials.
// They ride in the thrown message and the child prop so the log assertion proves
// redaction instead of merely observing a quiet console.
const CORRELATION_ID = '00000000-0000-0000-0000-0000000000bb';
const THROW_MARKER = 'MARKER-THROW-MESSAGE';
const PROP_MARKER = 'MARKER-PROP';
const PATIENT_MARKER = 'MARKER-PATIENT-MRN-0042';

/** Child that throws a synthetic failure whose message carries every marker. */
function ThrowingChild({ marker }: { marker: string }): ReactNode {
  const failure = new Error(`${THROW_MARKER} ${marker} ${PATIENT_MARKER}`);
  failure.name = 'BoomFailure';
  throw failure;
}

/** The provider tree the installed shell wraps around its global boundary. */
function failingTree(): ReactNode {
  return createElement(
    ThemeProvider,
    null,
    createElement(
      I18nProvider,
      null,
      createElement(ErrorBoundary, null, createElement(ThrowingChild, { marker: PROP_MARKER })),
    ),
  );
}

afterEach(() => {
  vi.restoreAllMocks();
  clearRenderFailure();
});

describe('WEB-09 clause 5: global error boundary shows a correlation ID and logs without patient data', () => {
  it('showsCorrelationIdAndLogsOnlyIdAndErrorName', () => {
    const randomUuid = vi.spyOn(globalThis.crypto, 'randomUUID').mockReturnValue(CORRELATION_ID);
    const errorLog = vi.spyOn(console, 'error').mockImplementation(() => undefined);

    render(failingTree());

    expect(randomUuid).toHaveBeenCalledTimes(1);
    const alert = screen.getByRole('alert');
    expect(alert.textContent).toContain('Something went wrong');
    expect(alert.textContent).toContain(CORRELATION_ID);
    expect(screen.getByRole('button', { name: 'Retry' })).toBeTruthy();

    // React logs its own render failure with an Error object; isolate the
    // boundary's single one-string call by the opaque ID it prints.
    const ownCalls = errorLog.mock.calls.filter(
      (args) => typeof args[0] === 'string' && args[0].includes(CORRELATION_ID),
    );
    expect(ownCalls).toHaveLength(1);
    expect(ownCalls[0]?.[0]).toBe(`${CORRELATION_ID} BoomFailure`);
  });

  it('refusesToLogThrownMessageOrPatientData', () => {
    const randomUuid = vi.spyOn(globalThis.crypto, 'randomUUID').mockReturnValue(CORRELATION_ID);
    const errorLog = vi.spyOn(console, 'error').mockImplementation(() => undefined);

    const view = render(failingTree());
    view.rerender(failingTree());

    // The fallback re-renders, but one failure keeps one ID and one log line.
    expect(randomUuid).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('alert').textContent).toContain(CORRELATION_ID);

    const ownCalls = errorLog.mock.calls.filter(
      (args) => typeof args[0] === 'string' && args[0].includes(CORRELATION_ID),
    );
    expect(ownCalls).toHaveLength(1);
    expect(ownCalls[0]?.[0]).toBe(`${CORRELATION_ID} BoomFailure`);
    expect(ownCalls[0]?.[0]).not.toContain(THROW_MARKER);
    expect(ownCalls[0]?.[0]).not.toContain(PROP_MARKER);
    expect(ownCalls[0]?.[0]).not.toContain(PATIENT_MARKER);
  });
});
