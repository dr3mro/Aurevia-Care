import { useState } from 'react';
import { ApiPage } from '../../components/ApiPage';
import { Button } from '../../components/Button';
import { Form } from '../../components/Form';
import { FormField } from '../../components/FormField';
import { MixedScriptText } from '../../components/MixedScriptText';
import { ConflictState, StateView } from '../../components/StateViews';
import type { TransportError } from '../../api/transport';
import { formatInstant } from '../../i18n/format';
import type { Locale } from '../../i18n/messages';
import { useTheme } from '../../theme/ThemeProvider';
import { acknowledgeResult, asResultTransportError, getResult } from './api';
import type { ResultDetail } from './domain';
import { isNonBlank } from './domain';
import { resultKindKey, resultText, reviewStateKey } from './messages';

/** Props for the tenant-scoped result review panel. */
export interface ResultReviewProps {
  organizationId: string;
  locale: Locale;
}

/** One labelled immutable revision fact shown in the review panel. */
function Fact({ label, value, lang }: { label: string; value: string; lang: string }) {
  const { tokens } = useTheme();
  return (
    <div style={{ display: 'grid', gap: tokens.spacing.sm }}>
      <dt>{label}</dt>
      <dd style={{ marginInlineStart: 0 }}>
        <MixedScriptText text={value} lang={lang} />
      </dd>
    </div>
  );
}

/** Props for the loaded detail panel. */
interface DetailPanelProps {
  id: string;
  detail: ResultDetail;
  organizationId: string;
  locale: Locale;
  onDone: () => void;
  onError: (error: TransportError) => void;
}

/** Show one revision, its lineage and the acknowledge command. */
function DetailPanel({ id, detail, organizationId, locale, onDone, onError }: DetailPanelProps) {
  const { tokens } = useTheme();

  async function handleAcknowledge() {
    try {
      await acknowledgeResult(id, detail.review.version, organizationId);
      onDone();
    } catch (failure: unknown) {
      onError(asResultTransportError(failure));
    }
  }

  return (
    <div style={{ display: 'grid', gap: tokens.spacing.md }}>
      <dl style={{ display: 'grid', gap: tokens.spacing.sm }}>
        <Fact label={resultText(locale, 'reportId')} value={detail.current.id} lang="en" />
        <Fact label={resultText(locale, 'version')} value={String(detail.current.version)} lang="en" />
        <Fact
          label={resultText(locale, 'kind')}
          value={resultText(locale, resultKindKey[detail.current.kind])}
          lang={locale}
        />
        <Fact
          label={resultText(locale, 'reviewState')}
          value={resultText(locale, reviewStateKey[detail.review.state])}
          lang={locale}
        />
        <Fact label={resultText(locale, 'owner')} value={detail.current.owner} lang="en" />
        <Fact label={resultText(locale, 'coverageQueue')} value={detail.current.coverageQueue} lang="en" />
        <Fact
          label={resultText(locale, 'effectiveAt')}
          value={formatInstant(detail.current.effectiveAt, 'UTC', locale)}
          lang={locale}
        />
      </dl>
      {detail.review.state === 'unreviewed' ? (
        <Button onClick={handleAcknowledge}>{resultText(locale, 'acknowledge')}</Button>
      ) : (
        <p role="status">{resultText(locale, 'acknowledged')}</p>
      )}
      {detail.lineage.length > 0 && (
        <section aria-label={resultText(locale, 'lineage')}>
          <h3>{resultText(locale, 'lineage')}</h3>
          <ul>
            {detail.lineage.map((revision) => (
              <li key={revision.id}>
                <MixedScriptText
                  text={`${revision.id} · v${revision.version}`}
                  lang="en"
                />
              </li>
            ))}
          </ul>
        </section>
      )}
      {detail.notifications.map((notice) => (
        <p key={notice.id} role="status">
          {resultText(locale, 'correctionNotice')}:{' '}
          <MixedScriptText text={`${notice.recipient} · v${notice.supersededVersion}`} lang="en" />
        </p>
      ))}
    </div>
  );
}

