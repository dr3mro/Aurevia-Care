import { useRef, useState } from 'react';
import { Button } from '../../components/Button';
import { Form } from '../../components/Form';
import { FormField } from '../../components/FormField';
import { MixedScriptText } from '../../components/MixedScriptText';
import { formatCivilDate } from '../../i18n/format';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
import {
  canEnable,
  displaySubject,
  hasBackupCoverage,
  subjectLanguage,
  validateProfile,
} from './domain';
import type { ProfileDraft, RefusalReason, ServiceProfile } from './types';
import { refusalMessageKey, unitsText } from './messages';
import type { UnitsMessageKey } from './messages';

/** Success messages announced after a command is accepted by the page. */
type StatusKey = 'proposed' | 'approved' | 'unitEnabled' | 'unitDisabled';

/** Props for the tenant-scoped care-unit service profile editor. */
export interface UnitsScreenProps {
  profile: ServiceProfile;
  /** Civil `YYYY-MM-DD` used to refuse a past effective date. */
  todayCivilDate: string;
  onPropose: (draft: ProfileDraft) => Promise<boolean>;
  onApprove: () => Promise<boolean>;
  onEnable: () => Promise<boolean>;
  onDisable: () => Promise<boolean>;
}

/** Build the editable draft from the currently published profile. */
function initialDraft(profile: ServiceProfile): ProfileDraft {
  return {
    ...profile.details,
    approver: profile.approval.approver,
    effectiveFrom: profile.approval.effectiveFrom,
  };
}

/**
 * Presentational editor for service hours, response targets, topics, urgent
 * contact instructions, named owner and backup coverage (MSG-06). Changes
 * carry a named approval and a future effective date (ADM-01); the screen
 * validates locally before handing a command to the container and never
 * contacts the network itself.
 */
