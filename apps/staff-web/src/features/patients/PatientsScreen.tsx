import type { Locale } from '../../i18n/messages';
import { Link } from '../../app/router';
import { MixedScriptText } from '../../components/MixedScriptText';
import { useTheme } from '../../theme/ThemeProvider';
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
 */
export function PatientsScreen({ patients, locale }: PatientsScreenProps) {
  const { tokens } = useTheme();
  return (
    <table style={{ borderCollapse: 'collapse', width: '100%' }}>
      <caption style={{ textAlign: 'start', paddingBlock: tokens.spacing.sm }}>
        {patientsText(locale, 'resultsCaption')}
      </caption>
      <thead>
        <tr>
          <th scope="col">{patientsText(locale, 'colName')}</th>
          <th scope="col">{patientsText(locale, 'colMrn')}</th>
          <th scope="col">{patientsText(locale, 'colIssuer')}</th>
          <th scope="col">{patientsText(locale, 'colDob')}</th>
        </tr>
      </thead>
      <tbody>
        {patients.map((patient) => (
          <tr key={`${patient.organizationId}:${patient.id}`}>
            <th scope="row">
              <Link to={`/patients/${patient.id}`}>
                <MixedScriptText
                  text={patient.displayName}
                  lang={languageForName(patient.displayName)}
                />
              </Link>
            </th>
            <td>
              <MixedScriptText text={patient.mrn} lang="en" />
            </td>
            <td>
              <MixedScriptText text={patient.mrnIssuer} lang="en" />
            </td>
            <td>
              <MixedScriptText text={formatDob(patient, locale)} lang={locale} />
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
