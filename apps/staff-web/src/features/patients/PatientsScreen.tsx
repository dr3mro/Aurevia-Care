import type { Locale } from '../../i18n/messages';
import { Link } from '../../app/router';
import { MixedScriptText } from '../../components/MixedScriptText';
import { useTheme } from '../../theme/ThemeProvider';
import { useBreakpoint } from '../../theme/useBreakpoint';
import type { Patient } from './domain';
import { formatDob, languageForName } from './domain';
import { patientsText } from './messages';

/** Props for the presentational patient search results table. */
export interface PatientsScreenProps {
  patients: readonly Patient[];
  locale: Locale;
}

/**
 * Render the patient results as an accessible table. Names are isolated as
 * bidirectional runs and the opaque id is the only value placed in a URL.
 *
 * The table, caption and `<th scope>` headers are identical at every width: no
 * clinical column is added, removed or hidden. On compact viewports the table
 * sits in a labelled, keyboard-scrollable region so a long identifier can be
 * reached by inline scrolling without a page-level horizontal scroll (WEB-07,
 * UX-01). Headers and cells wrap long English and Arabic values and keep no
 * fixed block size, so text stays readable at 200 percent zoom.
 */
export function PatientsScreen({ patients, locale }: PatientsScreenProps) {
  const { tokens } = useTheme();
  const breakpoint = useBreakpoint();
  const caption = patientsText(locale, 'resultsCaption');

  // Logical wrapping: a long clinical value may shrink and wrap instead of
  // forcing the grid or the viewport wider than the available inline space.
  const wrappingStyle = {
    minInlineSize: 0,
    overflowWrap: 'anywhere',
    whiteSpace: 'normal',
  } as const;

  const table = (
    <table style={{ borderCollapse: 'collapse', inlineSize: '100%' }}>
      <caption style={{ textAlign: 'start', paddingBlock: tokens.spacing.sm }}>{caption}</caption>
      <thead>
        <tr>
          <th scope="col" style={wrappingStyle}>
            {patientsText(locale, 'colName')}
          </th>
          <th scope="col" style={wrappingStyle}>
            {patientsText(locale, 'colMrn')}
          </th>
          <th scope="col" style={wrappingStyle}>
            {patientsText(locale, 'colIssuer')}
          </th>
          <th scope="col" style={wrappingStyle}>
            {patientsText(locale, 'colDob')}
          </th>
        </tr>
      </thead>
      <tbody>
        {patients.map((patient) => (
          <tr key={`${patient.organizationId}:${patient.id}`}>
            <th scope="row" style={wrappingStyle}>
              {/* A grid wrapper stretches the shared Link across the 44 px
                  coarse-pointer floor without changing the Link primitive. */}
              <div className="touch" style={{ display: 'grid' }}>
                <Link to={`/patients/${patient.id}`}>
                  <MixedScriptText
                    text={patient.displayName}
                    lang={languageForName(patient.displayName)}
                  />
                </Link>
              </div>
            </th>
            <td style={wrappingStyle}>
              <MixedScriptText text={patient.mrn} lang="en" />
            </td>
            <td style={wrappingStyle}>
              <MixedScriptText text={patient.mrnIssuer} lang="en" />
            </td>
            <td style={wrappingStyle}>
              <MixedScriptText text={formatDob(patient, locale)} lang={locale} />
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );

  // The region is a layout affordance only: the table keeps its caption,
  // headers and every clinical column at each breakpoint.
  if (breakpoint === 'compact') {
    return (
      <div className="table-region" role="region" aria-label={caption} tabIndex={0}>
        {table}
      </div>
    );
  }

  return table;
}
