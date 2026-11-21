import { EmptyState } from '../components/StateViews';
import { useI18n } from '../i18n/I18nProvider';
import { shellText } from './shellText';

/** Placeholder home route until the role-aware worklists are wired in. */
export function WelcomePage() {
  const { locale } = useI18n();
  return (
    <section aria-labelledby="welcome-title">
      <h2 id="welcome-title">{shellText(locale, 'welcome')}</h2>
      <EmptyState />
    </section>
  );
}
