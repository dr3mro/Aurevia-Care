import { useState } from 'react';
import type { TransportError } from '../../api/transport';
import { Button } from '../../components/Button';
import { FormField } from '../../components/FormField';
import { MixedScriptText } from '../../components/MixedScriptText';
import { StateView } from '../../components/StateViews';
import { formatInstant } from '../../i18n/format';
import type { Locale } from '../../i18n/messages';
import { useTheme } from '../../theme/ThemeProvider';
import { hasAnswer, isAnswered } from './domain';
import { partyMessageKey, prescriberText } from './labels';
import type { AnswerDraft, ThreadViewResponse } from './types';

/** Props for the prescriber's clarification answer form. */
export interface ClarificationReplyProps {
  thread: ThreadViewResponse;
  locale: Locale;
  timeZone: string;
  error?: TransportError;
  onSend: (draft: AnswerDraft) => void;
}

/**
 * Presents the pharmacy's question and any answer trail. An open request takes
 * a free-text answer, an amended prescription revision, or both; an answered
 * thread is read-only and links the revision without implying a prescription
 * change the server did not make. Typed work is owned here so a failed command
 * never clears it (WEB-09).
 */
export function ClarificationReply({
  thread,
  locale,
  timeZone,
  error,
  onSend,
}: ClarificationReplyProps) {
  const { tokens } = useTheme();
  const [answerBody, setAnswerBody] = useState('');
  const [amendedRevisionId, setAmendedRevisionId] = useState('');
  const [validationError, setValidationError] = useState(false);
  const answered = isAnswered(thread.thread.state);

  function handleSend() {
    if (!hasAnswer(answerBody, amendedRevisionId)) {
      setValidationError(true);
      return;
    }
    setValidationError(false);
    onSend({ answerBody, amendedRevisionId });
  }

  return (
    <section
      aria-label={prescriberText(locale, 'clarificationTitle')}
      style={{ display: 'grid', gap: tokens.spacing.md }}
    >
      <h2>{prescriberText(locale, 'clarificationTitle')}</h2>
      {thread.overdue ? <p data-testid="overdue">{prescriberText(locale, 'overdue')}</p> : null}
      <h3>{prescriberText(locale, 'threadEntries')}</h3>
      <ul>
        {thread.entries.map((entry) => (
          <li key={entry.id} data-testid={`clarification-entry-${entry.id}`}>
            <p>{prescriberText(locale, partyMessageKey(entry.authorParty))}</p>
            {entry.body === '' ? null : <MixedScriptText text={entry.body} lang="en" />}
            {entry.amendedRevisionId === null ? null : (
              <code dir="ltr">{entry.amendedRevisionId}</code>
            )}
            <time dateTime={entry.occurredAt}>
              {formatInstant(entry.occurredAt, timeZone, locale)}
            </time>
          </li>
        ))}
      </ul>
      {answered ? (
        <section aria-label={prescriberText(locale, 'answered')}>
          <p>{prescriberText(locale, 'answered')}</p>
          {thread.thread.answerBody === null ? null : (
            <bdi data-testid="answer-body" lang="en" dir="auto">
              {thread.thread.answerBody}
            </bdi>
          )}
          {thread.thread.amendedRevisionId === null ? null : (
            <a
              data-testid="amended-revision-link"
              href={`/pharmacy/${thread.thread.prescriptionId}`}
            >
              {prescriberText(locale, 'linkedRevision')}:{' '}
              <code dir="ltr">{thread.thread.amendedRevisionId}</code>
            </a>
          )}
        </section>
      ) : (
        <section aria-label={prescriberText(locale, 'sendAnswer')}>
          <FormField
            id="clarification-answer"
            label={prescriberText(locale, 'answerLabel')}
            value={answerBody}
            onChange={(value) => {
              setAnswerBody(value);
              setValidationError(false);
            }}
            {...(validationError ? { error: prescriberText(locale, 'answerRequired') } : {})}
          />
          <FormField
            id="clarification-revision"
            label={prescriberText(locale, 'revisionLabel')}
            value={amendedRevisionId}
            onChange={(value) => {
              setAmendedRevisionId(value);
              setValidationError(false);
            }}
          />
          <Button onClick={handleSend}>{prescriberText(locale, 'sendAnswer')}</Button>
        </section>
      )}
      {error === undefined ? null : <div>{StateView.forTransportError(error)}</div>}
    </section>
  );
}