/** Props for the loader keyed by one opaque result identifier. */
interface LoadedResultProps {
  id: string;
  organizationId: string;
  locale: Locale;
  reloadToken: number;
  onDone: () => void;
  onError: (error: TransportError) => void;
}

/** Load one result revision through `ApiPage` and expose its acknowledge action. */
function LoadedResult({
  id,
  organizationId,
  locale,
  reloadToken,
  onDone,
  onError,
}: LoadedResultProps) {
  return (
    <ApiPage
      resource={{
        label: resultText(locale, 'resultDetail'),
        load: () => getResult(id, organizationId),
        deps: [id, organizationId, reloadToken],
      }}
      isEmpty={() => false}
    >
      {(detail) => (
        <DetailPanel
          id={id}
          detail={detail}
          organizationId={organizationId}
          locale={locale}
          onDone={onDone}
          onError={onError}
        />
      )}
    </ApiPage>
  );
}

/**
 * Open one result by its opaque identifier, review the returned revision and
 * acknowledge it with the version observed on screen. A conflict keeps the
 * typed identifier so the reviewer can compare and reload (WEB-09).
 */
export function ResultReview({ organizationId, locale }: ResultReviewProps) {
  const { tokens } = useTheme();
  const [resultId, setResultId] = useState('');
  const [openedId, setOpenedId] = useState<string | undefined>(undefined);
  const [idError, setIdError] = useState(false);
  const [commandError, setCommandError] = useState<TransportError | undefined>(undefined);
  const [statusText, setStatusText] = useState<string | undefined>(undefined);
  const [reloadToken, setReloadToken] = useState(0);
  const [comparing, setComparing] = useState(false);
  const [copied, setCopied] = useState(false);

  function handleOpen() {
    if (!isNonBlank(resultId)) {
      setIdError(true);
      return;
    }
    setIdError(false);
    setCommandError(undefined);
    setStatusText(undefined);
    setOpenedId(resultId.trim());
  }

  function handleDone() {
    setStatusText(resultText(locale, 'acknowledged'));
    setCommandError(undefined);
    setReloadToken((value) => value + 1);
  }

  function handleReload() {
    setCommandError(undefined);
    setStatusText(undefined);
    setComparing(false);
    setCopied(false);
    setReloadToken((value) => value + 1);
  }

  function handleCopy() {
    void navigator.clipboard?.writeText(resultId);
    setCopied(true);
  }

  return (
    <section aria-label={resultText(locale, 'resultReview')} style={{ display: 'grid', gap: tokens.spacing.md }}>
      <h2>{resultText(locale, 'resultReview')}</h2>
      <p>{resultText(locale, 'openResultIntro')}</p>
      <Form label={resultText(locale, 'resultReview')} submitLabel={resultText(locale, 'openResult')} onSubmit={handleOpen}>
        <FormField
          id="result-id"
          label={resultText(locale, 'resultId')}
          value={resultId}
          onChange={setResultId}
          {...(idError ? { error: resultText(locale, 'resultIdRequired') } : {})}
        />
      </Form>
      {statusText !== undefined && <p role="status">{statusText}</p>}
      {commandError !== undefined ? (
        commandError.kind === 'conflict' ? (
          <ConflictState
            onCompare={() => setComparing(true)}
            onReload={handleReload}
            onCopy={handleCopy}
          />
        ) : (
          StateView.forTransportError(commandError)
        )
      ) : null}
      {copied && <p role="status">{resultText(locale, 'copiedNotice')}</p>}
      {comparing && (
        <section data-testid="result-compare" style={{ display: 'grid', gap: tokens.spacing.sm }}>
          <h3>{resultText(locale, 'compareTitle')}</h3>
          <p>{resultText(locale, 'compareHint')}</p>
        </section>
      )}
      {openedId === undefined ? (
        <p>{resultText(locale, 'resultNotFoundHint')}</p>
      ) : (
        <LoadedResult
          id={openedId}
          organizationId={organizationId}
          locale={locale}
          reloadToken={reloadToken}
          onDone={handleDone}
          onError={setCommandError}
        />
      )}
    </section>
  );
}
