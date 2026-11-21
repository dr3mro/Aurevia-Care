import type { Locale } from '../../i18n/messages';
import { Link } from '../../app/router';
import { MixedScriptText } from '../../components/MixedScriptText';
import { useTheme } from '../../theme/ThemeProvider';
import type { PatientOverview } from './domain';
import { formatDob, languageForName } from './domain';
import { patientsText } from './messages';

/** Props for the presentational patient overview. */
export interface PatientOverviewScreenProps {
  overview: PatientOverview;
  locale: Locale;
}

/**
 * Show one patient's identity, any likely-duplicate warnings computed by the
 * server, and a link to the longitudinal chart. No record is merged or linked.
 */
export function PatientOverviewScreen({ overview, locale }: PatientOverviewScreenProps) {
  const { tokens } = useTheme();
  const patient = overview.patient;
  return (
    <div style={{ display: 'grid', gap: tokens.spacing.lg }}>
      <header style={{ display: 'grid', gap: tokens.spacing.sm }}>
        <h2>{patientsText(locale, 'overviewTitle')}</h2>
        <p style={{ marginBlock: 0 }}>
          <MixedScriptText
            text={patient.displayName}
            lang={languageForName(patient.displayName)}
          />
          {' · '}
          <MixedScriptText text={patient.id} lang="en" />
        </p>
      </header>
      <section aria-label={patientsText(locale, 'identity')} style={{ display: 'grid', gap: tokens.spacing.sm }}>
        <h3>{patientsText(locale, 'identity')}</h3>
        <p>
          <b>{patientsText(locale, 'mrn')}:</b>{' '}
          <MixedScriptText text={patient.mrn} lang="en" />
        </p>
        <p>
          <b>{patientsText(locale, 'issuer')}:</b>{' '}
          <MixedScriptText text={patient.mrnIssuer} lang="en" />
        </p>
        <p>
          <b>{patientsText(locale, 'dob')}:</b>{' '}
          <MixedScriptText text={formatDob(patient, locale)} lang={locale} />
        </p>
        <p>
          <b>{patientsText(locale, 'language')}:</b>{' '}
          <MixedScriptText text={patient.language} lang="en" />
        </p>
        <p>
          <b>{patientsText(locale, 'version')}:</b> <span>{patient.version}</span>
        </p>
      </section>
      {overview.duplicates.length === 0 ? (
        <p>{patientsText(locale, 'noDuplicates')}</p>
      ) : (
        <section
          role="alert"
          aria-label={patientsText(locale, 'duplicateWarningTitle')}
          style={{ display: 'grid', gap: tokens.spacing.sm }}
        >
          <h3>{patientsText(locale, 'duplicateWarningTitle')}</h3>
          <p>{patientsText(locale, 'duplicateWarningDetail')}</p>
          <ul style={{ margin: 0, paddingInlineStart: tokens.spacing.lg }}>
            {overview.duplicates.map((candidate) => (
              <li key={`${patient.organizationId}:${candidate.id}`}>
                <MixedScriptText
                  text={candidate.displayName}
                  lang={languageForName(candidate.displayName)}
                />
                {' · '}
                <MixedScriptText text={candidate.mrn} lang="en" />
              </li>
            ))}
          </ul>
        </section>
      )}
      <p>
        <Link to={`/patients/${patient.id}/chart`}>{patientsText(locale, 'chartLink')}</Link>
      </p>
    </div>
  );
}
