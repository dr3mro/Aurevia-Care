import { StrictMode, useState } from 'react';
import type { ReactElement } from 'react';
import { createRoot } from 'react-dom/client';
import { ActiveOrganizationProvider, useActiveOrganization } from '../../src/api/activeOrganization';
import { syntheticFacility } from '../../src/fixtures/synthetic';
import { I18nProvider, useI18n } from '../../src/i18n/I18nProvider';
import type { Locale } from '../../src/i18n/messages';
import { ThemeProvider } from '../../src/theme/ThemeProvider';
import { expectedPatientId } from './synthetic-therapy-api';

/**
 * Dev-only physical-therapy workstation (WEB-10, PT-01).
 *
 * The navigation rail declares `/therapy` but no screen is implemented yet.
 * This mounts a small, typed component with the same providers as the portal so
 * an English and an Arabic journey can exercise the real synthetic boundary.
 * It is not a production entry point and carries no real patient data.
 */

/** Visible outcome of one accept command. */
type EpisodeStatus = 'pending' | 'accepted' | 'refused';

/** One table of harness words; the clinical page does not exist yet. */
interface HarnessWords {
  heading: string;
  intro: string;
  patientLabel: string;
  planLabel: string;
  accept: string;
  pending: string;
  accepted: string;
  refused: string;
}

/** Identical keys in both locales; the locale switch is mounted throughout. */
const words: Record<Locale, HarnessWords> = {
  en: {
    heading: 'Therapy referral',
    intro: 'Synthetic only: match the patient alias before starting the episode.',
    patientLabel: 'Patient alias from the referral',
    planLabel: 'Synthetic plan code',
    accept: 'Accept episode',
    pending: 'No episode has been accepted.',
    accepted: 'Episode accepted.',
    refused: 'Episode refused: the patient alias does not match this referral.',
  },
  ar: {
    heading: 'إحالة العلاج الطبيعي',
    intro: 'بيانات توضيحية فقط: طابق معرّف المريض قبل بدء الحلقة.',
    patientLabel: 'معرّف المريض من الإحالة',
    planLabel: 'رمز الخطة التوضيحية',
    accept: 'قبول الحلقة',
    pending: 'لم تُقبل أي حلقة بعد.',
    accepted: 'قُبلت الحلقة.',
    refused: 'رُفضت الحلقة: معرّف المريض لا يطابق هذه الإحالة.',
  },
};

/** Stable tenant object; hoisted so React sees one identity per mount. */
const tenant = { organizationId: syntheticFacility.organizationId };

/** The workstation under one persistent, visible locale switch. */
function Workstation(): ReactElement {
  const { locale, toggleLocale } = useI18n();
  const organizationId = useActiveOrganization();
  const text = words[locale];
  const [patientId, setPatientId] = useState(expectedPatientId);
  const [plan, setPlan] = useState('PT-SYNTHETIC-PLAN');
  const [status, setStatus] = useState<EpisodeStatus>('pending');

  async function accept(): Promise<void> {
    const response = await fetch('/api/v1/therapy/episodes', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Active-Organization': organizationId,
        'X-Correlation-Id': 'synthetic-correlation-therapy',
        'X-XSRF-TOKEN': 'synthetic-csrf-token',
        'Idempotency-Key': 'synthetic-therapy-accept',
      },
      body: JSON.stringify({ patientId, plan }),
    });
    setStatus(response.ok ? 'accepted' : 'refused');
  }

  return (
    <div style={{ display: 'grid', gap: '1rem' }}>
      <button type="button" onClick={toggleLocale}>
        {locale === 'en' ? 'العربية' : 'English'}
      </button>
      <h2>{text.heading}</h2>
      <p>{text.intro}</p>
      <label htmlFor="therapy-patient">{text.patientLabel}</label>
      <input
        id="therapy-patient"
        value={patientId}
        onChange={(event) => setPatientId(event.target.value)}
      />
      <label htmlFor="therapy-plan">{text.planLabel}</label>
      <input id="therapy-plan" value={plan} onChange={(event) => setPlan(event.target.value)} />
      <button type="button" onClick={() => void accept()}>
        {text.accept}
      </button>
      <p role="status" data-testid="therapy-status" data-status={status}>
        {text[status]}
      </p>
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

const container = document.getElementById('therapy-harness');
if (container === null) {
  throw new Error('therapy-harness container is required');
}
createRoot(container).render(<Harness />);
