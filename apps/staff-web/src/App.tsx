import { lazy, Suspense } from 'react';
import { AppShell } from './app/AppShell';
import {
  alphaOrganizationId as adminOrganizationId,
  adminAnchorCivilDate,
  adminConfigurationKey,
  syntheticAccessProbes,
  syntheticAuditEvents,
  syntheticConfigurations,
  syntheticStaffEvents,
} from './features/admin/fixtures';
import {
  alphaOrganizationId as chartOrganizationId,
  syntheticCharts,
} from './features/chart/fixtures';
import {
  alphaOrganizationId as schedulingOrganizationId,
  syntheticAppointments,
} from './features/scheduling/fixtures';
import { syntheticFacility, syntheticStaff } from './fixtures/synthetic';
import { I18nProvider } from './i18n/I18nProvider';
import { ThemeProvider } from './theme/ThemeProvider';

/*
 * One dynamic import per feature screen gives Vite a separate chunk for each
 * route, which is what the WEB-08 budget measures. The small adapters at the
 * boundary supply synthetic fixtures; screens never read fixtures themselves.
 * READY-07 will replace the fallback with LoadingState once it is merged.
 */
const SchedulingScreen = lazy(() => import('./features/scheduling/SchedulingScreen').then(
  ({ SchedulingScreen: Screen }) => ({
    default: () => (
      <Screen appointments={syntheticAppointments} organizationId={schedulingOrganizationId} />
    ),
  }),
));

const ChartScreen = lazy(() => import('./features/chart/ChartScreen').then(
  ({ ChartScreen: Screen }) => ({
    default: () => <Screen charts={syntheticCharts} organizationId={chartOrganizationId} />,
  }),
));

const AdminScreen = lazy(() => import('./features/admin/AdminScreen').then(
  ({ AdminScreen: Screen }) => ({
    default: () => (
      <Screen
        organizationId={adminOrganizationId}
        configurations={syntheticConfigurations}
        staffEvents={syntheticStaffEvents}
        auditEvents={syntheticAuditEvents}
        accessProbes={syntheticAccessProbes}
        configKey={adminConfigurationKey}
        subject={syntheticStaff.subject!}
        facilityCode={syntheticFacility.code}
        anchorCivilDate={adminAnchorCivilDate}
      />
    ),
  }),
));

/** Compose the application providers around the shell and its route chunks. */
export function App() {
  return (
    <ThemeProvider>
      <I18nProvider>
        <AppShell>
          <Suspense fallback={<p role="status">Loading</p>}>
            <SchedulingScreen />
            <ChartScreen />
            <AdminScreen />
          </Suspense>
        </AppShell>
      </I18nProvider>
    </ThemeProvider>
  );
}
