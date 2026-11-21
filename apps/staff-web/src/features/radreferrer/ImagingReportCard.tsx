import type { Locale } from '../../i18n/messages';
import { useTheme } from '../../theme/ThemeProvider';
import type { ReportSection, SignedReport } from './domain';
import { reportSections } from './domain';
import { radreferrerText, sectionMessageKey } from './labels';

/** Props for the signed imaging report card shown in the patient chart. */
export interface ImagingReportCardProps {
  /** The signed, tenant-scoped interpretation to display. */
  report: SignedReport;
  /** Interface language; clinical free text keeps its own direction. */
  locale: Locale;
}

/**
 * Present a signed interpretation with its five narrated sections in clinical
 * order. Section headings are translated; the stored clinical narrative keeps
 * its own direction inside an isolated run so mixed-script text stays readable
 * under both LTR and RTL interfaces (RAD-01, WEB-09).
 */
export function ImagingReportCard({ report, locale }: ImagingReportCardProps) {
  const { tokens } = useTheme();
  const sections: ReportSection[] = reportSections(report);
  return (
    <article
      data-testid="imaging-report-card"
      aria-label={radreferrerText(locale, 'radreferrer')}
      style={{ color: tokens.foreground, backgroundColor: tokens.background }}
    >
      <header>
        <h2>{radreferrerText(locale, 'radreferrer')}</h2>
        <p data-testid="report-signed-by">
          {radreferrerText(locale, 'signedBy')}: <bdi dir="auto">{report.signer}</bdi>
        </p>
        <p data-testid="report-version">
          {radreferrerText(locale, 'version')} <bdi dir="ltr">{report.version}</bdi>
        </p>
        <p data-testid="report-signed-at">
          {radreferrerText(locale, 'signedAt')}: <bdi dir="ltr">{report.signedAt}</bdi>
        </p>
      </header>
      <dl data-testid="report-sections">
        {sections.map((section) => (
          <div key={section.key} data-section={section.key}>
            <dt>{radreferrerText(locale, sectionMessageKey[section.key])}</dt>
            <dd>
              <bdi dir="auto">{section.body}</bdi>
            </dd>
          </div>
        ))}
      </dl>
    </article>
  );
}
