import { Button } from '../../components/Button';
import { defined } from '../../components/invariant';
import { formatCivilDate } from '../../i18n/format';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
import { schedulingAnchorCivilDate } from './fixtures';
import type { ReceptionFacility } from './domain';
import { schedulingText } from './messages';

/** Props for the reception Today header. */
export interface ReceptionHeaderProps {
  facilities: readonly ReceptionFacility[];
  facilityCode: string;
  onFacilityChange: (code: string) => void;
  onNewBooking: () => void;
}

/**
 * Show the synthetic day, selected facility and its time zone, plus the
 * facility, language and booking controls. The language switch uses the shared
 * provider so document direction and every message update together; all layout
 * is logical so the same markup mirrors in Arabic (UX-01).
 */
export function ReceptionHeader({
  facilities,
  facilityCode,
  onFacilityChange,
  onNewBooking,
}: ReceptionHeaderProps) {
  const { locale, setLocale } = useI18n();
  const { tokens } = useTheme();
  const facility = defined(
    facilities.find((candidate) => candidate.code === facilityCode),
    'selected reception facility',
  );

  return (
    <header
      style={{
        display: 'flex',
        flexWrap: 'wrap',
        alignItems: 'center',
        justifyContent: 'space-between',
        gap: tokens.spacing.md,
        inlineSize: '100%',
        minInlineSize: 0,
        overflowWrap: 'anywhere',
      }}
    >
      <div style={{ display: 'grid', gap: tokens.spacing.sm, minInlineSize: 0 }}>
        <h1 style={{ marginBlock: 0 }}>{schedulingText(locale, 'today')}</h1>
        <p style={{ marginBlock: 0, minInlineSize: 0, overflowWrap: 'anywhere' }}>
          {formatCivilDate(schedulingAnchorCivilDate, locale)}
          {' · '}
          <span>{facility.name}</span>
          {' · '}
          {schedulingText(locale, 'timeZoneLabel')} <span>{facility.timeZone}</span>
        </p>
      </div>
      <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: tokens.spacing.sm }}>
        <label htmlFor="reception-facility">{schedulingText(locale, 'facilitySelect')}</label>
        <select
          id="reception-facility"
          className="field touch"
          value={facilityCode}
          onChange={(event) => onFacilityChange(event.target.value)}
        >
          {facilities.map((candidate) => (
            <option key={candidate.code} value={candidate.code}>
              {candidate.name}
            </option>
          ))}
        </select>
        <div role="group" aria-label={schedulingText(locale, 'languageLabel')} style={{ display: 'flex', gap: tokens.spacing.sm }}>
          <Button
            pressed={locale === 'en'}
            onClick={() => setLocale('en')}
            variant={locale === 'en' ? 'primary' : 'secondary'}
          >
            {schedulingText(locale, 'english')}
          </Button>
          <Button
            pressed={locale === 'ar'}
            onClick={() => setLocale('ar')}
            variant={locale === 'ar' ? 'primary' : 'secondary'}
          >
            {schedulingText(locale, 'arabic')}
          </Button>
        </div>
        <Button variant="primary" onClick={onNewBooking}>
          {schedulingText(locale, 'newBooking')}
        </Button>
      </div>
    </header>
  );
}
