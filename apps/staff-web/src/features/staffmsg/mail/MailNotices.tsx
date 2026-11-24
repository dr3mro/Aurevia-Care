import { Button } from '../../../components/Button';
import { StateView } from '../../../components/StateViews';
import type { StateViewActions } from '../../../components/StateViews';
import type { Locale } from '../../../i18n/messages';
import type { TransportError } from '../../../api/transport';
import { mailText } from './labels';
import type { ComposeDraft, MailOverviewView } from './types';

/** Props for the inline status, command error and stale-draft recovery panels. */
export interface MailNoticesProps {
  readonly locale: Locale;
  readonly notice: string | undefined;
  readonly error: TransportError | undefined;
  readonly actions: StateViewActions & { onRetry: () => void };
  readonly comparing: boolean;
  readonly copied: ComposeDraft | undefined;
  readonly draft: ComposeDraft;
  readonly view: MailOverviewView;
}

/**
 * Render command feedback beside the editor rather than instead of it. A stale
 * draft keeps the typed text in a working copy and in a comparison panel, so
 * reload or a failed retry can never silently discard the author's work.
 */
export function MailNotices({
  locale,
  notice,
  error,
  actions,
  comparing,
  copied,
  draft,
  view,
}: MailNoticesProps) {
  return (
    <>
      {notice === undefined ? null : <p role="status">{notice}</p>}
      {error === undefined ? null : (
        <div data-testid="mail-command-error">
          {StateView.forTransportError(error, actions)}
          <Button onClick={actions.onRetry}>{mailText(locale, 'retryCommand')}</Button>
        </div>
      )}
      {comparing ? (
        <section data-testid="mail-compare" className="stack">
          <h3>{mailText(locale, 'compareTitle')}</h3>
          <b>{mailText(locale, 'yourValues')}</b>
          <pre data-testid="mail-compare-typed">{JSON.stringify(draft)}</pre>
          <b>{mailText(locale, 'storedValues')}</b>
          <pre>{JSON.stringify(view)}</pre>
        </section>
      ) : null}
      {copied === undefined ? null : (
        <section data-testid="mail-working-copy" className="stack">
          <h3>{mailText(locale, 'workingCopy')}</h3>
          <p>{mailText(locale, 'copyNotice')}</p>
          <pre data-testid="mail-working-copy-typed">{JSON.stringify(copied)}</pre>
        </section>
      )}
    </>
  );
}
