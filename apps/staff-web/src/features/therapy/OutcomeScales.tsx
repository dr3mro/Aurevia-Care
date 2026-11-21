import { formatInstant, formatMeasurement } from '../../i18n/format';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
import type { OutcomeScale } from './domain';
import { therapyText } from './messages';

/** Props for the versioned outcome scales panel. */
export interface OutcomeScalesProps {
  scales: readonly OutcomeScale[];
}

/** Render each scale version, its unit and its units-bearing measurements. */
export function OutcomeScales({ scales }: OutcomeScalesProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const text = (key: Parameters<typeof therapyText>[1]) => therapyText(locale, key);

  return (
    <section aria-label={text('outcomeScales')} style={{ display: 'grid', gap: tokens.spacing.sm }}>
      <h3>{text('outcomeScales')}</h3>
      {scales.length === 0 ? (
        <p>{text('noReferrals')}</p>
      ) : (
        scales.map((scale) => (
          <article key={scale.id} style={{ display: 'grid', gap: tokens.spacing.sm }}>
            <h4>
              {scale.name} ({scale.code}) · {text('version')} {scale.scaleVersion}
            </h4>
            <p>
              {text('unit')}: {scale.unit}
            </p>
            <table style={{ borderCollapse: 'collapse', width: '100%' }}>
              <thead>
                <tr>
                  <th scope="col">{text('recorded')}</th>
                  <th scope="col">{text('measurements')}</th>
                </tr>
              </thead>
              <tbody>
                {scale.measurements.map((measurement) => (
                  <tr key={measurement.id}>
                    <td>{formatInstant(measurement.recordedAt, 'UTC', locale)}</td>
                    <td>{formatMeasurement(measurement.value, measurement.unit, locale)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </article>
        ))
      )}
    </section>
  );
}
