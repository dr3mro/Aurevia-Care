import { useState } from 'react';
import { MixedScriptText } from '../../components/MixedScriptText';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
import { AccessStateNotice } from './AccessStateNotice';
import { AuditEventList } from './AuditEventList';
import { ConfigEditor } from './ConfigEditor';
import { StaffHistoryView } from './StaffHistoryView';
import type {
  AccessProbe,
  AuditEvent,
  ConfigurationDraft,
  ConfigurationVersion,
  StaffHistoryEvent,
} from './domain';
import { proposeVersion, resolveAccess } from './domain';
import { adminText, proposalAnnouncement } from './messages';

/** Coordinates of the last locally proposed version, re-localized on render. */
interface LocalProposal {
  facilityCode: string;
  version: number;
}

/** Props for the composed synthetic administration and audit screen. */
export interface AdminScreenProps {
  organizationId: string;
  configurations: readonly ConfigurationVersion[];
  staffEvents: readonly StaffHistoryEvent[];
  auditEvents: readonly AuditEvent[];
  accessProbes: readonly AccessProbe[];
  configKey: string;
  subject: string;
  facilityCode: string;
  anchorCivilDate: string;
}

/** Compose tenant-scoped configuration, staff history, audit and access review. */
export function AdminScreen({
  organizationId,
  configurations,
  staffEvents,
  auditEvents,
  accessProbes,
  configKey,
  subject,
  facilityCode,
  anchorCivilDate,
}: AdminScreenProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const [proposals, setProposals] = useState<ConfigurationVersion[]>([]);
  const [proposal, setProposal] = useState<LocalProposal | null>(null);
  const [proposalOrganizationId, setProposalOrganizationId] = useState(organizationId);

  // Reset synchronously during render when the active organization changes. A
  // passive effect would leave the previous tenant's announcement painted for
  // one committed frame; React discards this render and re-runs it immediately
  // after these state updates, so a tenant-local identifier never crosses over.
  if (organizationId !== proposalOrganizationId) {
    setProposalOrganizationId(organizationId);
    setProposals([]);
    setProposal(null);
  }

  const visibleConfigurations = [...configurations, ...proposals];

  function handlePropose(draft: ConfigurationDraft) {
    const created = proposeVersion(
      visibleConfigurations,
      organizationId,
      configKey,
      facilityCode,
      draft,
    );
    setProposals((current) => [...current, created]);
    setProposal({ facilityCode: created.facilityCode, version: created.version });
  }

  return (
    <section
      aria-label={adminText(locale, 'admin')}
      style={{ display: 'grid', gap: tokens.spacing.lg }}
    >
      <h2>{adminText(locale, 'admin')}</h2>
      <p>{adminText(locale, 'syntheticNotice')}</p>
      <p role="status" data-testid="admin-status" aria-label={adminText(locale, 'proposalStatus')}>
        <MixedScriptText
          text={proposal ? proposalAnnouncement(locale, proposal.facilityCode, proposal.version) : ''}
          lang={locale}
        />
      </p>
      <section aria-label={adminText(locale, 'accessReview')} style={{ display: 'grid', gap: tokens.spacing.sm }}>
        <h3>{adminText(locale, 'accessReview')}</h3>
        {accessProbes.map((probe) => (
          <AccessStateNotice
            key={probe.id}
            state={resolveAccess(probe.viewer, probe.resourceOrganizationId)}
            label={probe.label}
          />
        ))}
      </section>
      <ConfigEditor
        entries={visibleConfigurations}
        organizationId={organizationId}
        configKey={configKey}
        anchorCivilDate={anchorCivilDate}
        onPropose={handlePropose}
      />
      <StaffHistoryView events={staffEvents} organizationId={organizationId} subject={subject} />
      <AuditEventList events={auditEvents} organizationId={organizationId} />
    </section>
  );
}
