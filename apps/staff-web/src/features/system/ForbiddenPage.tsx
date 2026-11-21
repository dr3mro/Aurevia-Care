import { navigate } from '../../app/router';
import { Button } from '../../components/Button';
import { ForbiddenState } from '../../components/StateViews';
import { useI18n } from '../../i18n/I18nProvider';
import { systemText } from './messages';

/** Fixed page for a role with no access; it explains without revealing data. */
export function ForbiddenPage() {
  const { locale } = useI18n();
  return (
    <>
      <ForbiddenState />
      <Button onClick={() => navigate('/')}>{systemText(locale, 'goHome')}</Button>
    </>
  );
}
