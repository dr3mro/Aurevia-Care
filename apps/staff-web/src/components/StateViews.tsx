import type { ReactNode } from 'react';
import type { TransportError } from '../api/transport';
import { useI18n } from '../i18n/I18nProvider';
import { useTheme } from '../theme/ThemeProvider';
import { useBreakpoint } from '../theme/useBreakpoint';
import { Button } from './Button';
import { stateViewText } from './stateViewMessages';

/** Stable inert action used when a state view is presented without callbacks. */
const noop = (): void => undefined;

/**
 * Shared tokenized surface with the correct announcement semantics.
 *
 * The `page` utility supplies the safe-area-aware logical padding and the
 * centred reading measure; the inline styles only centre the state content
 * (no dedicated state-panel class exists in `responsive.css`) and keep long
 * English and Arabic labels wrappable without a horizontal scroll.
 */
function StatePanel({ role, children }: { role: 'status' | 'alert'; children: ReactNode }) {
  const { tokens } = useTheme();
  const breakpoint = useBreakpoint();
  return (
    <section
      role={role}
      aria-live={role === 'alert' ? 'assertive' : 'polite'}
      className="page"
      data-breakpoint={breakpoint}
      style={{
        display: 'grid',
        placeItems: 'center',
        background: tokens.background,
        color: tokens.foreground,
        border: `1px solid ${tokens.border}`,
        maxInlineSize: '44rem',
        textAlign: 'start',
        minInlineSize: 0,
        overflowWrap: 'anywhere',
      }}
    >
      <div
        style={{
          display: 'grid',
          gap: tokens.spacing.lg,
          inlineSize: '100%',
          minInlineSize: 0,
          textAlign: 'start',
          overflowWrap: 'anywhere',
        }}
      >
        {children}
      </div>
    </section>
  );
}

/**
 * Wraps one state action in a logical touch target. The grid wrapper stretches
 * its native button across the row on phones and up to the 44 px coarse-pointer
 * floor via the shared `touch` class, without changing the button component.
 */
function StateAction({ children }: { children: ReactNode }) {
  return (
    <div className="touch" style={{ display: 'grid' }}>
      {children}
    </div>
  );
}

/** Stacks state actions on phones and keeps them inline from the medium band up. */
function StateActions({ children }: { children: ReactNode }) {
  const compact = useBreakpoint() === 'compact';
  return (
    <div className={compact ? 'stack' : 'cluster'} style={{ inlineSize: '100%' }}>
      {children}
    </div>
  );
}

/** Polite loading state; announces progress without moving focus. */
export function LoadingState() {
  const { locale } = useI18n();
  return (
    <StatePanel role="status">
      <h2>{stateViewText(locale, 'loading')}</h2>
    </StatePanel>
  );
}

/** Polite empty state that always offers the next action in text. */
export function EmptyState() {
  const { locale } = useI18n();
  return (
    <StatePanel role="status">
      <h2>{stateViewText(locale, 'empty')}</h2>
    </StatePanel>
  );
}

/** Props for the blocking error state. */
export interface ErrorStateProps {
  correlationId?: string | undefined;
  onRetry?: (() => void) | undefined;
}

/** Blocking error state: shows the correlation ID and an explicit Retry action. */
export function ErrorState({ correlationId, onRetry = noop }: ErrorStateProps) {
  const { locale } = useI18n();
  return (
    <StatePanel role="alert">
      <h2>{stateViewText(locale, 'error')}</h2>
      <p>{stateViewText(locale, 'errorDetail')}</p>
      <p>
        {stateViewText(locale, 'correlation')}:{' '}
        <code dir="ltr">{correlationId ?? stateViewText(locale, 'correlationUnavailable')}</code>
      </p>
      <StateActions>
        <StateAction>
          <Button onClick={onRetry}>{stateViewText(locale, 'retry')}</Button>
        </StateAction>
      </StateActions>
    </StatePanel>
  );
}

/** Blocking permission state with a plain, role-scoped explanation. */
export function ForbiddenState() {
  const { locale } = useI18n();
  return (
    <StatePanel role="alert">
      <h2>{stateViewText(locale, 'forbidden')}</h2>
      <p>{stateViewText(locale, 'forbiddenDetail')}</p>
    </StatePanel>
  );
}

/** Props for the stale-version conflict state. */
export interface ConflictStateProps {
  onCompare?: (() => void) | undefined;
  onReload?: (() => void) | undefined;
  onCopy?: (() => void) | undefined;
}

/** Conflict state: keeps typed work and offers compare, reload and copy actions. */
export function ConflictState({
  onCompare = noop,
  onReload = noop,
  onCopy = noop,
}: ConflictStateProps) {
  const { locale } = useI18n();
  return (
    <StatePanel role="alert">
      <h2>{stateViewText(locale, 'conflict')}</h2>
      <p>{stateViewText(locale, 'conflictDetail')}</p>
      <StateActions>
        <StateAction>
          <Button onClick={onCompare}>{stateViewText(locale, 'compare')}</Button>
        </StateAction>
        <StateAction>
          <Button onClick={onReload}>{stateViewText(locale, 'reload')}</Button>
        </StateAction>
        <StateAction>
          <Button onClick={onCopy}>{stateViewText(locale, 'copyMyChanges')}</Button>
        </StateAction>
      </StateActions>
    </StatePanel>
  );
}

/** Props for the expired-session state. */
export interface SessionExpiredStateProps {
  onSignIn?: (() => void) | undefined;
}

/** Session-expired state: a sign-in prompt that keeps the user's place. */
export function SessionExpiredState({ onSignIn = noop }: SessionExpiredStateProps) {
  const { locale } = useI18n();
  return (
    <StatePanel role="alert">
      <h2>{stateViewText(locale, 'sessionExpired')}</h2>
      <p>{stateViewText(locale, 'sessionExpiredDetail')}</p>
      <StateActions>
        <StateAction>
          <Button onClick={onSignIn}>{stateViewText(locale, 'signIn')}</Button>
        </StateAction>
      </StateActions>
    </StatePanel>
  );
}

/**
 * Maps a transport failure to the single blocking state a screen should show.
 * Response text is never used: only the typed kind and the correlation ID.
 */
export const StateView = {
  forTransportError(error: TransportError): ReactNode {
    switch (error.kind) {
      case 'session-expired':
        return <SessionExpiredState />;
      case 'forbidden':
        return <ForbiddenState />;
      case 'conflict':
      case 'precondition-required':
        return <ConflictState />;
      case 'csrf-missing':
      case 'not-found':
      case 'invalid':
      case 'rate-limited':
      case 'server':
      case 'offline':
      case 'timeout':
        return <ErrorState correlationId={error.correlationId} />;
    }
  },
};
