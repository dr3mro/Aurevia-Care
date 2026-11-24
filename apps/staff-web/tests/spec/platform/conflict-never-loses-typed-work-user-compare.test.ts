/**
 * WEB-09 clause 3 evidence, staff web: a stale-version conflict never discards
 * the user's typed work and offers compare, reload and copy recovery actions.
 *
 * Synthetic fixtures only. This file is `.ts` (not `.tsx`), so React elements
 * are built with `createElement`; the assertions still exercise the real
 * `NoteEditor`, `ConflictState`, `StateView` and `TransportError` boundaries in
 * jsdom under the production providers. No application endpoint is contacted.
 */
import { createElement } from 'react';
import type { ReactElement, ReactNode } from 'react';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { TransportError } from '../../../src/api/transport';
import { StateView } from '../../../src/components/StateViews';
import { stateViewText } from '../../../src/components/stateViewMessages';
import type { NoteRevision, SoapContent } from '../../../src/features/chart/domain';
import { NoteEditor } from '../../../src/features/chart/NoteEditor';
import { chartText } from '../../../src/features/chart/messages';
import { syntheticFacility, syntheticInstant } from '../../../src/fixtures/synthetic';
import { I18nProvider } from '../../../src/i18n/I18nProvider';
import { ThemeProvider } from '../../../src/theme/ThemeProvider';

/** Empty SOAP content for a fresh synthetic draft. */
const emptySoap: SoapContent = {
  subjective: '',
  objective: '',
  assessment: '',
  plan: '',
};

/**
 * Build an editable synthetic draft revision. Identifiers are deliberately
 * synthetic; the tenant and save time come from the shared synthetic fixtures.
 * A `draft` state keeps the SOAP fields editable so typed work can be observed.
 */
function draftRevision(content: SoapContent = emptySoap): NoteRevision {
  return {
    organizationId: syntheticFacility.organizationId,
    id: 'SYN-NOTE-42',
    chartLocalId: 'SYN-CHART-42',
    patientLocalId: 'SYN-PATIENT-42',
    encounterLocalId: 'SYN-ENCOUNTER-42',
    version: 1,
    kind: 'original',
    state: 'draft',
    content,
    templateVersion: '3',
    savedAt: syntheticInstant,
  };
}

/** Mount an element under the same providers the staff shell uses. */
function withProviders(child: ReactNode): ReactElement {
  const i18nProps = { initialLocale: 'en' as const, children: child };
  return createElement(ThemeProvider, null, createElement(I18nProvider, i18nProps));
}

/** The three conflict recovery actions in the order they are offered. */
const recoveryKeys = ['compare', 'reload', 'copyMyChanges'] as const;

