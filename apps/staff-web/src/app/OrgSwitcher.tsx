import { useState } from 'react';
import type { SyntheticOrganization } from '../fixtures/synthetic';
import { useI18n } from '../i18n/I18nProvider';
import { useTheme } from '../theme/ThemeProvider';

/** Props for the active-organization placeholder. */
export interface OrgSwitcherProps {
  organizations: readonly SyntheticOrganization[];
}

/** Select a synthetic active tenant; no membership check or endpoint call. */
export function OrgSwitcher({ organizations }: OrgSwitcherProps) {
  const { t } = useI18n();
  const { tokens } = useTheme();
  const [selectedId, setSelectedId] = useState(organizations[0]?.id ?? '');
  const selected = organizations.find((organization) => organization.id === selectedId);
  return (
    <div style={{ display: 'grid', gap: tokens.spacing.sm }}>
      <label htmlFor="active-organization">{t('organization')}</label>
      <select
        id="active-organization"
        value={selectedId}
        onChange={(event) => setSelectedId(event.target.value)}
      >
        {organizations.map((organization) => (
          <option key={organization.id} value={organization.id}>
            {organization.name}
          </option>
        ))}
      </select>
      <output aria-live="polite">{selected?.name ?? ''}</output>
    </div>
  );
}
