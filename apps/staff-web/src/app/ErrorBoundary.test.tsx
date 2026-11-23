import { fireEvent, render, screen } from '@testing-library/react';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { useState } from 'react';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { I18nProvider } from '../i18n/I18nProvider';
import { ThemeProvider } from '../theme/ThemeProvider';
import { clearRenderFailure, ErrorBoundary } from './ErrorBoundary';

const correlationId = '00000000-0000-0000-0000-0000000000bb';

afterEach(() => {
  vi.restoreAllMocks();
  clearRenderFailure();
});

function ThrowingChild({ fail }: { marker: string; fail?: boolean }): ReactNode {
  if (fail === false) {
    return <p>synthetic recovered child</p>;
  }
  const failure = new Error('MARKER-THROW-MESSAGE');
  failure.name = 'BoomFailure';
  throw failure;
}

/** Lets the test clear the failure cause outside the boundary before retrying. */
function RecoveryHarness() {
  const [fail, setFail] = useState(true);
  return (
    <>
      <button type="button" onClick={() => setFail(false)}>
        clear failure
      </button>
      <ErrorBoundary>
        <ThrowingChild fail={fail} marker="MARKER-PROP" />
      </ErrorBoundary>
    </>
  );
}

/** Forces a parent re-render without clearing the boundary's failure. */
function RerenderHarness() {
  const [tick, setTick] = useState(0);
  return (
    <>
      <button type="button" onClick={() => setTick(tick + 1)}>
        rerender
      </button>
      <ErrorBoundary>
        <ThrowingChild marker="MARKER-PROP" />
      </ErrorBoundary>
    </>
  );
}

/** Throws a non-Error value to exercise the defensive log-name fallback. */
function ThrowingStringChild(): ReactNode {
  const thrown: unknown = 'MARKER-NON-ERROR-VALUE';
  throw thrown;
}

describe('global ErrorBoundary', () => {
  it('passes healthy children through unchanged', () => {
    render(
      <ThemeProvider>
        <I18nProvider>
          <ErrorBoundary>
            <p>synthetic healthy child</p>
          </ErrorBoundary>
        </I18nProvider>
      </ThemeProvider>,
    );
    expect(screen.getByText('synthetic healthy child')).toBeTruthy();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('catches a throwing child, shows the correlation ID and logs only the ID and error name', () => {
    const randomUuid = vi.spyOn(globalThis.crypto, 'randomUUID').mockReturnValue(correlationId);
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
      (args) => typeof args[0] === 'string' && args[0].includes(correlationId),
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

  it('reuses one correlation ID across fallback re-renders and logs only once', () => {
    const randomUuid = vi.spyOn(globalThis.crypto, 'randomUUID').mockReturnValue(correlationId);
    const errorLog = vi.spyOn(console, 'error').mockImplementation(() => undefined);

    render(
      <ThemeProvider>
        <I18nProvider>
          <RerenderHarness />
        </I18nProvider>
      </ThemeProvider>,
    );
    expect(screen.getByRole('alert').textContent).toContain(correlationId);

    fireEvent.click(screen.getByRole('button', { name: 'rerender' }));

    expect(randomUuid).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('alert').textContent).toContain(correlationId);
    const ownCalls = errorLog.mock.calls.filter(
      (args) => typeof args[0] === 'string' && args[0].includes(correlationId),
    );
    expect(ownCalls).toHaveLength(1);
  });

  it('logs a safe class name and no thrown text when a non-Error value is thrown', () => {
    const errorLog = vi.spyOn(console, 'error').mockImplementation(() => undefined);

    render(
      <ThemeProvider>
        <I18nProvider>
          <ErrorBoundary>
            <ThrowingStringChild />
          </ErrorBoundary>
        </I18nProvider>
      </ThemeProvider>,
    );

    const renderedId = screen.getByRole('alert').querySelector('code')?.textContent ?? '';
    expect(renderedId).not.toBe('');
    const ownCalls = errorLog.mock.calls.filter(
      (args) => typeof args[0] === 'string' && args[0].includes(renderedId),
    );
    expect(ownCalls).toHaveLength(1);
    expect(ownCalls[0]?.[0]).toBe(`${renderedId} Error`);
    expect(ownCalls[0]?.[0]).not.toContain('MARKER-NON-ERROR-VALUE');
  });
});

/**
 * READY-07 owner decision (MEMORY.md): the global boundary is a function
 * component built on a reviewed, exactly-pinned dependency, not a React class.
 * The generic frontend scan only matches the literal `extends Component`, so
 * these assertions guard the actual decision, including aliased base classes.
 */
describe('error boundary implementation contract', () => {
  const source = readFileSync(
    join(dirname(fileURLToPath(import.meta.url)), 'ErrorBoundary.tsx'),
    'utf8',
  );

  it('is implemented without a class component', () => {
    expect(source).not.toMatch(/\bclass\s+[A-Z]\w*/);
  });

  it('delegates to the reviewed error-boundary dependency', () => {
    expect(source).toMatch(/from ['"]react-error-boundary['"]/);
  });

  it('pins the reviewed dependency to an exact version', () => {
    const manifest = JSON.parse(
      readFileSync(
        join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'package.json'),
        'utf8',
      ),
    ) as { dependencies: Record<string, string> };
    expect(manifest.dependencies['react-error-boundary']).toMatch(/^\d+\.\d+\.\d+$/);
  });
});
