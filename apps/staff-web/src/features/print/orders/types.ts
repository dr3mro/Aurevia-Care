import type { OrderCategory, OrderPriority } from '../../orderentry/entry/domain';

/** Status of an order that may be considered for a patient requisition sheet. */
export type PrintableOrderStatus = 'draft' | 'placed';

/** Whether several orders share one sheet or each gets its own sheet. */
export type SheetLayout = 'combined' | 'separate';

/** One test or imaging study printed on a requisition sheet. */
export interface PrintableTest {
  /** Local order code, shown as an isolated left-to-right run. */
  code: string;
  /** Latin display name from the order snapshot. */
  name: string;
  /** Arabic display name; absence is not an unknown state. */
  nameLocal?: string | undefined;
}

/**
 * One order shown on a patient requisition sheet (LAB-01, RAD-01).
 *
 * Only a placed order is printable; a draft is kept in the type so the button
 * can explain why it refuses to print. All text is synthetic in tests.
 */
export interface PrintableOrder {
  /** Tenant-local identifier; the sheet shows a barcode-free readable form. */
  id: string;
  category: OrderCategory;
  status: PrintableOrderStatus;
  /** One entry per ordered lab test or imaging study. */
  tests: readonly PrintableTest[];
  /** Clinical question or reason for the study, stored as written. */
  clinicalQuestion: string;
  /** Approved priority vocabulary reused from the order domain. */
  priority: OrderPriority;
  /** Patient preparation text, for example a fasting instruction. */
  preparationInstructions: string;
  /** Where the patient must go for collection or imaging. */
  whereToGo: string;
}