export function UnitsScreen({
  profile,
  todayCivilDate,
  onPropose,
  onApprove,
  onEnable,
  onDisable,
}: UnitsScreenProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const [draft, setDraft] = useState<ProfileDraft>(() => initialDraft(profile));
  const [refusal, setRefusal] = useState<RefusalReason | null>(null);
  const [status, setStatus] = useState<StatusKey | null>(null);
  const summaryRef = useRef<HTMLParagraphElement>(null);
  const text = (key: UnitsMessageKey) => unitsText(locale, key);

  const published = profile.details;
  const backupMissing = !hasBackupCoverage(published);

  /** Update one draft field; a corrected field clears the previous refusal. */
  function update(field: keyof ProfileDraft, value: string) {
    setDraft((current) => ({ ...current, [field]: value }));
    setRefusal(null);
  }

  async function propose() {
    const validation = validateProfile(draft, todayCivilDate);
    if (!validation.ok) {
      setRefusal(validation.reason);
      setStatus(null);
      summaryRef.current!.focus();
      return;
    }
    const accepted = await onPropose(draft);
    if (!accepted) {
      setRefusal(null);
      setStatus(null);
      return;
    }
    setRefusal(null);
    setStatus('proposed');
  }

  async function enable() {
    if (!canEnable(profile)) {
      setRefusal('missing-owner');
      setStatus(null);
      summaryRef.current!.focus();
      return;
    }
    const accepted = await onEnable();
    if (!accepted) {
      setRefusal(null);
      setStatus(null);
      return;
    }
    setRefusal(null);
    setStatus('unitEnabled');
  }

  async function disable() {
    const accepted = await onDisable();
    if (!accepted) {
      setRefusal(null);
      setStatus(null);
      return;
    }
    setRefusal(null);
    setStatus('unitDisabled');
  }

  async function approve() {
    const accepted = await onApprove();
    if (!accepted) {
      setRefusal(null);
      setStatus(null);
      return;
    }
    setRefusal(null);
    setStatus('approved');
  }

  return (
    <div style={{ display: 'grid', gap: tokens.spacing.lg }}>
      <h2>{text('serviceProfile')}</h2>
      <p>{text('syntheticNotice')}</p>

      <section aria-label={text('publishedProfile')} style={{ display: 'grid', gap: tokens.spacing.md }}>
        <h3>{text('publishedProfile')}</h3>
        <dl style={{ display: 'grid', gap: tokens.spacing.sm, margin: 0 }}>
          <div>
            <dt>{text('unit')}</dt>
            <dd><code dir="ltr">{profile.unitId}</code></dd>
          </div>
          <div>
            <dt>{text('version')}</dt>
            <dd>{profile.version}</dd>
          </div>
          <div>
            <dt>{text('serviceHours')}</dt>
            <dd>{published.serviceHours}</dd>
          </div>
          <div>
            <dt>{text('responseTargetUrgent')}</dt>
            <dd>{published.responseTargetUrgent}</dd>
          </div>
          <div>
            <dt>{text('responseTargetRoutine')}</dt>
            <dd>{published.responseTargetRoutine}</dd>
          </div>
          <div>
            <dt>{text('topics')}</dt>
            <dd>{published.topics}</dd>
          </div>
          <div>
            <dt>{text('urgentContactInstructions')}</dt>
            <dd>{published.urgentContactInstructions}</dd>
          </div>
          <div>
            <dt>{text('queueOwner')}</dt>
            <dd>
              <MixedScriptText
                text={displaySubject(published.queueOwner, text('notRecorded'))}
                lang={subjectLanguage(published.queueOwner)}
              />
            </dd>
          </div>
          <div>
            <dt>{text('backupCoverage')}</dt>
            <dd>
              <MixedScriptText
                text={displaySubject(published.backupCoverage, text('notRecorded'))}
                lang={subjectLanguage(published.backupCoverage)}
              />
            </dd>
          </div>
        </dl>
        {backupMissing ? (
          <section
            role="status"
            aria-label={text('noBackupWarning')}
            data-testid="units-warning"
            style={{ borderInlineStart: `2px solid ${tokens.accent}`, paddingInlineStart: tokens.spacing.md }}
          >
            <h4>{text('noBackupWarning')}</h4>
            <p>{text('noBackupDetail')}</p>
          </section>
        ) : null}
      </section>

      <p role="status" data-testid="units-status" style={{ margin: 0 }}>
        {status === null ? '' : text(status)}
      </p>
      <p
        ref={summaryRef}
        role="alert"
        tabIndex={-1}
        data-testid="units-refusal"
        style={{ margin: 0 }}
      >
        {refusal === null ? '' : text(refusalMessageKey[refusal])}
      </p>

      <div style={{ display: 'flex', gap: tokens.spacing.md, flexWrap: 'wrap' }}>
        {profile.enabled ? (
          <Button onClick={disable}>{text('disableUnit')}</Button>
        ) : (
          <Button onClick={enable}>{text('enableUnit')}</Button>
        )}
        {profile.approval.approvedAt === null ? (
          <span>
            {text('pendingApproval')} <Button onClick={approve}>{text('approve')}</Button>
          </span>
        ) : (
          <span>{text('approvedBadge')}</span>
        )}
      </div>

      <Form label={text('serviceProfile')} submitLabel={text('proposeChanges')} onSubmit={propose}>
        <p>{text('requiredHint')}</p>
        <FormField id="units-hours" label={text('serviceHours')} value={draft.serviceHours} onChange={(value) => update('serviceHours', value)} />
        <FormField id="units-urgent-target" label={text('responseTargetUrgent')} value={draft.responseTargetUrgent} onChange={(value) => update('responseTargetUrgent', value)} />
        <FormField id="units-routine-target" label={text('responseTargetRoutine')} value={draft.responseTargetRoutine} onChange={(value) => update('responseTargetRoutine', value)} />
        <FormField id="units-topics" label={text('topics')} value={draft.topics} onChange={(value) => update('topics', value)} />
        <FormField id="units-urgent-instructions" label={text('urgentContactInstructions')} value={draft.urgentContactInstructions} onChange={(value) => update('urgentContactInstructions', value)} />
        <FormField
          id="units-owner"
          label={text('queueOwner')}
          value={draft.queueOwner}
          onChange={(value) => update('queueOwner', value)}
          error={refusal === 'missing-owner' ? text('missingOwner') : ''}
        />
        <FormField id="units-backup" label={text('backupCoverage')} value={draft.backupCoverage} onChange={(value) => update('backupCoverage', value)} />
        <FormField
          id="units-approver"
          label={text('namedApprover')}
          value={draft.approver}
          onChange={(value) => update('approver', value)}
          error={refusal === 'missing-approver' ? text('missingApprover') : ''}
        />
        <FormField
          id="units-effective"
          label={text('effectiveFrom')}
          value={draft.effectiveFrom}
          onChange={(value) => update('effectiveFrom', value)}
          placeholder="2026-03-10"
          error={
            refusal === 'invalid-effective-date' || refusal === 'past-effective-date'
              ? text(refusalMessageKey[refusal])
              : ''
          }
        />
        <p>{text('effectiveFrom')}: <time dateTime={profile.approval.effectiveFrom}>{formatCivilDate(profile.approval.effectiveFrom, locale)}</time></p>
      </Form>
    </div>
  );
}
