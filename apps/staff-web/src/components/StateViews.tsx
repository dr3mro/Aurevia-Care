import type { ReactNode } from 'react';
import type { TransportError } from '../api/transport';
import { useI18n } from '../i18n/I18nProvider';
import { useTheme } from '../theme/ThemeProvider';
import { Button } from './Button';
import { stateViewText } from './stateViewMessages';

/** Stable inert action used when a state view is presented without callbacks. */
const noop = (): void => undefined;

/** Shared tokenized surface with the correct announcement semantics. */
function StatePanel({
  role,
  children,
}: {
  role: 'status' | 'alert';
  children: ReactNode;
}) {
  const { tokens } = useTheme();
  return (
    <section
      role={role}
      aria-live={role === 'alert' ? 'assertive' : 'polite'}
      style={{
        background: tokens.background,
        color: tokens.foreground,
        border: `1px solid ${tokens.border}`,
        padding: tokens.spacing.lg,
      }}
    >
      {children}
    </section>
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
        <code dir="ltr">
          {correlationId ?? stateViewText(locale, 'correlationUnavailable')}
        </code>
      </p>
      <Button onClick={onRetry}>{stateViewText(locale, 'retry')}</Button>
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
      <div>
        <Button onClick={onCompare}>{stateViewText(locale, 'compare')}</Button>{' '}
        <Button onClick={onReload}>{stateViewText(locale, 'reload')}</Button>{' '}
        <Button onClick={onCopy}>{stateViewText(locale, 'copyMyChanges')}</Button>
      </div>
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
      <Button onClick={onSignIn}>{stateViewText(locale, 'signIn')}</Button>
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
      default:
        return <ErrorState correlationId={error.correlationId} />;
    }
  },
};