describe('WEB-09 clause 3 — a conflict never loses typed work', () => {
  it('keepsTypedWorkAndOffersCompareReloadCopyOnConflict', () => {
    const typedText = 'SYN-TYPED-SUBJECTIVE-42';
    const onContentChange = vi.fn();
    const onCompare = vi.fn();
    const onReload = vi.fn();
    const onCopy = vi.fn();

    // Start on a clean editable draft, then simulate the parent owning the typed
    // text and a stale save setting the conflict flag. The textarea is controlled
    // by revision.content, so the rerender is the real hand-off being tested.
    const view = render(
      withProviders(
        createElement(NoteEditor, {
          revision: draftRevision(),
          history: [],
          locale: 'en',
          onContentChange,
          onSign: vi.fn(),
          onAmend: vi.fn(),
          onMarkEnteredInError: vi.fn(),
          conflict: false,
        }),
      ),
    );

    const field = screen.getByRole<HTMLTextAreaElement>('textbox', {
      name: chartText('en', 'subjective'),
    });
    expect(field.readOnly).toBe(false);
    fireEvent.change(field, { target: { value: typedText } });
    expect(onContentChange).toHaveBeenCalledTimes(1);
    expect(onContentChange).toHaveBeenCalledWith('subjective', typedText);

    view.rerender(
      withProviders(
        createElement(NoteEditor, {
          revision: draftRevision({ ...emptySoap, subjective: typedText }),
          history: [],
          locale: 'en',
          onContentChange,
          onSign: vi.fn(),
          onAmend: vi.fn(),
          onMarkEnteredInError: vi.fn(),
          conflict: true,
          onCompare,
          onReload,
          onCopy,
        }),
      ),
    );

    // The same field still shows the typed work once the conflict is on screen.
    const keptField = screen.getByRole<HTMLTextAreaElement>('textbox', {
      name: chartText('en', 'subjective'),
    });
    expect(keptField.value).toBe(typedText);

    const noteCard = screen.getByRole('region', { name: chartText('en', 'clinicalNote') });
    expect(noteCard.querySelector('[data-part="conflict"]')).toBeTruthy();
    const conflictSurface = within(noteCard).getByRole('alert');
    expect(
      within(conflictSurface).getByRole('heading', { name: stateViewText('en', 'conflict') }),
    ).toBeTruthy();
    expect(within(conflictSurface).getByText(stateViewText('en', 'conflictDetail'))).toBeTruthy();

    const recoveryActions = [
      ['compare', onCompare],
      ['reload', onReload],
      ['copyMyChanges', onCopy],
    ] as const;
    for (const [key, spy] of recoveryActions) {
      const button = within(conflictSurface).getByRole<HTMLButtonElement>('button', {
        name: stateViewText('en', key),
      });
      expect(button.disabled).toBe(false);
      fireEvent.click(button);
      expect(spy).toHaveBeenCalledTimes(1);
    }
  });

  it('doesNotOfferConflictRecoveryForANonConflictFailure', () => {
    const onRetry = vi.fn();
    const onCompare = vi.fn();
    const onReload = vi.fn();
    const onCopy = vi.fn();
    const serverError = new TransportError('server', {
      correlationId: 'SYN-CORR-42',
      code: undefined,
      retryAfterSeconds: undefined,
    });

    const { unmount } = render(
      withProviders(
        StateView.forTransportError(serverError, { onRetry, onCompare, onReload, onCopy }),
      ),
    );

    // A server failure is an error state with Retry; none of the conflict
    // recovery actions is offered, even though handlers were supplied.
    expect(screen.getByRole('heading', { name: stateViewText('en', 'error') })).toBeTruthy();
    const retry = screen.getByRole('button', { name: stateViewText('en', 'retry') });
    expect(retry).toBeTruthy();
    expect(screen.queryByRole('heading', { name: stateViewText('en', 'conflict') })).toBeNull();
    for (const key of recoveryKeys) {
      expect(screen.queryByRole('button', { name: stateViewText('en', key) })).toBeNull();
    }
    fireEvent.click(retry);
    expect(onRetry).toHaveBeenCalledTimes(1);
    expect(onCompare).not.toHaveBeenCalled();
    expect(onReload).not.toHaveBeenCalled();
    expect(onCopy).not.toHaveBeenCalled();
    unmount();

    // With no stale save, the note editor renders no conflict surface at all.
    const typedWithoutConflict = 'SYN-TYPED-NO-CONFLICT-42';
    const { container } = render(
      withProviders(
        createElement(NoteEditor, {
          revision: draftRevision({ ...emptySoap, subjective: typedWithoutConflict }),
          history: [],
          locale: 'en',
          onContentChange: vi.fn(),
          onSign: vi.fn(),
          onAmend: vi.fn(),
          onMarkEnteredInError: vi.fn(),
          conflict: false,
        }),
      ),
    );
    expect(container.querySelector('[data-part="conflict"]')).toBeNull();
    expect(screen.queryByRole('heading', { name: stateViewText('en', 'conflict') })).toBeNull();
    for (const key of recoveryKeys) {
      expect(screen.queryByRole('button', { name: stateViewText('en', key) })).toBeNull();
    }
    expect(
      screen.getByRole<HTMLTextAreaElement>('textbox', { name: chartText('en', 'subjective') })
        .value,
    ).toBe(typedWithoutConflict);
  });

  it('keepsConflictRecoveryButtonsInertButReachableWithoutHandlers', () => {
    const typedWithoutHandlers = 'SYN-TYPED-NO-HANDLERS-42';
    render(
      withProviders(
        createElement(NoteEditor, {
          revision: draftRevision({ ...emptySoap, subjective: typedWithoutHandlers }),
          history: [],
          locale: 'en',
          onContentChange: vi.fn(),
          onSign: vi.fn(),
          onAmend: vi.fn(),
          onMarkEnteredInError: vi.fn(),
          conflict: true,
        }),
      ),
    );

    // Defaulted no-op handlers keep the controls keyboard reachable and safe.
    const noteCard = screen.getByRole('region', { name: chartText('en', 'clinicalNote') });
    for (const key of recoveryKeys) {
      const button = within(noteCard).getByRole<HTMLButtonElement>('button', {
        name: stateViewText('en', key),
      });
      expect(button.disabled).toBe(false);
      expect(() => {
        fireEvent.click(button);
      }).not.toThrow();
    }
    expect(
      screen.getByRole<HTMLTextAreaElement>('textbox', { name: chartText('en', 'subjective') })
        .value,
    ).toBe(typedWithoutHandlers);
  });
});
