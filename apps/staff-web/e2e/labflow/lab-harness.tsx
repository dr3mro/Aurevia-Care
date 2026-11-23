import { StrictMode, useState } from 'react';
import type { ReactElement } from 'react';
import { createRoot } from 'react-dom/client';
import { ActiveOrganizationProvider } from '../../src/api/activeOrganization';
import { CollectionDeskPage } from '../../src/features/labcollection/CollectionDeskPage';
import { ResultEntryPage } from '../../src/features/labresults/ResultEntryPage';
import { alphaOrganizationId } from '../../src/features/scheduling/fixtures';
import { I18nProvider, useI18n } from '../../src/i18n/I18nProvider';
import { ThemeProvider } from '../../src/theme/ThemeProvider';

/**
 * Dev-only browser harness for the bilingual lab journey (WEB-10, LAB-01).
 *
 * The collection and result-entry pages are real screens with real providers and
 * real transport. They are mounted directly because the pre-login staff shell
 * hard-codes a reception role and denies lab routes; an isolated harness is the
 * documented way to render a role-specific screen without changing production
 * authorization. The locale switch stays mounted across the in-harness
 * navigation, so an Arabic journey does not lose its direction between steps.
 */

/** Stable tenant context object; hoisted so React sees one identity per mount. */
const tenant = { organizationId: alphaOrganizationId };

/** The two screens of the lab journey. */
type LabView = 'collection' | 'results';

/** The collector and verifier workstations behind one visible locale switch. */
function LabWorkstation(): ReactElement {
  const { locale, toggleLocale } = useI18n();
  const [view, setView] = useState<LabView>('collection');
  return (
    <>
      <button type="button" onClick={toggleLocale}>
        {locale === 'en' ? 'العربية' : 'English'}
      </button>
      <button type="button" data-testid="lab-open-results" onClick={() => setView('results')}>
        Open result entry
      </button>
      {view === 'collection' ? <CollectionDeskPage /> : <ResultEntryPage />}
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
            <LabWorkstation />
          </ActiveOrganizationProvider>
        </I18nProvider>
      </ThemeProvider>
    </StrictMode>
  );
}

const container = document.getElementById('lab-harness');
if (container === null) {
  throw new Error('lab-harness container is required');
}
createRoot(container).render(<Harness />);
