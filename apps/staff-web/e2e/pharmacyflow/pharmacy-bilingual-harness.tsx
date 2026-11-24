import { StrictMode, useState } from 'react';
import type { ReactElement } from 'react';
import { createRoot } from 'react-dom/client';
import { ActiveOrganizationProvider, useActiveOrganization } from '../../src/api/activeOrganization';
import { RouterProvider } from '../../src/app/router';
import { PharmacyItemPage } from '../../src/features/pharmacyinbox/PharmacyItemPage';
import { syntheticFacility } from '../../src/fixtures/synthetic';
import { I18nProvider, useI18n } from '../../src/i18n/I18nProvider';
import { ThemeProvider } from '../../src/theme/ThemeProvider';

/**
 * Dev-only harness for the pharmacy workflow screen (WEB-10, PHARM-08).
 *
 * The screen is a real routed page, but the pre-login shell hard-codes a
 * reception role and denies `/pharmacy`. Mounting it here with the real
 * providers and router exercises the same component and typed client without
 * changing production authorization. A single route control stands in for the
 * not-yet-routed prescriber action and goes through the same stub boundary.
 */

/** Opaque prescription identifier shared with the synthetic pharmacy API. */
const prescriptionId = 'rx-synthetic-001';

/** Stable tenant object; hoisted so React sees one identity per mount. */
const tenant = { organizationId: syntheticFacility.organizationId };

/** Harness-only labels; the screen itself supplies every clinical word. */
const labels = {
  en: { route: 'Prescribe and route', routed: 'Delivery confirmed', waiting: 'No prescription has been routed yet.' },
  ar: { route: 'وصف وتوجيه', routed: 'تم تأكيد التسليم', waiting: 'لم يتم توجيه أي وصفة بعد.' },
} as const;

/** The route control, confirmation and the real prescription detail screen. */
function Workstation(): ReactElement {
  const { locale, toggleLocale } = useI18n();
  const organizationId = useActiveOrganization();
  const [routed, setRouted] = useState(false);
  const [status, setStatus] = useState('Not routed');
  const text = labels[locale];

  async function route(): Promise<void> {
    const response = await fetch('/api/v1/pharmacy/inbox/route', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Active-Organization': organizationId,
        'X-Correlation-Id': 'synthetic-correlation-pharmacy',
        'X-XSRF-TOKEN': 'synthetic-csrf-token',
      },
      body: JSON.stringify({ prescriptionId, patientId: 'SYN-PATIENT-001' }),
    });
    if (response.ok) {
      setRouted(true);
      setStatus(text.routed);
    } else {
      setStatus('Refused');
    }
  }

  return (
    <div style={{ display: 'grid', gap: '1rem' }}>
      <button type="button" onClick={toggleLocale}>
        {locale === 'en' ? 'العربية' : 'English'}
      </button>
      <button type="button" data-testid="pharmacy-route" onClick={() => void route()}>
        {text.route}
      </button>
      <output data-testid="pharmacy-route-status">{status}</output>
      {routed ? (
        <RouterProvider patterns={['/pharmacy/:id']} initialPath={`/pharmacy/${prescriptionId}`}>
          <PharmacyItemPage />
        </RouterProvider>
      ) : (
        <p data-testid="pharmacy-waiting">{text.waiting}</p>
      )}
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

const container = document.getElementById('pharmacy-bilingual-harness');
if (container === null) {
  throw new Error('pharmacy-bilingual-harness container is required');
}
createRoot(container).render(<Harness />);
