import { useState } from 'react';
import { MixedScriptText } from '../../components/MixedScriptText';
import { useI18n } from '../../i18n/I18nProvider';
import { useBreakpoint } from '../../theme/useBreakpoint';
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

/**
 * Compose tenant-scoped configuration, staff history, audit and access review.
 *
 * The screen is one logical `.page` grid: it occupies a single full-width
 * column on phones and centres to the shared reading width on larger viewports,
 * so long clinical values wrap instead of forcing a page-level horizontal
 * scroll. The access states use the `.stack` utility rather than a bespoke inline
 * grid. Direction stays with the document — no subtree overrides it, so the
 * same markup mirrors under right-to-left. The active layout band is exposed as
 * `data-breakpoint` because jsdom cannot evaluate the real media queries
 * (WEB-07, UX-01).
 */
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
  const breakpoint = useBreakpoint();
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
    <section className="page" data-breakpoint={breakpoint} aria-label={adminText(locale, 'admin')}>
      <h2>{adminText(locale, 'admin')}</h2>
      <p>{adminText(locale, 'syntheticNotice')}</p>
      {/* No aria-label: the live region's announcement must be exactly the
          visible result text, not a static accessible name (WCAG 4.1.3). */}
      <p role="status" data-testid="admin-status">
        <MixedScriptText
          text={proposal ? proposalAnnouncement(locale, proposal.facilityCode, proposal.version) : ''}
          lang={locale}
        />
      </p>
      <section className="stack" aria-label={adminText(locale, 'accessReview')}>
        <h3>{adminText(locale, 'accessReview')}</h3>
        {accessProbes.map((probe) => (
          <AccessStateNotice
            key={probe.id}
            state={resolveAccess(probe.viewer, probe.resourceOrganizationId)}
            label={probe.label}
          />
        ))}
      </section>
      {/* Key by tenant so an unsubmitted draft typed under one organization is
          never reconciled into the editor of another. The local value and date
          are dropped with the old instance instead of being carried into a
          command scoped to the new tenant (IAM-01, ARC-01). */}
      <ConfigEditor
        key={organizationId}
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
