import { formatCivilDate } from '../../i18n/format';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
import type { Goal } from './domain';
import { goalStateMessageKey, therapyText } from './messages';

/** Props for the treatment goals table. */
export interface GoalsListProps {
  goals: readonly Goal[];
}

/** Render goals with their civil target dates and explicit state words. */
export function GoalsList({ goals }: GoalsListProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const text = (key: Parameters<typeof therapyText>[1]) => therapyText(locale, key);

  return (
    <section aria-label={text('goals')} style={{ display: 'grid', gap: tokens.spacing.sm }}>
      <h3>{text('goals')}</h3>
      <table style={{ borderCollapse: 'collapse', width: '100%' }}>
        <thead>
          <tr>
            <th scope="col">{text('goal')}</th>
            <th scope="col">{text('target')}</th>
            <th scope="col">{text('state')}</th>
          </tr>
        </thead>
        <tbody>
          {goals.length === 0 ? (
            <tr>
              <td colSpan={3}>{text('noReferrals')}</td>
            </tr>
          ) : (
            goals.map((goal) => (
              <tr key={goal.id}>
                <th scope="row">{goal.description}</th>
                <td>{formatCivilDate(goal.targetOn, locale)}</td>
                <td>{text(goalStateMessageKey[goal.state])}</td>
              </tr>
            ))
          )}
        </tbody>
      </table>
    </section>
  );
}
