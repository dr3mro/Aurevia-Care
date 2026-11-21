import { formatCivilDate, formatMeasurement } from '../../i18n/format';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
import type { Episode } from './domain';
import { therapyText } from './messages';

/** Props for the read-only episode evaluation and plan summary. */
export interface EpisodeOverviewProps {
  episode: Episode;
}

/** Show the evaluation, problems, baseline, goals plan and care plan of an episode. */
export function EpisodeOverview({ episode }: EpisodeOverviewProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const text = (key: Parameters<typeof therapyText>[1]) => therapyText(locale, key);

  return (
    <section aria-label={text('episodeOverview')} style={{ display: 'grid', gap: tokens.spacing.sm }}>
      <h3>{text('episodeOverview')}</h3>
      <dl style={{ display: 'grid', gap: tokens.spacing.sm }}>
        <dt>{text('evaluation')}</dt>
        <dd>{episode.evaluation}</dd>
        <dt>{text('plannedFrequency')}</dt>
        <dd>{episode.plannedFrequency}</dd>
        <dt>{text('plannedDuration')}</dt>
        <dd>{episode.plannedDuration}</dd>
      </dl>
      <h4>{text('problems')}</h4>
      <ul>
        {episode.problems.map((problem) => (
          <li key={problem}>{problem}</li>
        ))}
      </ul>
      <h4>{text('baseline')}</h4>
      <ul>
        {episode.baseline.map((measure) => (
          <li key={measure.id}>
            {measure.name}: {formatMeasurement(measure.value, measure.unit, locale)} ·{' '}
            {formatCivilDate(measure.measuredOn, locale)}
          </li>
        ))}
      </ul>
      <h4>{text('interventions')}</h4>
      <ul>
        {episode.interventions.map((intervention) => (
          <li key={intervention}>{intervention}</li>
        ))}
      </ul>
    </section>
  );
}
