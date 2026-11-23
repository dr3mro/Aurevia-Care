/**
 * Onboarding import screen (OPS-08).
 *
 * <p>Builds a fully synthetic versioned manifest, runs a read-only dry run and
 * presents every required finding. Only after a successful dry run may a named
 * authorized sign-off be submitted. The screen never writes chart data itself:
 * the dry run changes nothing and the sign-off is a separate, audited command.
 * Only an authorized role may use the screen; a denied API response resolves to
 * the shared forbidden state.</p>
 */

import { useState } from 'react';
import type { Dispatch, SetStateAction } from 'react';
import { useActiveOrganization } from '../../api/activeOrganization';
import type { TransportError } from '../../api/transport';
import type { Role } from '../../app/navigation';
import { useRole } from '../../app/useRole';
import { Button } from '../../components/Button';
import { Form } from '../../components/Form';
import { FormField } from '../../components/FormField';
import { ForbiddenState, StateView } from '../../components/StateViews';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
import {
  asDataExchangeError,
  manifestFromDraft,
  runDryRun,
  signOffRequest,
  submitSignOff,
} from './api';
import type { ImportManifestRequest } from './api';
import { canReviewImport, signOffRejection, toDryRunView } from './domain';
import type { DryRunView, SignOffRejection } from './domain';
import { dataExchangeText } from './labels';
import type { DataExchangeMessageKey } from './labels';

/** Props for the onboarding import screen. */
export interface ImportPageProps {
  /** Role override for tests and embedding; the session role is the default. */
  roleOverride?: Role;
}

/** Fields describing the synthetic dataset under review. */
interface DatasetDraft {
  source: string;
  sourceVersion: string;
  manifestVersion: string;
  unsupportedFieldNames: string;
}

/** Fields of the named authorization step. */
interface SignOffDraft {
  approver: string;
  decision: 'approved' | 'rejected';
  contentSha256: string;
  approvedAt: string;
}

/** A ready-to-run synthetic dataset so a reviewer can act without fixture setup. */
const EMPTY_DATASET: DatasetDraft = {
  source: 'SYNTHETIC-CSV',
  sourceVersion: '1.0',
  manifestVersion: '1',
  unsupportedFieldNames: 'legacy_ssn, free_text_notes',
};

/** An empty named authorization: an approver must always be supplied. */
const EMPTY_SIGN_OFF: SignOffDraft = {
  approver: '',
  decision: 'approved',
  contentSha256: '',
  approvedAt: '',
};

/** Map a local refusal to the exact reason the operator can act on. */
const REJECTION_MESSAGE: Record<SignOffRejection, DataExchangeMessageKey> = {
  missingApprover: 'reasonMissingApprover',
  roleNotPermitted: 'reasonRoleNotPermitted',
  decisionNotApproved: 'reasonDecisionNotApproved',
};

/** One titled list of findings, or its explicit empty message. */
function FindingList({
  title,
  items,
  emptyText,
}: {
  title: string;
  items: readonly string[];
  emptyText: string;
}) {
  return (
    <>
      <h4>{title}</h4>
      {items.length === 0 ? (
        <p>{emptyText}</p>
      ) : (
        <ul aria-label={title}>
          {items.map((entry) => (
            <li key={entry}>{entry}</li>
          ))}
        </ul>
      )}
    </>
  );
}

/** Every required dry-run finding, in the order the operator reviews them. */
function DryRunReport({
  report,
  text,
}: {
  report: DryRunView;
  text: (key: DataExchangeMessageKey) => string;
}) {
  return (
    <>
      <p>
        {text('totalRecords')}: {report.totalRecords}
      </p>
      <p>
        {text('batchCount')}: {report.batchCount}
      </p>
      <FindingList
        title={text('unsupportedFields')}
        items={report.unsupportedFields}
        emptyText={text('noUnsupportedFields')}
      />
      <FindingList
        title={text('duplicateRisk')}
        items={report.duplicateRisk}
        emptyText={text('noDuplicateRisk')}
      />
      <FindingList
        title={text('missingAttachments')}
        items={report.missingAttachments}
        emptyText={text('noMissingAttachments')}
      />
      <FindingList title={text('errors')} items={report.errors} emptyText={text('noErrors')} />
    </>
  );
}

/** Synthetic dataset fields and the dry-run submit action. */
function DryRunForm({
  dataset,
  setDataset,
  text,
  onSubmit,
}: {
  dataset: DatasetDraft;
  setDataset: Dispatch<SetStateAction<DatasetDraft>>;
  text: (key: DataExchangeMessageKey) => string;
  onSubmit: () => void;
}) {
  const { tokens } = useTheme();
  return (
    <section aria-label={text('dryRunForm')} style={{ display: 'grid', gap: tokens.spacing.sm }}>
      <h3>{text('dryRunForm')}</h3>
      <Form label={text('dryRunForm')} submitLabel={text('runDryRun')} onSubmit={onSubmit}>
        <FormField
          id="dataexchange-source"
          label={text('source')}
          value={dataset.source}
          onChange={(value) => setDataset((current) => ({ ...current, source: value }))}
        />
        <FormField
          id="dataexchange-source-version"
          label={text('sourceVersion')}
          value={dataset.sourceVersion}
          onChange={(value) => setDataset((current) => ({ ...current, sourceVersion: value }))}
        />
        <FormField
          id="dataexchange-manifest-version"
          label={text('manifestVersion')}
          value={dataset.manifestVersion}
          onChange={(value) => setDataset((current) => ({ ...current, manifestVersion: value }))}
        />
        <FormField
          id="dataexchange-unsupported-fields"
          label={text('unsupportedFieldNames')}
          value={dataset.unsupportedFieldNames}
          onChange={(value) =>
            setDataset((current) => ({ ...current, unsupportedFieldNames: value }))
          }
        />
      </Form>
    </section>
  );
}

