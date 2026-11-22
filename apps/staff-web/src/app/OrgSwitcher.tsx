import { useState } from 'react';
import type { SyntheticOrganization } from '../fixtures/synthetic';
import { useI18n } from '../i18n/I18nProvider';
import { useTheme } from '../theme/ThemeProvider';
import { useBreakpoint } from '../theme/useBreakpoint';

/** Props for the active-organization placeholder. */
export interface OrgSwitcherProps {
  organizations: readonly SyntheticOrganization[];
}

/**
 * Select a synthetic active tenant; no membership check or endpoint call.
 *
 * The native select fills the inline space at every width (`.field` sets
 * `inline-size: 100%` and a 16px text size so a focused control does not zoom
 * the viewport on touch devices) and carries `.touch` for the 44px coarse
 * pointer target. The label and select share a `.grid-2`, so they stack on a
 * phone and sit side by side from the medium breakpoint upward without any
 * direction-specific styling. The outer `.page` supplies logical safe-area
 * padding; the fields grid and the confirmation wrap long English and Arabic
 * organization names. The `aria-live` output keeps the explicit switch
 * confirmation (IAM-01).
 * Behaviour, text and exported names are unchanged (WEB-07, UX-01).
 */
export function OrgSwitcher({ organizations }: OrgSwitcherProps) {
  const { t } = useI18n();
  const { tokens } = useTheme();
  const breakpoint = useBreakpoint();
  const [selectedId, setSelectedId] = useState(organizations[0]?.id ?? '');
  const selected = organizations.find((organization) => organization.id === selectedId);
  return (
    <div
      className="page"
      data-breakpoint={breakpoint}
      data-org-switcher-view={breakpoint === 'compact' ? 'full-width-select' : 'inline-fields'}
      style={{ color: tokens.foreground }}
    >
      <div className="grid-2" data-org-switcher-fields>
        <label htmlFor="active-organization">{t('organization')}</label>
        <select
          id="active-organization"
          className="field touch"
          value={selectedId}
          onChange={(event) => setSelectedId(event.target.value)}
        >
          {organizations.map((organization) => (
            <option key={organization.id} value={organization.id}>
              {organization.name}
            </option>
          ))}
        </select>
      </div>
      {/* responsive.css has wrapping rules for .grid-2 > * but not .page > *,
          and there is no .clinical-value class to opt into: keep the confirmed
          name wrapping with a logical inline style instead of editing CSS. */}
      <output aria-live="polite" style={{ overflowWrap: 'anywhere' }}>
        {selected?.name ?? ''}
      </output>
    </div>
  );
}
