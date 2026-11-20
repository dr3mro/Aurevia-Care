import { fireEvent, render, screen } from '@testing-library/react';
import { useState } from 'react';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { I18nProvider } from '../i18n/I18nProvider';
import { ThemeProvider } from '../theme/ThemeProvider';
import { ErrorBoundary } from './ErrorBoundary';

const correlationId = '00000000-0000-0000-0000-0000000000bb';

afterEach(() => {
  vi.restoreAllMocks();
});

function ThrowingChild({ marker, fail }: { marker: string; fail?: boolean }): ReactNode {
  if (fail === false) {
    return <p>synthetic recovered child</p>;
  }
  const failure = new Error('MARKER-THROW-MESSAGE');
  failure.name = 'BoomFailure';
  void marker;
  throw failure;
}

/** Lets the test clear the failure cause outside the boundary before retrying. */
function RecoveryHarness() {
  const [fail, setFail] = useState(true);
  return (
    <>
      <button type="button" onClick={() => setFail(false)}>clear failure</button>
      <ErrorBoundary>
        <ThrowingChild fail={fail} marker="MARKER-PROP" />
      </ErrorBoundary>
    </>
  );
}

describe('global ErrorBoundary', () => {
  it('passes healthy children through unchanged', () => {
    render(
      <ThemeProvider>
        <I18nProvider>
          <ErrorBoundary><p>synthetic healthy child</p></ErrorBoundary>
        </I18nProvider>
      </ThemeProvider>,
    );
    expect(screen.getByText('synthetic healthy child')).toBeTruthy();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('catches a throwing child, shows the correlation ID and logs only the ID and error name', () => {
    const randomUuid = vi
      .spyOn(globalThis.crypto, 'randomUUID')
      .mockReturnValue(correlationId);
    const errorLog = vi.spyOn(console, 'error').mockImplementation(() => undefined);

    render(
      <ThemeProvider>
        <I18nProvider>
          <ErrorBoundary>
            <ThrowingChild marker="MARKER-PROP" />
          </ErrorBoundary>
        </I18nProvider>
      </ThemeProvider>,
    );

    expect(randomUuid).toHaveBeenCalled();
    const alert = screen.getByRole('alert');
    expect(alert.textContent).toContain('Something went wrong');
    expect(alert.textContent).toContain(correlationId);
    expect(screen.getByRole('button', { name: 'Retry' })).toBeTruthy();

    // React logs its own render failure; isolate only this boundary's single
    // one-string call, which must never carry props, state or the error message.
    const ownCalls = errorLog.mock.calls.filter(
      (args) => typeof args[0] === 'string' && args[0]?.includes(correlationId),
    );
    expect(ownCalls).toHaveLength(1);
    expect(ownCalls[0]?.[0]).toBe(`${correlationId} BoomFailure`);
    expect(ownCalls[0]?.[0]).not.toContain('MARKER-THROW-MESSAGE');
    expect(ownCalls[0]?.[0]).not.toContain('MARKER-PROP');
  });

  it('lets an explicit Retry remount the children once the cause is cleared', () => {
    vi.spyOn(globalThis.crypto, 'randomUUID').mockReturnValue(correlationId);
    vi.spyOn(console, 'error').mockImplementation(() => undefined);

    render(
      <ThemeProvider>
        <I18nProvider>
          <RecoveryHarness />
        </I18nProvider>
      </ThemeProvider>,
    );
    expect(screen.getByRole('alert').textContent).toContain(correlationId);

    fireEvent.click(screen.getByRole('button', { name: 'clear failure' }));
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));

    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.getByText('synthetic recovered child')).toBeTruthy();
  });
});
