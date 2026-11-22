import type { ReactNode, Ref } from 'react';
import { useTheme } from '../theme/ThemeProvider';
import { useBreakpoint } from '../theme/useBreakpoint';

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
  const breakpoint = useBreakpoint();
  const primary = variant === 'primary';
  return (
    <button
      ref={buttonRef}
      type={type}
      disabled={disabled}
      onClick={onClick}
      data-variant={variant}
      data-breakpoint={breakpoint}
      aria-pressed={pressed}
      // The stylesheet's .touch rule owns both target floors (24px fine,
      // 44px coarse); an inline min-height would outrank its 44px rule.
      className="touch"
      style={{
        // Logical padding mirrors under RTL; the physical shorthand does not.
        paddingBlock: tokens.spacing.sm,
        paddingInline: tokens.spacing.md,
        // Long English and Arabic labels wrap instead of forcing a page scroll.
        overflowWrap: 'anywhere',
        whiteSpace: 'normal',
        textAlign: 'start',
        // Best-effort scroll clearance for a focused action; the sticky form
        // footer owns keeping the primary action reachable above the keyboard.
        scrollMarginBlockEnd: tokens.spacing.lg,
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
