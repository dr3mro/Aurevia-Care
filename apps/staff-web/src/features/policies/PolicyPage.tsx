/** API-backed administration page for templates, dictionaries and release policies. */

import { useEffect, useRef, useState } from 'react';
import { FieldErrorSummary } from '../admin/FieldErrorSummary';
import type { FieldErrorLink } from '../admin/FieldErrorSummary';
import { useActiveOrganization } from '../../api/activeOrganization';
import type { TransportError } from '../../api/transport';
import { ApiPage } from '../../components/ApiPage';
import { Button } from '../../components/Button';
import { Form } from '../../components/Form';
import { FormField } from '../../components/FormField';
import { StateView } from '../../components/StateViews';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
import {
  asPolicyTransportError,
  approvePolicyVersion,
  defineDictionaryField,
  isValidCatalogFamily,
  isValidConfigKey,
  loadDictionaryVersions,
  loadPolicyVersions,
  proposePolicyVersion,
} from './api';
import { effectiveVersion, type PolicyChangeDraft } from './domain';
import { defaultKeys, policyAnchorCivilDate } from './fixtures';
import { policyMessage, policyText } from './messages';
import type { PolicyMessageKey } from './messages';
import { FieldDictionaryScreen } from './FieldDictionaryScreen';
import { ReleasePolicyScreen } from './ReleasePolicyScreen';
import { TemplateScreen } from './TemplateScreen';

/** The three review areas, in the order staff see them. */
const AREAS = [
  { id: 'templates', labelKey: 'areaTemplates' },
  { id: 'dictionaries', labelKey: 'areaDictionaries' },
  { id: 'release', labelKey: 'areaRelease' },
] as const;

/** One review area. */
type PolicyArea = (typeof AREAS)[number]['id'];

/** A reviewed key and the area that validated it. */
interface AppliedKey {
  area: PolicyArea;
  key: string;
}

/** One approval waiting for explicit confirmation. */
interface PendingApproval {
  version: number;
  note: string;
}

/** A command that keeps typed work when it fails. */
type RunCommand = (command: () => Promise<unknown>) => Promise<boolean>;

/** Default key presented for each area. */
const DEFAULT_KEY_BY_AREA: Record<PolicyArea, string> = {
  templates: defaultKeys.templates,
  dictionaries: defaultKeys.dictionaries,
  release: defaultKeys.release,
};

/** Props for the approval dialog of one reviewed key. */
interface ApprovalDialogProps {
  policyKey: string;
  approval: PendingApproval;
  onCancel: () => void;
  onConfirm: (approval: PendingApproval) => void;
}

/** Confirm a named approval; the safe Cancel choice takes initial focus. */
function ApprovalDialog({ policyKey, approval, onCancel, onConfirm }: ApprovalDialogProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const [note, setNote] = useState(approval.note);
  const cancelRef = useRef<HTMLButtonElement>(null);
  const title = policyMessage(locale, 'approveTitle', {
    key: policyKey,
    version: String(approval.version),
  });

  useEffect(() => {
    cancelRef.current?.focus();
  }, []);

  return (
    <section role="dialog" aria-label={title} style={{ display: 'grid', gap: tokens.spacing.md }}>
      <h2>{title}</h2>
      <p>{policyText(locale, 'approveDetail')}</p>
      <FormField
        id="policy-approval-note"
        label={policyText(locale, 'approveNote')}
        value={note}
        onChange={setNote}
      />
      <div>
        <Button variant="secondary" buttonRef={cancelRef} onClick={onCancel}>
          {policyText(locale, 'cancel')}
        </Button>{' '}
        <Button onClick={() => onConfirm({ version: approval.version, note })}>
          {policyText(locale, 'approve')}
        </Button>
      </div>
    </section>
  );
}

/** Props for the data-backed review of one tenant-scoped key. */
interface PolicyReviewProps {
  applied: AppliedKey;
  organizationId: string;
  reloadToken: number;
  runCommand: RunCommand;
}

/** Load one reviewed key and render the matching screen and approval dialog. */
function PolicyReview({ applied, organizationId, reloadToken, runCommand }: PolicyReviewProps) {
  const { locale } = useI18n();
  const [approval, setApproval] = useState<PendingApproval | undefined>(undefined);

  function openApproval(version: number) {
    setApproval({ version, note: '' });
  }

  async function confirmApproval(target: PendingApproval) {
    setApproval(undefined);
    await runCommand(() =>
      approvePolicyVersion(applied.key, target.version, target.note, organizationId),
    );
  }

  return (
    <>
      <ApiPage
        resource={{
          label: policyText(locale, 'history'),
          load: () =>
            applied.area === 'dictionaries'
              ? loadDictionaryVersions(applied.key, organizationId)
              : loadPolicyVersions(applied.key, organizationId),
          deps: [applied.area, applied.key, organizationId, reloadToken],
        }}
        isEmpty={(versions) => versions.length === 0}
      >
        {(versions) => {
          const effective = effectiveVersion(
            versions,
            organizationId,
            applied.key,
            policyAnchorCivilDate,
          );
          const effectiveNumber = effective === undefined ? undefined : effective.version;
          const propose = (draft: PolicyChangeDraft) =>
            runCommand(() => proposePolicyVersion(applied.key, draft, organizationId));
          const defineField = (draft: PolicyChangeDraft) =>
            runCommand(() => defineDictionaryField(applied.key, draft, organizationId));
          if (applied.area === 'templates') {
            return (
              <TemplateScreen
                versions={versions}
                effectiveVersionNumber={effectiveNumber}
                onPropose={propose}
                onApprove={openApproval}
              />
            );
          }
          if (applied.area === 'dictionaries') {
            return (
              <FieldDictionaryScreen
                versions={versions}
                effectiveVersionNumber={effectiveNumber}
                onDefine={defineField}
              />
            );
          }
          return (
            <ReleasePolicyScreen
              organizationId={organizationId}
              versions={versions}
              currentVersion={effective}
              anchorCivilDate={policyAnchorCivilDate}
              effectiveVersionNumber={effectiveNumber}
              onSet={propose}
              onApprove={openApproval}
            />
          );
        }}
      </ApiPage>
      {approval === undefined ? null : (
        <ApprovalDialog
          policyKey={applied.key}
          approval={approval}
          onCancel={() => setApproval(undefined)}
          onConfirm={(target) => void confirmApproval(target)}
        />
      )}
    </>
  );
}

