import type { ReactNode } from 'react';
import { useI18n } from '../i18n/I18nProvider';

/** Props for the visual protected-route placeholder. */
export interface ProtectedRouteProps {
  signedIn?: boolean;
  children?: ReactNode;
}

/** Conceal children until an explicit flag is set; no session logic here. */
export function ProtectedRoute({ signedIn = false, children }: ProtectedRouteProps) {
  const { t } = useI18n();
  return (
    <section aria-label={t('protectedArea')}>
      {signedIn ? children : <p role="status">{t('protectedNote')}</p>}
    </section>
  );
}
