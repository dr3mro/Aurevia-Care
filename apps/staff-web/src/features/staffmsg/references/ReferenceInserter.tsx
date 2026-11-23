import { useId, useState } from 'react';
import { useI18n } from '../../../i18n/I18nProvider';
import { useTheme } from '../../../theme/ThemeProvider';
import { referenceText } from './labels';
import { screenSubject } from './screening';

/** Props for the subject field that guards reference attachment. */
export interface ReferenceInserterProps {
  /** Called with the entered subject when the user inserts it. */
  readonly onInsert: (subject: string) => void;
}

/**
 * Present a labelled subject field with an insert action and a live warning
 * when the text looks like it carries an identifier (MSG-07, WEB-09). The
 * warning is fixed translated copy for a reason code; the raw subject is never
 * rendered into the warning, a label, a title or a log.
 */
export function ReferenceInserter({ onInsert }: ReferenceInserterProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const [subject, setSubject] = useState('');
  const fieldId = useId();
  const warningId = `${fieldId}-warning`;
  const screening = screenSubject(subject);
  const warning = screening.suspicious ? referenceText(locale, screening.reason) : undefined;

  return (
    <div
      style={{
        display: 'grid',
        gap: tokens.spacing.sm,
        color: tokens.foreground,
        maxInlineSize: '44rem',
      }}
    >
      <label htmlFor={fieldId}>{referenceText(locale, 'subjectLabel')}</label>
      <input
        id={fieldId}
        value={subject}
        onChange={(event) => setSubject(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === 'Enter') {
            onInsert(subject);
          }
        }}
        aria-describedby={warning === undefined ? undefined : warningId}
        style={{
          color: tokens.foreground,
          background: tokens.background,
          border: `1px solid ${tokens.border}`,
          minInlineSize: 0,
        }}
      />
      {warning === undefined ? null : (
        <p id={warningId} role="alert" style={{ color: tokens.foreground }}>
          <span aria-hidden="true">⚠</span> {warning}
        </p>
      )}
      <button
        type="button"
        onClick={() => onInsert(subject)}
        style={{
          color: tokens.background,
          background: tokens.accent,
          border: `1px solid ${tokens.accent}`,
          minBlockSize: '24px',
        }}
      >
        {referenceText(locale, 'insert')}
      </button>
    </div>
  );
}