/**
 * Render the dry-run form, its deterministic report and the sign-off step.
 *
 * @param props optional role override (synthetic value in tests)
 */
export function ImportPage({ roleOverride }: ImportPageProps) {
  const organizationId = useActiveOrganization();
  const sessionRole = useRole();
  const activeRole = roleOverride ?? sessionRole;
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const [dataset, setDataset] = useState<DatasetDraft>(EMPTY_DATASET);
  const [signOff, setSignOff] = useState<SignOffDraft>(EMPTY_SIGN_OFF);
  const [report, setReport] = useState<DryRunView | undefined>(undefined);
  const [manifest, setManifest] = useState<ImportManifestRequest | undefined>(undefined);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<TransportError | undefined>(undefined);
  const [status, setStatus] = useState<DataExchangeMessageKey | undefined>(undefined);

  if (!canReviewImport(activeRole)) {
    return <ForbiddenState />;
  }

  const text = (key: DataExchangeMessageKey) => dataExchangeText(locale, key);

  async function handleDryRun() {
    const version = Number(dataset.manifestVersion);
    if (!Number.isFinite(version) || version <= 0) {
      setReport(undefined);
      setManifest(undefined);
      setStatus('invalidManifestVersion');
      return;
    }
    setBusy(true);
    try {
      const built = manifestFromDraft({ ...dataset, manifestVersion: version });
      const response = await runDryRun(built, organizationId);
      setReport(toDryRunView(response));
      setManifest(built);
      setStatus('dryRunDone');
      setError(undefined);
    } catch (failure: unknown) {
      setStatus(undefined);
      setError(asDataExchangeError(failure));
    } finally {
      setBusy(false);
    }
  }

  async function handleSignOff() {
    const rejection = signOffRejection(signOff.approver, activeRole, signOff.decision);
    if (rejection !== undefined) {
      setStatus(REJECTION_MESSAGE[rejection]);
      return;
    }
    if (manifest === undefined) {
      setStatus('runDryRunFirst');
      return;
    }
    setBusy(true);
    try {
      const request = signOffRequest(manifest, {
        approver: signOff.approver,
        role: activeRole,
        contentSha256: signOff.contentSha256,
        approvedAt: signOff.approvedAt,
      });
      const result = await submitSignOff(request, organizationId);
      setStatus(result.accepted ? 'signOffDone' : 'signOffRefused');
      setError(undefined);
    } catch (failure: unknown) {
      setStatus(undefined);
      setError(asDataExchangeError(failure));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div style={{ display: 'grid', gap: tokens.spacing.lg }}>
      <h2>{text('importTitle')}</h2>
      <p>{text('syntheticNotice')}</p>
      <p>{text('importIntro')}</p>
      {status === undefined ? null : <p role="status">{text(status)}</p>}
      {error === undefined ? null : (
        <div data-testid="import-command-error">{StateView.forTransportError(error)}</div>
      )}

      <DryRunForm
        dataset={dataset}
        setDataset={setDataset}
        text={text}
        onSubmit={() => void handleDryRun()}
      />

      <section aria-label={text('report')} style={{ display: 'grid', gap: tokens.spacing.sm }}>
        <h3>{text('report')}</h3>
        {report === undefined ? null : <DryRunReport report={report} text={text} />}
      </section>

      <section aria-label={text('signOff')} style={{ display: 'grid', gap: tokens.spacing.sm }}>
        <h3>{text('signOff')}</h3>
        <div style={{ display: 'grid', gap: tokens.spacing.md }}>
          <FormField
            id="dataexchange-approver"
            label={text('approver')}
            value={signOff.approver}
            onChange={(value) => setSignOff((current) => ({ ...current, approver: value }))}
          />
          <div style={{ display: 'grid', gap: tokens.spacing.sm }}>
            <label htmlFor="dataexchange-decision">{text('decision')}</label>
            <select
              id="dataexchange-decision"
              value={signOff.decision}
              onChange={(event) =>
                setSignOff((current) => ({
                  ...current,
                  decision: event.target.value === 'approved' ? 'approved' : 'rejected',
                }))
              }
              style={{ minHeight: '32px' }}
            >
              <option value="approved">{text('decisionApproved')}</option>
              <option value="rejected">{text('decisionRejected')}</option>
            </select>
          </div>
          <FormField
            id="dataexchange-content-sha256"
            label={text('contentSha256')}
            value={signOff.contentSha256}
            onChange={(value) => setSignOff((current) => ({ ...current, contentSha256: value }))}
          />
          <FormField
            id="dataexchange-approved-at"
            label={text('approvedAt')}
            value={signOff.approvedAt}
            onChange={(value) => setSignOff((current) => ({ ...current, approvedAt: value }))}
          />
        </div>
      </section>
      <Button onClick={() => void handleSignOff()} disabled={busy}>
        {text('signOffButton')}
      </Button>
    </div>
  );
}
