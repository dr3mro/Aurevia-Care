import { AppShell } from './app/AppShell';
import { RouterProvider } from './app/router';
import { routes } from './app/routes';
import { I18nProvider } from './i18n/I18nProvider';
import { ThemeProvider } from './theme/ThemeProvider';

const patterns = routes.map((route) => route.pattern);

/** Compose the application providers around the router and the staff shell. */
export function App() {
  return (
    <ThemeProvider>
      <I18nProvider>
        <RouterProvider patterns={patterns}>
          <AppShell />
        </RouterProvider>
      </I18nProvider>
    </ThemeProvider>
  );
}
