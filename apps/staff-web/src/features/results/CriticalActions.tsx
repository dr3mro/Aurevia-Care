import { useState } from 'react';
import type { TransportError } from '../../api/transport';
import { Form } from '../../components/Form';
import { FormField } from '../../components/FormField';
import type { Locale } from '../../i18n/messages';
import { useTheme } from '../../theme/ThemeProvider';
import {
  acknowledgeCritical,
  asResultTransportError,
  assignCritical,
  escalateCritical,
  readBackCritical,
} from './api';
import type { CriticalResult } from './domain';
import { escalationTarget, isNonBlank, outcomeFrom } from './domain';
import { criticalStateKey, resultText } from './messages';

/** Shared inputs for every critical-result command. */
interface ActionProps {
  result: CriticalResult;
  organizationId: string;
  locale: Locale;
  now: number;
  onDone: () => void;
  onError: (error: TransportError) => void;
}

/** Record a clinician's acknowledgment of the displayed revision. */
function AcknowledgeForm({ result, organizationId, locale, onDone, onError }: ActionProps) {
  const [note, setNote] = useState('');

  async function submit() {
    try {
      await acknowledgeCritical(result.id, result.version, note, organizationId);
      onDone();
    } catch (failure: unknown) {
      onError(asResultTransportError(failure));
    }
  }

  return (
    <Form
      label={resultText(locale, 'acknowledge')}
      submitLabel={resultText(locale, 'acknowledge')}
      onSubmit={submit}
    >
      <FormField
        id={`critical-ack-note-${result.id}`}
        label={resultText(locale, 'note')}
        value={note}
        onChange={setNote}
      />
    </Form>
  );
}

/** Assign a named owner and a future due instant. */
function AssignForm({ result, organizationId, locale, onDone, onError }: ActionProps) {
  const { tokens } = useTheme();
  const [ownerSubject, setOwnerSubject] = useState(result.ownerSubject ?? '');
  const [dueAt, setDueAt] = useState('');

  async function submit() {
    try {
      await assignCritical(result.id, result.version, ownerSubject, dueAt, organizationId);
      onDone();
    } catch (failure: unknown) {
      onError(asResultTransportError(failure));
    }
  }

  return (
    <Form label={resultText(locale, 'assign')} submitLabel={resultText(locale, 'assign')} onSubmit={submit}>
      <div style={{ display: 'grid', gap: tokens.spacing.md }}>
        <FormField
          id={`critical-owner-${result.id}`}
          label={resultText(locale, 'ownerSubject')}
          value={ownerSubject}
          onChange={setOwnerSubject}
        />
        <FormField
          id={`critical-due-${result.id}`}
          label={resultText(locale, 'dueAt')}
          value={dueAt}
          onChange={setDueAt}
        />
      </div>
    </Form>
  );
}

/** Record one escalation attempt using the approved channel for the current time. */
function EscalateForm({ result, organizationId, locale, now, onDone, onError }: ActionProps) {
  const { tokens } = useTheme();
  const target = escalationTarget(result, now);
  const [channel, setChannel] = useState(target.channel);
  const [recipient, setRecipient] = useState(target.recipient);
  const [reason, setReason] = useState('');
  const [reasonError, setReasonError] = useState(false);
  const [outcome, setOutcome] = useState<'delivered' | 'failed'>('delivered');
  const [systemDown, setSystemDown] = useState(false);

  async function submit() {
    if (!isNonBlank(reason)) {
      setReasonError(true);
      return;
    }
    setReasonError(false);
    try {
      await escalateCritical(
        result.id,
        result.version,
        reason.trim(),
        channel,
        recipient,
        outcome,
        systemDown,
        organizationId,
      );
      onDone();
    } catch (failure: unknown) {
      onError(asResultTransportError(failure));
    }
  }

  return (
    <Form label={resultText(locale, 'escalate')} submitLabel={resultText(locale, 'escalate')} onSubmit={submit}>
      <div style={{ display: 'grid', gap: tokens.spacing.md }}>
        <FormField
          id={`critical-channel-${result.id}`}
          label={resultText(locale, 'channel')}
          value={channel}
          onChange={setChannel}
        />
        <FormField
          id={`critical-recipient-${result.id}`}
          label={resultText(locale, 'recipient')}
          value={recipient}
          onChange={setRecipient}
        />
        <FormField
          id={`critical-reason-${result.id}`}
          label={resultText(locale, 'reason')}
          value={reason}
          onChange={setReason}
          {...(reasonError ? { error: resultText(locale, 'reasonRequired') } : {})}
        />
        <label htmlFor={`critical-outcome-${result.id}`}>{resultText(locale, 'outcome')}</label>
        <select
          id={`critical-outcome-${result.id}`}
          value={outcome}
          onChange={(change) => setOutcome(outcomeFrom(change.target.value))}
          style={{ background: tokens.background, color: tokens.foreground }}
        >
          <option value="delivered">{resultText(locale, 'delivered')}</option>
          <option value="failed">{resultText(locale, 'failed')}</option>
        </select>
        <label htmlFor={`critical-system-down-${result.id}`}>
          <input
            id={`critical-system-down-${result.id}`}
            type="checkbox"
            checked={systemDown}
            onChange={(change) => setSystemDown(change.target.checked)}
          />{' '}
          {resultText(locale, 'systemDown')}
        </label>
      </div>
    </Form>
  );
}

/** Record the value repeated verbatim by the recipient. */
function ReadBackForm({ result, organizationId, locale, onDone, onError }: ActionProps) {
  const [repeatedValue, setRepeatedValue] = useState('');

  async function submit() {
    try {
      await readBackCritical(result.id, result.version, repeatedValue, organizationId);
      onDone();
    } catch (failure: unknown) {
      onError(asResultTransportError(failure));
    }
  }

  return (
    <Form label={resultText(locale, 'readBack')} submitLabel={resultText(locale, 'readBack')} onSubmit={submit}>
      <FormField
        id={`critical-readback-${result.id}`}
        label={resultText(locale, 'repeatedValue')}
        value={repeatedValue}
        onChange={setRepeatedValue}
      />
    </Form>
  );
}

/** Offer only the commands the result's current state allows. */
export function CriticalActions(props: ActionProps) {
  const { tokens } = useTheme();
  const { result, locale } = props;
  return (
    <section aria-label={resultText(locale, 'actions')} style={{ display: 'grid', gap: tokens.spacing.md }}>
      <h3>{resultText(locale, 'actions')}</h3>
      {result.state === 'unverified' && <AssignForm {...props} />}
      {result.state === 'awaiting_ack' && (
        <>
          <AcknowledgeForm {...props} />
          <EscalateForm {...props} />
          {result.readBackRequired && <ReadBackForm {...props} />}
        </>
      )}
      {(result.state === 'acknowledged' || result.state === 'closed') && (
        <p role="status">{resultText(locale, criticalStateKey[result.state])}</p>
      )}
    </section>
  );
}
