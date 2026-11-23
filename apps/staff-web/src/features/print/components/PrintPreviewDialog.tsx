import { useEffect, useRef, useState } from 'react';
import type { ReactNode, RefObject } from 'react';
import { Button } from '../../../components/Button';
import { ErrorState, ForbiddenState, LoadingState } from '../../../components/StateViews';
import { useI18n } from '../../../i18n/I18nProvider';
import { useTheme } from '../../../theme/ThemeProvider';
import { focusableItems } from '../focus';
import { printStyleId, printStylesheet } from '../styles/printStyles';
import { printReasonKey, printText, refusalReasonKey } from './messages';
import type { HandoutLanguage, PrintReason, RefusalReason } from './messages';

export type { HandoutLanguage, PrintReason, RefusalReason } from './messages';

/**
 * Lifecycle of a handout preview, supplied by the owning screen. The dialog
 * never fetches or decides: a refusal is a server or policy decision and is
 * displayed as words, never inferred from presentation (UX-01, CLN-07).
 */
export type PrintPreviewState =
  | { kind: 'loading' }
  | { kind: 'error'; correlationId?: string; onRetry?: () => void }
  | { kind: 'forbidden' }
  | { kind: 'refused'; reason: RefusalReason }
  | { kind: 'ready'; documentUrl: string; title: string };

/** Props for the shared handout preview dialog. */
export interface PrintPreviewDialogProps {
  open: boolean;
  state: PrintPreviewState;
  onPrint: (reason: PrintReason, language: HandoutLanguage) => void;
  onDownload: (language: HandoutLanguage) => void;
  onClose: () => void;
}

/** Ordered reason choices; the order is stable in both directions. */
const printReasons: readonly PrintReason[] = ['give-to-patient', 'referral', 'patient-request'];

/**
 * Shared modal preview for a patient handout. Displays the PDF, a language and
 * a reason choice, and the Print, Download and Close actions. The reason is
 * required before Print, matching the audit reason the API records (WEB-09).
 */
export function PrintPreviewDialog(props: PrintPreviewDialogProps) {
  if (!props.open) {
    return null;
  }
  // The body mounts only while open, so each opening starts with a fresh
  // language, reason and focus state.
  return <PrintDialogBody {...props} />;
}

/** Move focus into the modal, contain Tab inside it and restore focus on unmount. */
function useModalFocus(
  containerRef: RefObject<HTMLDivElement | null>,
  closeRef: RefObject<HTMLButtonElement | null>,
  onClose: () => void,
): void {
  useEffect(() => {
    // The previous focus is always an element in a live document; restore it
    // when the modal unmounts so the opener keeps its place (WCAG 2.4.3).
    const previous = document.activeElement as HTMLElement;
    closeRef.current?.focus();
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault();
        onClose();
        return;
      }
      if (event.key !== 'Tab') {
        return;
      }
      const items = focusableItems(containerRef.current);
      const index = items.indexOf(document.activeElement as HTMLElement);
      event.preventDefault();
      if (event.shiftKey) {
        items[index <= 0 ? items.length - 1 : index - 1]?.focus();
      } else {
        items[index < 0 || index === items.length - 1 ? 0 : index + 1]?.focus();
      }
    };
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('keydown', onKeyDown);
      previous.focus();
    };
  }, [onClose, containerRef, closeRef]);
}

/** Modal body with focus containment and the state-driven content. */
function PrintDialogBody({
  state,
  onPrint,
  onDownload,
  onClose,
}: Omit<PrintPreviewDialogProps, 'open'>) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const [reason, setReason] = useState<PrintReason | null>(null);
  const [language, setLanguage] = useState<HandoutLanguage>(locale);
  const [showReasonError, setShowReasonError] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);
  const closeRef = useRef<HTMLButtonElement>(null);
  const reasonErrorRef = useRef<HTMLParagraphElement>(null);

  useModalFocus(containerRef, closeRef, onClose);

  useEffect(() => {
    if (showReasonError) {
      reasonErrorRef.current?.focus();
    }
  }, [showReasonError]);

  const print = () => {
    if (reason === null) {
      setShowReasonError(true);
      return;
    }
    setShowReasonError(false);
    onPrint(reason, language);
  };

  let content: ReactNode;
  if (state.kind === 'loading') {
    content = <LoadingState />;
  } else if (state.kind === 'error') {
    content = <ErrorState correlationId={state.correlationId} onRetry={state.onRetry} />;
  } else if (state.kind === 'forbidden') {
    content = <ForbiddenState />;
  } else if (state.kind === 'refused') {
    content = (
      <section
        role="alert"
        aria-live="assertive"
        style={{ display: 'grid', gap: tokens.spacing.sm }}
      >
        <h3>{printText(locale, 'refusal')}</h3>
        <p>{printText(locale, refusalReasonKey[state.reason])}</p>
        <p>{printText(locale, 'refusalDetail')}</p>
      </section>
    );
  } else {
    content = (
      <>
        <iframe
          data-print-handout="true"
          title={state.title}
          src={state.documentUrl}
          style={{
            width: '100%',
            minHeight: '320px',
            border: `1px solid ${tokens.border}`,
          }}
        />
        <fieldset style={{ display: 'grid', gap: tokens.spacing.sm }}>
          <legend>{printText(locale, 'reason')}</legend>
          {printReasons.map((option) => (
            <label key={option} style={{ display: 'block' }}>
              <input
                type="radio"
                name="print-preview-reason"
                value={option}
                checked={reason === option}
                onChange={() => setReason(option)}
              />{' '}
              {printText(locale, printReasonKey[option])}
            </label>
          ))}
        </fieldset>
        {showReasonError ? (
          <p
            ref={reasonErrorRef}
            role="alert"
            tabIndex={-1}
            style={{ color: tokens.foreground, border: `1px solid ${tokens.border}` }}
          >
            {printText(locale, 'reasonRequired')}
          </p>
        ) : null}
        <label htmlFor="print-preview-language">{printText(locale, 'language')}</label>
        <select
          id="print-preview-language"
          value={language}
          onChange={(event) => setLanguage(event.target.value as HandoutLanguage)}
          style={{ minHeight: '32px' }}
        >
          <option value="en">{printText(locale, 'languageEn')}</option>
          <option value="ar">{printText(locale, 'languageAr')}</option>
        </select>
        <div data-print-controls style={{ display: 'flex', gap: tokens.spacing.sm }}>
          <Button onClick={print}>{printText(locale, 'print')}</Button>
          <Button variant="secondary" onClick={() => onDownload(language)}>
            {printText(locale, 'download')}
          </Button>
        </div>
      </>
    );
  }

  return (
    <div
      ref={containerRef}
      role="dialog"
      aria-modal="true"
      aria-labelledby="print-preview-title"
      style={{
        position: 'fixed',
        inset: 0,
        background: 'rgba(0, 0, 0, 0.45)',
        display: 'grid',
        placeItems: 'center',
      }}
    >
      <div
        style={{
          background: tokens.background,
          color: tokens.foreground,
          border: `1px solid ${tokens.border}`,
          padding: tokens.spacing.lg,
          maxWidth: '720px',
          maxHeight: '90vh',
          overflow: 'auto',
        }}
      >
        <style id={printStyleId}>{printStylesheet}</style>
        <h2 id="print-preview-title">{printText(locale, 'title')}</h2>
        {content}
        <div data-print-controls style={{ display: 'flex', gap: tokens.spacing.sm }}>
          <Button buttonRef={closeRef} variant="secondary" onClick={onClose}>
            {printText(locale, 'close')}
          </Button>
        </div>
      </div>
    </div>
  );
}
