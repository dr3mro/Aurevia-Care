import { useEffect, useRef } from 'react';
import type { ReactNode } from 'react';
import { useTheme } from '../theme/ThemeProvider';
import { Button } from './Button';

/** Props for a labelled modal placeholder. */
export interface DialogProps {
  open: boolean;
  title: string;
  closeLabel: string;
  onClose: () => void;
  children: ReactNode;
}

/** Render a labelled modal when open; render nothing when closed. */
export function Dialog({ open, title, closeLabel, onClose, children }: DialogProps) {
  const { tokens } = useTheme();
  const closeRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!open) {
      return undefined;
    }
    const previous = document.activeElement as HTMLElement | null;
    closeRef.current!.focus();
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        onClose();
      }
      // The close control is the only focusable element in this placeholder,
      // so keeping Tab on it prevents focus from reaching the obscured page.
      if (event.key === 'Tab') {
        event.preventDefault();
        closeRef.current!.focus();
      }
    };
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('keydown', onKeyDown);
      if (previous) {
        previous.focus();
      }
    };
  }, [open, onClose]);

  if (!open) {
    return null;
  }
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
        role="dialog"
        aria-modal="true"
        aria-labelledby="staff-dialog-title"
        style={{
          background: tokens.background,
          color: tokens.foreground,
          border: `1px solid ${tokens.border}`,
          padding: tokens.spacing.lg,
          minWidth: '280px',
        }}
      >
        <h2 id="staff-dialog-title">{title}</h2>
        <div>{children}</div>
        <Button buttonRef={closeRef} variant="secondary" onClick={onClose}>
          {closeLabel}
        </Button>
      </div>
    </div>
  );
}
