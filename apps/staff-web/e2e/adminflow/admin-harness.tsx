import { StrictMode } from 'react';
import type { ReactElement } from 'react';
import { createRoot } from 'react-dom/client';
import { ActiveOrganizationProvider } from '../../src/api/activeOrganization';
import { ExportPage } from '../../src/features/dataexchange/ExportPage';
import { syntheticFacility } from '../../src/fixtures/synthetic';
import { I18nProvider, useI18n } from '../../src/i18n/I18nProvider';
import { ThemeProvider } from '../../src/theme/ThemeProvider';

/**
 * Dev-only harness for the tenant-admin export screen (WEB-10, OPS-07).
 *
 * The screen is real and takes a role override precisely so a role-specific
 * browser journey can mount it without changing the pre-login shell. The
 * locale switch and a fixed clock stay mounted across the journey, so the
 * expiry decision never depends on the wall clock. No real tenant, checksum
 * or patient datum is present.
 */

/** Fixed clock instant, before the synthetic job's retention instant. */
const now = '2026-11-23T09:00:00Z';

/** Stable tenant object; hoisted so React sees one identity per mount. */
const tenant = { organizationId: syntheticFacility.organizationId };

/** The real export screen under one persistent, visible locale switch. */
function Workstation(): ReactElement {
  const { locale, toggleLocale } = useI18n();
  const isReception = new URLSearchParams(window.location.search).get('role') === 'reception';
  return (
    <div style={{ display: 'grid', gap: '1rem' }}>
      <button type="button" onClick={toggleLocale}>
        {locale === 'en' ? 'العربية' : 'English'}
      </button>
      <ExportPage roleOverride={isReception ? 'reception' : 'tenant_admin'} now={now} />
    </div>
  );
}

/** Mount the harness tree with the same providers the staff portal uses. */
function Harness(): ReactElement {
  return (
    <StrictMode>
      <ThemeProvider>
        <I18nProvider>
          <ActiveOrganizationProvider value={tenant}>
            <Workstation />
          </ActiveOrganizationProvider>
        </I18nProvider>
      </ThemeProvider>
    </StrictMode>
  );
}

const container = document.getElementById('admin-harness');
if (container === null) {
  throw new Error('admin-harness container is required');
}
createRoot(container).render(<Harness />);
