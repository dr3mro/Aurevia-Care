import { Button } from '../../../components/Button';
import { MixedScriptText } from '../../../components/MixedScriptText';
import { useI18n } from '../../../i18n/I18nProvider';
import { useTheme } from '../../../theme/ThemeProvider';
import type { DeliveryMode } from '../entry/domain';
import {
  canRetry,
  confirmKey,
  deliveryModes,
  milestoneKey,
  modeLabelKey,
  needsRecovery,
  partnerLang,
  receivesKey,
} from './domain';
import type { DeliveryMilestone, SendResult } from './domain';
import { deliveryText } from './labels';
import type { DeliveryMessageKey } from './labels';

/** A line from this feature's bilingual bundle for the active locale. */
type Line = (key: DeliveryMessageKey) => string;

/** Props for the final delivery step of order entry. All data is synthetic. */
export interface DeliveryChoiceProps {
  organizationId: string;
  /** Partner display name, isolated by script rather than treated as a label. */
  partnerName: string;
  /** Mode captured by the order entry draft. */
  mode: DeliveryMode;
  /** Send outcome supplied by the owning container from the delivery API. */
  result: SendResult;
  /** Final confirm; the container places the order with this mode. */
  onConfirm: (mode: DeliveryMode) => void;
  /** Let the clinician review the three modes before confirming. */
  onChangeMode: (mode: DeliveryMode) => void;
  /** Retry a known failure only when the container says a resend is safe. */
  onRetry: () => void;
  /** Reconcile an ambiguous send before any resend (XCH-02). */
  onReconcile: () => void;
  /**
   * Open the print preview owned by the printing tasks. Injected by the parent
   * so this step never imports the print feature or builds its own preview.
   */
  onOpenPrintPreview: () => void;
}

/**
 * Final delivery confirmation for a lab or imaging order (LAB-01, RAD-01).
 *
 * The step names the destination partner, explains what the patient receives
 * for each of the three modes and asks for a confirm that states the chosen
 * mode in words. Once the container reports a send outcome it shows either the
 * delivery timeline or a worded recovery choice. A resend is offered only when
 * the caller marks the failure safe to retry; an ambiguous send offers
 * reconciliation instead, because resending could duplicate an order.
 */
export function DeliveryChoice(props: DeliveryChoiceProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const { partnerName, mode, result } = props;
  const text: Line = (key) => deliveryText(locale, key);

  return (
    <section
      aria-label={text('title')}
      data-organization={props.organizationId}
      style={{ display: 'grid', gap: tokens.spacing.lg }}
    >
      <header style={{ display: 'grid', gap: tokens.spacing.sm }}>
        <h2>{text('title')}</h2>
        <p style={{ marginBlock: 0 }}>
          {text('partnerLabel')}:{' '}
          <MixedScriptText text={partnerName} lang={partnerLang(partnerName, locale)} />
        </p>
      </header>

      <fieldset style={{ display: 'grid', gap: tokens.spacing.sm }}>
        <legend>{text('modeLegend')}</legend>
        {deliveryModes.map((option) => (
          <label key={option} style={{ display: 'block' }}>
            <input
              type="radio"
              name="delivery-choice-mode"
              value={option}
              checked={mode === option}
              onChange={() => props.onChangeMode(option)}
            />{' '}
            <span>{text(modeLabelKey[option])}</span>
            {' — '}
            <span>{text(receivesKey[option])}</span>
          </label>
        ))}
      </fieldset>

      {needsRecovery(result) ? (
        <RecoveryPanel
          result={result}
          onRetry={props.onRetry}
          onReconcile={props.onReconcile}
          onOpenPrintPreview={props.onOpenPrintPreview}
          text={text}
        />
      ) : (
        <ProgressView result={result} mode={mode} onConfirm={props.onConfirm} text={text} />
      )}
    </section>
  );
}

/** The pre-recovery states: the confirm, an in-flight status or the timeline. */
function ProgressView({
  result,
  mode,
  onConfirm,
  text,
}: {
  result: Exclude<SendResult, Extract<SendResult, { state: 'failed' | 'ambiguous' }>>;
  mode: DeliveryMode;
  onConfirm: (mode: DeliveryMode) => void;
  text: Line;
}) {
  if (result.state === 'idle') {
    return <Button onClick={() => onConfirm(mode)}>{text(confirmKey[mode])}</Button>;
  }
  if (result.state === 'sending') {
    return <p role="status">{text('sending')}</p>;
  }
  return <DeliveryTimeline milestones={result.timeline} text={text} />;
}

/** Worded outcome steps shown after a successful place, in recorded order. */
function DeliveryTimeline({
  milestones,
  text,
}: {
  milestones: readonly DeliveryMilestone[];
  text: Line;
}) {
  const { tokens } = useTheme();
  return (
    <section aria-label={text('timelineTitle')} style={{ display: 'grid', gap: tokens.spacing.sm }}>
      <h3>{text('timelineTitle')}</h3>
      <ol>
        {milestones.map((milestone) => (
          <li key={milestone}>{text(milestoneKey[milestone])}</li>
        ))}
      </ol>
    </section>
  );
}

/** Worded failure or ambiguity with only the recovery actions that are safe. */
function RecoveryPanel({
  result,
  onRetry,
  onReconcile,
  onOpenPrintPreview,
  text,
}: {
  result: Extract<SendResult, { state: 'failed' | 'ambiguous' }>;
  onRetry: () => void;
  onReconcile: () => void;
  onOpenPrintPreview: () => void;
  text: Line;
}) {
  const { tokens } = useTheme();
  const failed = result.state === 'failed';
  const retryAllowed = failed && canRetry(result);
  const titleKey: DeliveryMessageKey = failed ? 'sendFailedTitle' : 'sendAmbiguousTitle';
  const detailKey: DeliveryMessageKey = failed ? 'sendFailedDetail' : 'sendAmbiguousDetail';

  return (
    <section
      role="alert"
      aria-live="assertive"
      data-testid={failed ? 'delivery-failure' : 'delivery-ambiguous'}
      style={{ display: 'grid', gap: tokens.spacing.sm }}
    >
      <h3>{text(titleKey)}</h3>
      <p>{text(detailKey)}</p>
      <CorrelationLine id={result.correlationId} text={text} />
      {failed && !retryAllowed ? <p>{text('retryUnavailable')}</p> : null}
      <div style={{ display: 'flex', gap: tokens.spacing.sm }}>
        {retryAllowed ? <Button onClick={onRetry}>{text('retry')}</Button> : null}
        {!failed ? <Button onClick={onReconcile}>{text('reconcile')}</Button> : null}
        <Button variant="secondary" onClick={onOpenPrintPreview}>
          {text('printInstead')}
        </Button>
      </div>
    </section>
  );
}

/** Opague, copyable correlation reference; never technical error detail. */
function CorrelationLine({ id, text }: { id: string | undefined; text: Line }) {
  if (id === undefined) {
    return null;
  }
  return (
    <p>
      {text('reference')}: <code dir="ltr">{id}</code>
    </p>
  );
}
