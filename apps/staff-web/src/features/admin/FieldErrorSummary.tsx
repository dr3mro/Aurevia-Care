import { useTheme } from '../../theme/ThemeProvider';

/** One linked entry in a form's validation error summary. */
export interface FieldErrorLink {
  /** Id of the input the link moves focus to. */
  id: string;
  /** Localized field label used as the link text prefix. */
  label: string;
  /** Localized explanation of what to change. */
  message: string;
}

/** Props for a form-level validation summary. */
export interface FieldErrorSummaryProps {
  /** Localized heading, also used as the region's accessible name. */
  title: string;
  /** One link per invalid field; an empty list renders nothing. */
  errors: readonly FieldErrorLink[];
}

/**
 * Announce a failed submit once and link each message to its field, so a
 * keyboard or screen-reader user can jump straight to what must be corrected.
 */
export function FieldErrorSummary({ title, errors }: FieldErrorSummaryProps) {
  const { tokens } = useTheme();
  if (errors.length === 0) {
    return null;
  }
  return (
    <div role="alert" aria-label={title} style={{ display: 'grid', gap: tokens.spacing.sm }}>
      <h3>{title}</h3>
      <ul style={{ margin: 0, paddingInlineStart: tokens.spacing.lg }}>
        {errors.map((error) => (
          <li key={error.id}>
            <a href={`#${error.id}`}>
              {error.label}: {error.message}
            </a>
          </li>
        ))}
      </ul>
    </div>
  );
}
