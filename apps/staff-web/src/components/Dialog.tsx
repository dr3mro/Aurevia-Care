import { useEffect, useRef } from 'react';
import type { CSSProperties, ReactNode } from 'react';
import { defined } from './invariant';
import { useTheme } from '../theme/ThemeProvider';
import { useBreakpoint } from '../theme/useBreakpoint';

/** Native controls inside a dialog that participate in the Tab cycle. */
const focusableSelector =
  'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

/** Props for a labelled modal that adapts to the available inline space. */
export interface DialogProps {
  open: boolean;
  title: string;
  closeLabel: string;
  onClose: () => void;
  children: ReactNode;
}

/**
 * Render a labelled modal when open; render nothing when closed.
 *
 * Below the medium breakpoint the panel is a full-screen sheet with a sticky
 * heading and action row, page-scroll locking and logical safe-area padding
 * from the `sheet` class. From medium upward the same panel is a centred dialog
 * with a maximum width. Direction is inherited from the document, so every
 * inline style uses logical properties and the same DOM mirrors under RTL.
 * Tab cycles through focusable controls inside the dialog and focus returns to
 * the opener on close (WEB-07, UX-01).
 */
export function Dialog({ open, title, closeLabel, onClose, children }: DialogProps) {
  const { tokens } = useTheme();
  const breakpoint = useBreakpoint();
  const compact = breakpoint === 'compact';
  const closeRef = useRef<HTMLButtonElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) {
      return undefined;
    }
    const previous = document.activeElement as HTMLElement | null;
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    defined(closeRef.current, 'dialog close button').focus();
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        onClose();
        return;
      }
      if (event.key === 'Tab') {
        const focusables = Array.from(
          defined(panelRef.current, 'dialog panel').querySelectorAll<HTMLElement>(
            focusableSelector,
          ),
        );
        const first = defined(focusables[0], 'first focusable element');
        const last = defined(focusables[focusables.length - 1], 'last focusable element');
        const active = document.activeElement;
        if (event.shiftKey) {
          if (active === first) {
            event.preventDefault();
            last.focus();
          }
        } else if (active === last) {
          event.preventDefault();
          first.focus();
        }
      }
    };
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('keydown', onKeyDown);
      document.body.style.overflow = previousOverflow;
      if (previous) {
        previous.focus();
      }
    };
  }, [open, onClose]);

  if (!open) {
    return null;
  }

  // Logical inline styles for the sticky chrome: responsive.css has no
  // dialog-specific sticky-header/action classes, so these use logical
  // properties and inherit the direction from the document.
  const stickyHeader: CSSProperties | undefined = compact
    ? {
        position: 'sticky',
        insetBlockStart: '0px',
        zIndex: 1,
        background: tokens.background,
        paddingBlock: tokens.spacing.md,
        paddingInline: tokens.spacing.lg,
        borderBlockEnd: `1px solid ${tokens.border}`,
      }
    : undefined;
  const stickyActions: CSSProperties | undefined = compact
    ? {
        position: 'sticky',
        insetBlockEnd: '0px',
        zIndex: 1,
        background: tokens.background,
        paddingBlock: tokens.spacing.md,
        paddingInline: tokens.spacing.lg,
        borderBlockStart: `1px solid ${tokens.border}`,
      }
    : undefined;
  const scrollPadding: CSSProperties | undefined = compact
    ? { scrollPaddingBlockStart: '3rem', scrollPaddingBlockEnd: '4rem' }
    : undefined;

  return (
    <div
      style={{
        position: 'fixed',
        inset: 0,
        background: 'rgba(0, 0, 0, 0.45)',
        display: 'grid',
        placeItems: 'center',
      }}
    >
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby="staff-dialog-title"
        className="sheet"
        data-breakpoint={breakpoint}
        data-dialog-view={compact ? 'sheet' : 'centered'}
        style={{
          background: tokens.background,
          color: tokens.foreground,
          border: `1px solid ${tokens.border}`,
          overflowWrap: 'anywhere',
          ...scrollPadding,
        }}
      >
        <h2 id="staff-dialog-title" data-dialog-region="header" style={stickyHeader}>
          {title}
        </h2>
        <div className="stack">{children}</div>
        <div data-dialog-region="actions" style={stickyActions}>
          <button
            ref={closeRef}
            type="button"
            className="touch"
            onClick={onClose}
            style={{
              paddingBlock: tokens.spacing.sm,
              paddingInline: tokens.spacing.md,
              border: `1px solid ${tokens.border}`,
              borderRadius: tokens.spacing.sm,
              background: 'transparent',
              color: tokens.foreground,
              cursor: 'pointer',
              font: 'inherit',
            }}
          >
            {closeLabel}
          </button>
        </div>
      </div>
    </div>
  );
}
