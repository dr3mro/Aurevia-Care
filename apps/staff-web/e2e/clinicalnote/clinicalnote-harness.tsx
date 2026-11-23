import { StrictMode } from 'react';
import type { ReactElement } from 'react';
import { createRoot } from 'react-dom/client';
import { ActiveOrganizationProvider } from '../../src/api/activeOrganization';
import { RouterProvider } from '../../src/app/router';
import { alphaOrganizationId } from '../../src/features/chart/fixtures';
import { NotePage } from '../../src/features/chart/NotePage';
import { I18nProvider, useI18n } from '../../src/i18n/I18nProvider';
import { ThemeProvider } from '../../src/theme/ThemeProvider';

/**
 * Dev-only browser harness for the clinician note journey (WEB-10, CLN-05).
 *
 * The note page reads its patient and note identifiers from the route, so the
 * harness mounts the real router with the production note pattern and a pinned
 * synthetic path. All other providers match the staff portal. Vite dev serves
 * this file; it never enters the production build.
 */

/** Stable tenant context object; hoisted so React sees one identity per mount. */
const tenant = { organizationId: alphaOrganizationId };
/** The production note pattern, reused so `useRoute` binds the same parameters. */
const notePatterns = ['/patients/:id/notes/:noteId'];
/** Opaque local identifiers bound by the router; never patient names. */
const notePath = '/patients/patient-100/notes/note-syn-1';

/** The note workspace with the harness-owned locale toggle. */
function NoteWorkstation(): ReactElement {
  const { locale, toggleLocale } = useI18n();
  return (
    <>
      <button type="button" onClick={toggleLocale}>
        {locale === 'en' ? 'العربية' : 'English'}
      </button>
      <NotePage />
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
            <RouterProvider patterns={notePatterns} initialPath={notePath}>
              <NoteWorkstation />
            </RouterProvider>
          </ActiveOrganizationProvider>
        </I18nProvider>
      </ThemeProvider>
    </StrictMode>
  );
}

const container = document.getElementById('clinicalnote-harness');
if (container === null) {
  throw new Error('clinicalnote-harness container is required');
}
createRoot(container).render(<Harness />);
