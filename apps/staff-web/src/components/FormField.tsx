import { useTheme } from '../theme/ThemeProvider';
import { useBreakpoint, usePointerCoarse } from '../theme/useBreakpoint';

/** Props for a labeled input with an optional accessible error message. */
export interface FormFieldProps {
  id: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
  error?: string;
  type?: 'text' | 'email' | 'password';
  placeholder?: string;
}

/**
 * A labeled field that binds hint/error text through aria-describedby.
 *
 * Layout uses only the shared responsive classes: `stack` keeps the label
 * above the control and gives every direct child wrapping room, `field` fills
 * the inline space with 16px text and a logical focus ring, and `touch` keeps a
 * 44px target for coarse pointers. Direction is never set here; the logical
 * properties in those classes mirror under right-to-left. The breakpoint and
 * pointer data attributes make the layout class observable in tests without
 * changing the field's behavior or API.
 */
export function FormField({
  id,
  label,
  value,
  onChange,
  error,
  type = 'text',
  placeholder,
}: FormFieldProps) {
  const { tokens } = useTheme();
  const breakpoint = useBreakpoint();
  const coarsePointer = usePointerCoarse();
  const hasError = error !== undefined && error !== '';
  return (
    <div
      className="stack"
      data-breakpoint={breakpoint}
      data-coarse-pointer={coarsePointer}
      style={{ gap: tokens.spacing.sm }}
    >
      <label htmlFor={id}>{label}</label>
      <input
        id={id}
        type={type}
        value={value}
        placeholder={placeholder}
        className="field touch"
        aria-invalid={hasError ? true : undefined}
        aria-describedby={hasError ? `${id}-error` : undefined}
        onChange={(event) => onChange(event.target.value)}
        style={{
          paddingBlock: tokens.spacing.sm,
          paddingInline: tokens.spacing.sm,
          border: `1px solid ${tokens.border}`,
          background: tokens.background,
          color: tokens.foreground,
        }}
      />
      {hasError ? (
        <p id={`${id}-error`} role="alert">
          {error}
        </p>
      ) : null}
    </div>
  );
}
