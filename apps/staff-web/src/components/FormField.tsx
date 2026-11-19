import { useTheme } from '../theme/ThemeProvider';

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

/** A labeled field that binds hint/error text through aria-describedby. */
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
  return (
    <div style={{ display: 'grid', gap: tokens.spacing.sm }}>
      <label htmlFor={id}>{label}</label>
      <input
        id={id}
        type={type}
        value={value}
        placeholder={placeholder}
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? `${id}-error` : undefined}
        onChange={(event) => onChange(event.target.value)}
        style={{
          minHeight: '32px',
          padding: tokens.spacing.sm,
          border: `1px solid ${tokens.border}`,
          background: tokens.background,
          color: tokens.foreground,
        }}
      />
      {error ? (
        <p id={`${id}-error`} role="alert">
          {error}
        </p>
      ) : null}
    </div>
  );
}
