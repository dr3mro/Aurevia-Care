import type { Locale } from '../../i18n/messages';
import { Link } from '../../app/router';
import { MixedScriptText } from '../../components/MixedScriptText';
import { useTheme } from '../../theme/ThemeProvider';
import { useBreakpoint } from '../../theme/useBreakpoint';
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
 *
 * The root uses the responsive `page` wrapper: its single-column grid and
 * logical safe-area padding keep every value readable from 320 px upward and
 * mirror under right-to-left. Identity fields share one `grid-2` container so
 * they stack on a phone and sit side by side from the medium breakpoint without
 * hiding a clinical field. The resolved band is exposed as `data-breakpoint`
 * because jsdom cannot evaluate a media query; width changes layout only, never
 * the values, actions or clinical meaning shown here.
 */
export function PatientOverviewScreen({ overview, locale }: PatientOverviewScreenProps) {
  const { tokens } = useTheme();
  const breakpoint = useBreakpoint();
  const patient = overview.patient;
  return (
    <div className="page" data-breakpoint={breakpoint}>
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
        <div className="grid-2" data-part="identity-fields">
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
        </div>
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
        <Link to={`/patients/${patient.id}/chart`}>
          {/* Link owns the anchor but exposes no className, so the shared
              .touch class sits on its content: the anchor's clickable box then
              inherits the 24px fine and 44px coarse target floors. */}
          <span className="touch" style={{ display: 'inline-flex', alignItems: 'center' }}>
            {patientsText(locale, 'chartLink')}
          </span>
        </Link>
      </p>
    </div>
  );
}
