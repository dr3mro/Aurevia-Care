import { StrictMode } from 'react';
import type { ReactElement } from 'react';
import { createRoot } from 'react-dom/client';
import { ActiveOrganizationProvider } from '../../src/api/activeOrganization';
import { PatientsPage } from '../../src/features/patients/PatientsPage';
import { alphaOrganizationId, schedulingAnchorCivilDate } from '../../src/features/scheduling/fixtures';
import { SchedulingPage } from '../../src/features/scheduling/SchedulingPage';
import { I18nProvider, useI18n } from '../../src/i18n/I18nProvider';
import { ThemeProvider } from '../../src/theme/ThemeProvider';

/**
 * Dev-only browser harness for the reception journey (WEB-10, CLN-01).
 *
 * The two real pages are mounted together with the providers the staff portal
 * uses, so the browser exercises the same components, commands and translation
 * dictionaries as production. The worklist is pinned to the synthetic Monday
 * anchor so its rows are deterministic. Vite dev serves this file; it never
 * enters the production build.
 */

/** Stable tenant context object; hoisted so React sees one identity per mount. */
const tenant = { organizationId: alphaOrganizationId };

/** The front desk: patient registration beside the worklist and booking form. */
function ReceptionWorkstation(): ReactElement {
  const { locale, toggleLocale } = useI18n();
  return (
    <>
      <button type="button" onClick={toggleLocale}>
        {locale === 'en' ? 'العربية' : 'English'}
      </button>
      <PatientsPage />
      <SchedulingPage anchorCivilDate={schedulingAnchorCivilDate} />
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
            <ReceptionWorkstation />
          </ActiveOrganizationProvider>
        </I18nProvider>
      </ThemeProvider>
    </StrictMode>
  );
}

const container = document.getElementById('reception-harness');
if (container === null) {
  throw new Error('reception-harness container is required');
}
createRoot(container).render(<Harness />);