/** Content scoped to one tenant; remounting clears the previous tenant's state. */
function PolicyContent({ organizationId }: { organizationId: string }) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const [area, setArea] = useState<PolicyArea>('templates');
  const [input, setInput] = useState<string>(DEFAULT_KEY_BY_AREA.templates);
  const [applied, setApplied] = useState<AppliedKey | undefined>(undefined);
  const [lookupError, setLookupError] = useState<PolicyMessageKey | undefined>(undefined);
  const [reloadToken, setReloadToken] = useState(0);
  const [statusKey, setStatusKey] = useState<PolicyMessageKey | undefined>(undefined);
  const [commandError, setCommandError] = useState<TransportError | undefined>(undefined);

  function selectArea(next: PolicyArea) {
    setArea(next);
    setInput(DEFAULT_KEY_BY_AREA[next]);
    setApplied(undefined);
    setLookupError(undefined);
    setStatusKey(undefined);
    setCommandError(undefined);
  }

  function handleLookup() {
    const trimmed = input.trim();
    if (trimmed === '') {
      setLookupError('keyRequired');
      return;
    }
    const valid = area === 'dictionaries' ? isValidCatalogFamily(trimmed) : isValidConfigKey(trimmed);
    if (!valid) {
      setLookupError(area === 'dictionaries' ? 'invalidDictionaryFamily' : 'invalidPolicyKey');
      return;
    }
    setLookupError(undefined);
    setApplied({ area, key: trimmed });
    setStatusKey(undefined);
    setCommandError(undefined);
  }

  /** Run one command, keeping typed work when the API refuses it. */
  async function runCommand(command: () => Promise<unknown>): Promise<boolean> {
    setCommandError(undefined);
    setStatusKey(undefined);
    try {
      await command();
      setStatusKey('changeSaved');
      setReloadToken((value) => value + 1);
      return true;
    } catch (failure: unknown) {
      setCommandError(asPolicyTransportError(failure));
      return false;
    }
  }

  const lookupLinks: FieldErrorLink[] =
    lookupError === undefined
      ? []
      : [
          {
            id: 'policy-key',
            label:
              area === 'dictionaries'
                ? policyText(locale, 'dictionaryFamily')
                : policyText(locale, 'resourceKey'),
            message: policyText(locale, lookupError),
          },
        ];

  return (
    <div style={{ display: 'grid', gap: tokens.spacing.lg }}>
      <h1>{policyText(locale, 'pageTitle')}</h1>
      <p>{policyText(locale, 'syntheticNotice')}</p>
      <div role="group" aria-label={policyText(locale, 'pageTitle')} style={{ display: 'flex', flexWrap: 'wrap', gap: tokens.spacing.sm }}>
        {AREAS.map((item) => (
          <Button
            key={item.id}
            pressed={area === item.id}
            variant={area === item.id ? 'primary' : 'secondary'}
            onClick={() => selectArea(item.id)}
          >
            {policyText(locale, item.labelKey)}
          </Button>
        ))}
      </div>
      <section aria-label={policyText(locale, 'review')} style={{ display: 'grid', gap: tokens.spacing.md }}>
        <FieldErrorSummary title={policyText(locale, 'errorSummary')} errors={lookupLinks} />
        <Form label={policyText(locale, 'review')} submitLabel={policyText(locale, 'review')} onSubmit={handleLookup}>
          <FormField
            id="policy-key"
            label={
              area === 'dictionaries'
                ? policyText(locale, 'dictionaryFamily')
                : policyText(locale, 'resourceKey')
            }
            value={input}
            onChange={(value) => {
              setInput(value);
              setLookupError(undefined);
            }}
            {...(lookupError === undefined ? {} : { error: policyText(locale, lookupError) })}
          />
        </Form>
      </section>

      <p role="status" data-testid="policy-status">
        {statusKey === undefined ? '' : policyText(locale, statusKey)}
      </p>
      {commandError === undefined ? null : StateView.forTransportError(commandError)}

      {applied === undefined ? null : (
        <PolicyReview
          applied={applied}
          organizationId={organizationId}
          reloadToken={reloadToken}
          runCommand={runCommand}
        />
      )}
    </div>
  );
}

/** API-backed policies page; the tenant key prevents cross-tenant state. */
export function PolicyPage() {
  const organizationId = useActiveOrganization();
  return <PolicyContent key={organizationId} organizationId={organizationId} />;
}
