import { useTheme } from '../../theme/ThemeProvider';
import { useBreakpoint, usePointerCoarse } from '../../theme/useBreakpoint';

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
 *
 * Layout uses only the shared responsive classes: `stack` keeps the heading
 * above the messages and gives every child wrapping room, and `touch` gives
 * each link a 44px coarse-pointer target without a local min-height that would
 * outrank the stylesheet. Logical inline styles keep long English and Arabic
 * messages wrappable with no horizontal scroll; direction is never set here.
 * The breakpoint and pointer data attributes make the layout observable in
 * tests without changing the summary's behavior, text or public API.
 */
export function FieldErrorSummary({ title, errors }: FieldErrorSummaryProps) {
  const { tokens } = useTheme();
  const breakpoint = useBreakpoint();
  const coarsePointer = usePointerCoarse();
  if (errors.length === 0) {
    return null;
  }
  return (
    <div
      role="alert"
      aria-label={title}
      className="stack"
      data-breakpoint={breakpoint}
      data-coarse-pointer={coarsePointer}
      style={{
        gap: tokens.spacing.sm,
        minInlineSize: 0,
        overflowWrap: 'anywhere',
        textAlign: 'start',
      }}
    >
      <h3>{title}</h3>
      <ul style={{ margin: 0, paddingInlineStart: tokens.spacing.lg }}>
        {errors.map((error) => (
          <li key={error.id}>
            <a
              href={`#${error.id}`}
              className="touch"
              style={{
                display: 'block',
                paddingBlock: tokens.spacing.sm,
                paddingInline: tokens.spacing.sm,
                overflowWrap: 'anywhere',
                whiteSpace: 'normal',
                textAlign: 'start',
              }}
            >
              {error.label}: {error.message}
            </a>
          </li>
        ))}
      </ul>
    </div>
  );
}
