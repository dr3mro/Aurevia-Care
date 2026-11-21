import { useActiveOrganization } from '../../api/activeOrganization';
import { ApiPage } from '../../components/ApiPage';
import { useRoute } from '../../app/router';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
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
  const { tokens } = useTheme();
  return (
    <div style={{ display: 'grid', gap: tokens.spacing.lg }}>
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
