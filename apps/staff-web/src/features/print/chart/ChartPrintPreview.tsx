import { MixedScriptText } from '../../../components/MixedScriptText';
import { displayLocalName } from '../../../i18n/format';
import type { Locale } from '../../../i18n/messages';
import { useTheme } from '../../../theme/ThemeProvider';
import type { ChartPrintPreview, LeftOutItem } from './domain';
import { chartPrintText, chartSectionMessageKey, leftOutReasonKey } from './labels';

/** Props for the read-only preview shown before a print is confirmed. */
export interface ChartPrintPreviewProps {
  locale: Locale;
  model: ChartPrintPreview;
}

/** Render omitted items with a plain-language reason for each, never silently. */
function LeftOutList({ items, locale }: { items: readonly LeftOutItem[]; locale: Locale }) {
  const { tokens } = useTheme();
  return (
    <section
      aria-label={chartPrintText(locale, 'leftOut')}
      style={{ display: 'grid', gap: tokens.spacing.sm }}
    >
      <h4 style={{ marginBlock: 0 }}>{chartPrintText(locale, 'leftOut')}</h4>
      {items.length === 0 ? (
        <p>{chartPrintText(locale, 'leftOutEmpty')}</p>
      ) : (
        <ul aria-label={chartPrintText(locale, 'leftOut')}>
          {items.map((item) => (
            <li key={item.id}>
              <MixedScriptText text={item.title} lang={locale} /> —{' '}
              {chartPrintText(locale, leftOutReasonKey[item.reason])}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

/** Render the chosen scope, included sections and omissions for one handout. */
export function ChartPrintPreview({ locale, model }: ChartPrintPreviewProps) {
  const { tokens } = useTheme();
  if (model.kind === 'report') {
    return (
      <section style={{ display: 'grid', gap: tokens.spacing.sm }} data-print-preview="report">
        <h3 style={{ marginBlock: 0 }}>{chartPrintText(locale, 'reportPrint')}</h3>
        <p>
          <MixedScriptText text={model.title} lang={locale} />
        </p>
        {model.provenance !== undefined ? (
          <p>
            <b>{chartPrintText(locale, 'provenance')}:</b>{' '}
            <MixedScriptText text={model.provenance} lang={locale} />
          </p>
        ) : null}
        {model.printable ? null : <p role="status">{chartPrintText(locale, 'notPrintable')}</p>}
        <LeftOutList items={model.leftOut} locale={locale} />
      </section>
    );
  }
  return (
    <section style={{ display: 'grid', gap: tokens.spacing.sm }} data-print-preview="chart">
      <h3 style={{ marginBlock: 0 }}>{chartPrintText(locale, 'chartPrint')}</h3>
      <p>
        <b>{chartPrintText(locale, 'patient')}:</b>{' '}
        <MixedScriptText text={displayLocalName(model.patientName, locale)} lang={locale} />
      </p>
      {model.scope.kind === 'visit' ? (
        <p>
          <b>{chartPrintText(locale, 'visitId')}:</b> <code dir="ltr">{model.scope.visitId}</code>
        </p>
      ) : (
        <p>
          <b>{chartPrintText(locale, 'from')}:</b>{' '}
          <time dateTime={model.scope.from}>{model.scope.from}</time> ·{' '}
          <b>{chartPrintText(locale, 'to')}:</b>{' '}
          <time dateTime={model.scope.to}>{model.scope.to}</time>
        </p>
      )}
      <h4 style={{ marginBlock: 0 }}>{chartPrintText(locale, 'included')}</h4>
      <ul aria-label={chartPrintText(locale, 'included')}>
        {model.included.map((item) => (
          <li key={item.section}>
            <MixedScriptText
              text={`${chartPrintText(locale, chartSectionMessageKey[item.section])}: ${item.count}`}
              lang={locale}
            />
          </li>
        ))}
      </ul>
      <LeftOutList items={model.leftOut} locale={locale} />
      <p>{chartPrintText(locale, 'syntheticNotice')}</p>
    </section>
  );
}
