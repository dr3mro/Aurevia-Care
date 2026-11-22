import { useActiveOrganization } from '../../api/activeOrganization';
import { ApiPage } from '../../components/ApiPage';
import { useRoute } from '../../app/router';
import { useI18n } from '../../i18n/I18nProvider';
import { useBreakpoint } from '../../theme/useBreakpoint';
import { getPatient } from './api';
import type { PatientViewResponse } from './domain';
import { toPatientOverview } from './domain';
import { patientsText } from './messages';
import { PatientOverviewScreen } from './PatientOverviewScreen';

/** Props for the tenant- and patient-keyed overview content. */
interface PatientOverviewContentProps {
  organizationId: string;
  patientId: string;
}

/** Load one effective patient view and render the overview states. */
function PatientOverviewContent({ organizationId, patientId }: PatientOverviewContentProps) {
  const { locale } = useI18n();
  const breakpoint = useBreakpoint();
  return (
    // A transparent `.stack` wrapper keeps the single content column and lets
    // long values wrap; `ApiPage` remains the only `.page` layout owner, so the
    // safe-area padding and reading-width cap are applied once, not twice. The
    // resolved breakpoint is exposed as a data attribute for jsdom structure
    // assertions. `overflow-wrap`
    // is inherited so long English and Arabic clinical values wrap instead of
    // forcing a horizontal scroll. No field is hidden and direction stays with
    // the document.
    <div className="stack" data-breakpoint={breakpoint} style={{ overflowWrap: 'anywhere' }}>
      <ApiPage
        resource={{
          label: patientsText(locale, 'overviewTitle'),
          load: () => getPatient(patientId, organizationId),
          deps: [patientId, organizationId],
        }}
      >
        {(view: PatientViewResponse) => (
          <PatientOverviewScreen
            overview={toPatientOverview(view, organizationId)}
            locale={locale}
          />
        )}
      </ApiPage>
    </div>
  );
}

/** Overview for `/patients/:id`; only the opaque id is taken from the route. */
export function PatientOverviewPage() {
  const organizationId = useActiveOrganization();
  const { params } = useRoute();
  const patientId = params.id ?? '';
  return (
    <PatientOverviewContent
      key={`${organizationId}:${patientId}`}
      organizationId={organizationId}
      patientId={patientId}
    />
  );
}
