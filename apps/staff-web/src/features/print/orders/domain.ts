import type { HandoutLanguage } from '../components/messages';
import type { PrintableOrder, PrintableTest, SheetLayout } from './types';

/**
 * Keep only placed orders. A draft is not a clinical instruction and must
 * never reach a sheet the patient carries (LAB-01, RAD-01).
 */
export function printableOrders(orders: readonly PrintableOrder[]): PrintableOrder[] {
  return orders.filter((order) => order.status === 'placed');
}

/** Format the tenant-local identifier as a readable number, without a barcode. */
export function humanReadableOrderNumber(order: PrintableOrder): string {
  return `ORD-${order.id}`;
}

/**
 * Choose the local-script name for an Arabic sheet, falling back to the Latin
 * name the order recorded. English always uses the Latin name.
 */
export function testDisplayName(test: PrintableTest, language: HandoutLanguage): string {
  if (language === 'ar' && test.nameLocal !== undefined) {
    return test.nameLocal;
  }
  return test.name;
}

/**
 * Group printable orders into the sheets the clinician chose. One visit can
 * produce a single combined sheet or one sheet per order.
 */
export function buildSheets(
  orders: readonly PrintableOrder[],
  layout: SheetLayout,
): PrintableOrder[][] {
  if (layout === 'separate') {
    return orders.map((order) => [order]);
  }
  return orders.length === 0 ? [] : [orders.slice()];
}
