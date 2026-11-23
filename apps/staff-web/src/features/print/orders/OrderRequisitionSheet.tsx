import { MixedScriptText } from '../../../components/MixedScriptText';
import { useTheme } from '../../../theme/ThemeProvider';
import type { HandoutLanguage } from '../components/messages';
import { humanReadableOrderNumber, testDisplayName } from './domain';
import { orderPrintText, priorityMessageKey } from './labels';
import type { PrintableOrder } from './types';

/** Props for one printable requisition sheet. */
export interface OrderRequisitionSheetProps {
  /** Orders that share this sheet: one for a separate sheet, many for combined. */
  orders: readonly PrintableOrder[];
  /** Language of the generated sheet, independent of the interface locale. */
  language: HandoutLanguage;
}

/**
 * Presentational patient requisition sheet. It shows the tests or study, the
 * clinical question, priority, preparation, where to go and a barcode-free
 * human readable order number. It never fetches or decides: the button owns
 * the placement gate and the audit.
 */
export function OrderRequisitionSheet({ orders, language }: OrderRequisitionSheetProps) {
  const { tokens } = useTheme();
  // A handout is its own document: direction follows the chosen sheet language,
  // which may differ from the surrounding UI locale.
  return (
    <section
      data-order-requisition="true"
      data-print-handout="true"
      dir={language === 'ar' ? 'rtl' : 'ltr'}
      lang={language}
      style={{
        background: tokens.background,
        color: tokens.foreground,
        border: `1px solid ${tokens.border}`,
        padding: tokens.spacing.md,
        marginBlockEnd: tokens.spacing.md,
      }}
    >
      <h3>{orderPrintText(language, 'title')}</h3>
      {orders.map((order) => (
        <article
          key={order.id}
          data-order-entry="true"
          data-order-id={order.id}
          style={{
            borderBlockStart: `1px solid ${tokens.border}`,
            paddingBlockStart: tokens.spacing.sm,
          }}
        >
          <dl>
            <div>
              <dt>{orderPrintText(language, 'orderNumber')}</dt>
              <dd>
                <bdi dir="ltr">{humanReadableOrderNumber(order)}</bdi>
              </dd>
            </div>
            <div>
              <dt>{orderPrintText(language, 'investigations')}</dt>
              <dd>
                <ul>
                  {order.tests.map((test) => (
                    <li key={test.code}>
                      <bdi dir="ltr">{test.code}</bdi>{' '}
                      <MixedScriptText text={testDisplayName(test, language)} lang={language} />
                    </li>
                  ))}
                </ul>
              </dd>
            </div>
            <div>
              <dt>{orderPrintText(language, 'clinicalQuestion')}</dt>
              <dd>
                <MixedScriptText text={order.clinicalQuestion} lang={language} />
              </dd>
            </div>
            <div>
              <dt>{orderPrintText(language, 'priority')}</dt>
              <dd>{orderPrintText(language, priorityMessageKey[order.priority])}</dd>
            </div>
            <div>
              <dt>{orderPrintText(language, 'preparation')}</dt>
              <dd>
                <MixedScriptText text={order.preparationInstructions} lang={language} />
              </dd>
            </div>
            <div>
              <dt>{orderPrintText(language, 'whereToGo')}</dt>
              <dd>
                <MixedScriptText text={order.whereToGo} lang={language} />
              </dd>
            </div>
          </dl>
        </article>
      ))}
    </section>
  );
}
