import { useCallback, useEffect, useId, useRef, useState } from 'react';
import type { RefObject } from 'react';
import { Button } from '../../../components/Button';
import { useI18n } from '../../../i18n/I18nProvider';
import type { HandoutLanguage, PrintReason } from '../components/messages';
import { printReasonKey, printText } from '../components/messages';
import { focusableItems } from '../focus';
import { printStylesheet } from '../styles/printStyles';
import { buildSheets, printableOrders } from './domain';
import { orderPrintText } from './labels';
import { OrderRequisitionSheet } from './OrderRequisitionSheet';
import type { PrintableOrder, SheetLayout } from './types';

export type { HandoutLanguage, PrintReason } from '../components/messages';
export type { PrintableOrder, PrintableTest, SheetLayout } from './types';

/** Stable order of reason choices in both directions. */
const reasonOptions: readonly PrintReason[] = ['give-to-patient', 'referral', 'patient-request'];

/** Stable order of the clinician's sheet-layout choices. */
const layoutOptions: readonly SheetLayout[] = ['combined', 'separate'];

/** Props for the lab and imaging order print button. */
export interface PrintOrderButtonProps {
  /** Orders from one visit; only placed orders may reach a sheet. */
  orders: readonly PrintableOrder[];
  /** Called once on confirmation with the chosen sheets so the owner audits the print. */
  onPrint: (
    sheets: readonly (readonly PrintableOrder[])[],
    reason: PrintReason,
    language: HandoutLanguage,
  ) => void;
}

/** Move focus into the open dialog, contain Tab inside it and restore focus on close. */
function useDialogFocus(
  open: boolean,
  dialogRef: RefObject<HTMLDivElement | null>,
  closeRef: RefObject<HTMLButtonElement | null>,
  close: () => void,
): void {
  useEffect(() => {
    if (!open) {
      return;
    }
    const previous = document.activeElement as HTMLElement;
    closeRef.current?.focus();
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        close();
        return;
      }
      if (event.key !== 'Tab') {
        return;
      }
      // The dialog declares aria-modal, so Tab must not reach the controls
      // behind it. Wrap focus among this dialog's own controls.
      const items = focusableItems(dialogRef.current);
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
  }, [open, close, dialogRef, closeRef]);
}

/**
 * Exported entry point for printing lab and imaging requisition sheets from an
 * order screen. Drafts cannot print; several placed orders may be combined on
 * one sheet or printed separately, as the clinician chooses.
 */
export function PrintOrderButton({ orders, onPrint }: PrintOrderButtonProps) {
  const { locale } = useI18n();
  const instanceId = useId();
  const selectId = `order-print-language-${instanceId}`;
  const reasonErrorId = `order-print-reason-error-${instanceId}`;
  const [open, setOpen] = useState(false);
  const [layout, setLayout] = useState<SheetLayout>('combined');
  const [language, setLanguage] = useState<HandoutLanguage>(locale);
  const [reason, setReason] = useState<PrintReason | null>(null);
  const [reasonMissing, setReasonMissing] = useState(false);
  const dialogRef = useRef<HTMLDivElement>(null);
  const reasonRef = useRef<HTMLInputElement>(null);
  const closeRef = useRef<HTMLButtonElement>(null);
  const printable = printableOrders(orders);
  const hasDrafts = printable.length !== orders.length;
  const close = useCallback(() => setOpen(false), []);

  useDialogFocus(open, dialogRef, closeRef, close);

  const print = () => {
    if (reason === null) {
      setReasonMissing(true);
      reasonRef.current?.focus();
      return;
    }
    setReasonMissing(false);
    onPrint(buildSheets(printable, layout), reason, language);
  };

  if (printable.length === 0) {
    return (
      <div>
        <Button disabled>{orderPrintText(locale, 'printButton')}</Button>
        <p role="status">{orderPrintText(locale, 'draftCannotPrint')}</p>
      </div>
    );
  }

  const sheets = buildSheets(printable, layout);
  const sheetKey = (sheet: readonly PrintableOrder[]) => sheet.map((order) => order.id).join('-');

  return (
    <>
      <Button
        onClick={() => {
          // A fresh opening takes the current interface locale as its default;
          // any later handout-language choice stays independent of the shell.
          setLanguage(locale);
          setOpen(true);
        }}
      >
        {orderPrintText(locale, 'printButton')}
      </Button>
      {open ? (
        <div
          ref={dialogRef}
          role="dialog"
          aria-modal="true"
          aria-label={orderPrintText(locale, 'title')}
          data-order-print-preview="true"
        >
          <style data-order-print-styles="true">{printStylesheet}</style>
          {hasDrafts ? <p role="status">{orderPrintText(locale, 'draftExcluded')}</p> : null}
          {printable.length > 1 ? (
            <fieldset data-print-controls>
              <legend>{orderPrintText(locale, 'layoutLegend')}</legend>
              {layoutOptions.map((option) => (
                <label key={option}>
                  <input
                    type="radio"
                    name="order-sheet-layout"
                    value={option}
                    checked={layout === option}
                    onChange={() => setLayout(option)}
                  />{' '}
                  {orderPrintText(
                    locale,
                    option === 'combined' ? 'layoutCombined' : 'layoutSeparate',
                  )}
                </label>
              ))}
            </fieldset>
          ) : null}
          <p data-sheet-count>
            {orderPrintText(locale, 'sheetCount')}: {sheets.length}
          </p>
          <label htmlFor={selectId}>{printText(locale, 'language')}</label>
          <select
            id={selectId}
            data-print-controls
            value={language}
            onChange={(event) => setLanguage(event.target.value as HandoutLanguage)}
          >
            <option value="en">{printText(locale, 'languageEn')}</option>
            <option value="ar">{printText(locale, 'languageAr')}</option>
          </select>
          <fieldset
            data-print-controls
            aria-describedby={reasonMissing ? reasonErrorId : undefined}
          >
            <legend>{printText(locale, 'reason')}</legend>
            {reasonOptions.map((option, index) => (
              <label key={option}>
                <input
                  ref={index === 0 ? reasonRef : undefined}
                  type="radio"
                  name="order-print-reason"
                  value={option}
                  checked={reason === option}
                  onChange={() => {
                    setReason(option);
                    setReasonMissing(false);
                  }}
                />{' '}
                {printText(locale, printReasonKey[option])}
              </label>
            ))}
          </fieldset>
          {reasonMissing ? (
            <p id={reasonErrorId} role="alert">
              {orderPrintText(locale, 'reasonMissing')}
            </p>
          ) : null}
          {sheets.map((sheet) => (
            <OrderRequisitionSheet key={sheetKey(sheet)} orders={sheet} language={language} />
          ))}
          <div data-print-controls>
            <Button onClick={print}>{printText(locale, 'print')}</Button>
            <Button buttonRef={closeRef} variant="secondary" onClick={close}>
              {printText(locale, 'close')}
            </Button>
          </div>
        </div>
      ) : null}
    </>
  );
}
