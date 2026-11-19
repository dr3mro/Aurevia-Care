import type { ReactNode, Ref } from 'react';
import { useTheme } from '../theme/ThemeProvider';

/** Props for an accessible action button. */
export interface ButtonProps {
  children: ReactNode;
  onClick?: () => void;
  variant?: 'primary' | 'secondary';
  disabled?: boolean;
  type?: 'button' | 'submit';
  pressed?: boolean;
  buttonRef?: Ref<HTMLButtonElement>;
}

/** A tokenized button with an explicit variant and native focus behavior. */
export function Button({
  children,
  onClick,
  variant = 'primary',
  disabled = false,
  type = 'button',
  pressed,
  buttonRef,
}: ButtonProps) {
  const { tokens } = useTheme();
  const primary = variant === 'primary';
  return (
    <button
      ref={buttonRef}
      type={type}
      disabled={disabled}
      onClick={onClick}
      data-variant={variant}
      aria-pressed={pressed}
      style={{
        minHeight: '32px',
        padding: `${tokens.spacing.sm} ${tokens.spacing.md}`,
        border: `1px solid ${tokens.border}`,
        borderRadius: tokens.spacing.sm,
        background: primary ? tokens.accent : 'transparent',
        color: primary ? tokens.background : tokens.foreground,
        cursor: disabled ? 'not-allowed' : 'pointer',
      }}
    >
      {children}
    </button>
  );
}
