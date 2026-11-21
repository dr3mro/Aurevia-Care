import { EmptyState } from '../components/StateViews';
import { useI18n } from '../i18n/I18nProvider';
import { shellText } from './shellText';

/** Catch-all route: a plain, data-free page for unregistered paths. */
export function NotFoundPage() {
  const { locale } = useI18n();
  return (
    <section aria-labelledby="not-found-title">
      <h2 id="not-found-title">{shellText(locale, 'notFound')}</h2>
      <EmptyState />
    </section>
  );
}
