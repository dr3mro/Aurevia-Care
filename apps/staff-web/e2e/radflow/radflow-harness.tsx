import { StrictMode } from 'react';
import type { ReactElement } from 'react';
import { createRoot } from 'react-dom/client';
import { ActiveOrganizationProvider } from '../../src/api/activeOrganization';
import { RadWorklistPage } from '../../src/features/radworklist/RadWorklistPage';
import { syntheticFacility } from '../../src/fixtures/synthetic';
import { I18nProvider, useI18n } from '../../src/i18n/I18nProvider';
import { ThemeProvider } from '../../src/theme/ThemeProvider';

/**
 * Dev-only harness for the imaging worklist screen (WEB-10, RAD-01).
 *
 * The screen is a real routed page, but the pre-login shell hard-codes a
 * reception role and denies the radiology route. Mounting it here with the
 * real providers exercises the same component and transport without changing
 * production authorization. The locale switch stays mounted for the whole
 * journey, so an Arabic case keeps its document direction between steps.
 */

/** Stable tenant object; hoisted so React sees one identity per mount. */
const tenant = { organizationId: syntheticFacility.organizationId };

/** The worklist under one persistent, visible locale switch. */
function Workstation(): ReactElement {
  const { locale, toggleLocale } = useI18n();
  return (
    <>
      <button type="button" onClick={toggleLocale}>
        {locale === 'en' ? 'العربية' : 'English'}
      </button>
      <RadWorklistPage />
    </>
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

const container = document.getElementById('radworklist-harness');
if (container === null) {
  throw new Error('radworklist-harness container is required');
}
createRoot(container).render(<Harness />);
