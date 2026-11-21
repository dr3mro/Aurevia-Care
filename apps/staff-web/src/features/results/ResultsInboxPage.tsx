import { useActiveOrganization } from '../../api/activeOrganization';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
import { ReconciliationList } from './ReconciliationList';
import { ResultReview } from './ResultReview';
import { resultText } from './messages';

/** The tenant-scoped content of the results inbox. */
function ResultsInboxContent({ organizationId }: { organizationId: string }) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  return (
    <div style={{ display: 'grid', gap: tokens.spacing.lg }}>
      <header>
        <h1>{resultText(locale, 'results')}</h1>
        <p>{resultText(locale, 'resultsIntro')}</p>
      </header>
      <ReconciliationList organizationId={organizationId} locale={locale} />
      <ResultReview organizationId={organizationId} locale={locale} />
    </div>
  );
}

/** `/results`: remounting on an organization switch clears all previous rows. */
export function ResultsInboxPage() {
  const organizationId = useActiveOrganization();
  return <ResultsInboxContent key={organizationId} organizationId={organizationId} />;
}
