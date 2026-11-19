import { useI18n } from '../i18n/I18nProvider';

/** Static sign-in placeholder; identity tooling owns real authentication. */
export function SignInPlaceholder() {
  const { t } = useI18n();
  return (
    <section>
      <h2>{t('signIn')}</h2>
      <p>{t('signInNote')}</p>
    </section>
  );
}
