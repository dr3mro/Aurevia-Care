import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
import { installSyntheticOrderApi } from './synthetic-order-api';
import type { SyntheticOrderApi } from './synthetic-order-api';

/**
 * Synthetic browser journey for clinician order entry (LAB-01, RAD-01, WEB-09).
 *
 * The three order-entry parts are mounted through a dev-only harness with their
 * real providers and synthetic fixtures. Only the `/api/v1/orders/**` boundary is
 * stubbed: the partner failure is produced by that stub, never by a real
 * interface. The journey places a lab order sent electronically, places an
 * imaging order whose send fails and is switched to print, checks the status
 * wording and that the failed send left exactly one printed order, then cancels
 * one order and amends another. It runs in English and Arabic.
 */

/** Words the interface shows for one locale, asserted verbatim in the browser. */
interface JourneyWords {
  locale: 'en' | 'ar';
  direction: 'ltr' | 'rtl';
  panelTitle: string;
  search: string;
  labSearch: string;
  imagingSearch: string;
  add: string;
  labItem: string;
  imagingItem: string;
  clinicalQuestion: string;
  clinicalQuestionRequired: string;
  deliveryModeLabel: string;
  saveDraft: string;
  savedNotice: string;
  selectedItems: string;
  deliveryTitle: string;
  partner: string;
  confirmElectronic: string;
  timelineTitle: string;
  milestoneTransmitted: string;
  milestoneDelivered: string;
  milestonePrinted: string;
  sendFailedTitle: string;
  sendFailedDetail: string;
  reference: string;
  retry: string;
  printInstead: string;
  ordersTab: string;
  statusDelivered: string;
  statusPlaced: string;
  statusCancelled: string;
  printDeliveryMode: string;
  notDelivered: string;
  cancel: string;
  amend: string;
  reason: string;
  required: string;
  reasonRequired: string;
  cancelReason: string;
  amendReason: string;
  cancelledNotice: string;
  amendNotice: string;
}

const english: JourneyWords = {
  locale: 'en',
  direction: 'ltr',
  panelTitle: 'Order entry',
  search: 'Search the order catalog',
  labSearch: 'LAB-GLU',
  imagingSearch: 'RAD-CT-ABD',
  add: 'Add',
  labItem: 'Fasting glucose',
  imagingItem: 'CT abdomen with contrast',
  clinicalQuestion: 'Does the lesion enhance after contrast?',
  clinicalQuestionRequired: 'Imaging orders require a clinical question before saving.',
  deliveryModeLabel: 'Delivery mode',
  saveDraft: 'Save draft',
  savedNotice: 'Draft saved to the patient record.',
  selectedItems: 'Selected items',
  deliveryTitle: 'Choose how this order is delivered',
  partner: 'Synthetic Lab Partner',
  confirmElectronic: 'Confirm: send electronically to the partner',
  timelineTitle: 'Delivery timeline',
  milestoneTransmitted: 'Sent to the partner',
  milestoneDelivered: 'Delivered to the partner',
  milestonePrinted: 'Printed handout ready',
  sendFailedTitle: 'The order could not be sent',
  sendFailedDetail:
    'The partner did not accept the order. Retry the send when it is safe, or print a handout instead.',
  reference: 'Reference',
  retry: 'Retry send',
  printInstead: 'Print instead',
  ordersTab: 'Orders',
  statusDelivered: 'Delivered',
  statusPlaced: 'Placed',
  statusCancelled: 'Cancelled',
  printDeliveryMode: 'Print to carry',
  notDelivered: 'Not delivered yet',
  cancel: 'Cancel',
  amend: 'Amend',
  reason: 'Reason',
  required: 'required',
  reasonRequired: 'A reason is required before this action can be sent.',
  cancelReason: 'Synthetic duplicate order placed in error.',
  amendReason: 'Synthetic clinical question clarified after partner review.',
  cancelledNotice: 'The order was cancelled.',
  amendNotice: 'An amendment was sent to the partner.',
};

const arabic: JourneyWords = {
  locale: 'ar',
  direction: 'rtl',
  panelTitle: 'إدخال الطلبات',
  search: 'ابحث في كتالوج الطلبات',
  labSearch: 'LAB-GLU',
  imagingSearch: 'RAD-CT-ABD',
  add: 'إضافة',
  labItem: 'Fasting glucose',
  imagingItem: 'CT abdomen with contrast',
  clinicalQuestion: 'هل تُظهر الآفة تعزيزاً بعد الصبغة؟',
  clinicalQuestionRequired: 'طلبات الأشعة تحتاج سؤالاً سريرياً قبل الحفظ.',
  deliveryModeLabel: 'طريقة الإرسال',
  saveDraft: 'حفظ المسودة',
  savedNotice: 'تم حفظ المسودة في ملف المريض.',
  selectedItems: 'العناصر المختارة',
  deliveryTitle: 'اختر طريقة إرسال هذا الطلب',
  partner: 'شريك المختبر التجريبي',
  confirmElectronic: 'تأكيد: الإرسال إلكترونياً إلى الجهة',
  timelineTitle: 'خط سير الإرسال',
  milestoneTransmitted: 'أُرسل إلى الجهة',
  milestoneDelivered: 'تم التسليم إلى الجهة',
  milestonePrinted: 'المطبوع جاهز',
  sendFailedTitle: 'تعذّر إرسال الطلب',
  sendFailedDetail:
    'لم تقبل الجهة الطلب. أعد الإرسال عندما يكون ذلك آمناً، أو اطبع مطبوعاً بدلاً من ذلك.',
  reference: 'المرجع',
  retry: 'إعادة الإرسال',
  printInstead: 'الطباعة بدلاً من ذلك',
  ordersTab: 'الطلبات',
  statusDelivered: 'تم الإرسال',
  statusPlaced: 'مُصدَر',
  statusCancelled: 'ملغي',
  printDeliveryMode: 'مطبوع ليحمله المريض',
  notDelivered: 'لم يتم الإرسال بعد',
  cancel: 'إلغاء',
  amend: 'تعديل',
  reason: 'السبب',
  required: 'مطلوب',
  reasonRequired: 'يلزم إدخال سبب قبل إرسال هذا الإجراء.',
  cancelReason: 'طلب مكرر تم تقديمه بالخطأ (توضيحي).',
  amendReason: 'تم توضيح السؤال السريري بعد مراجعة الجهة (توضيحي).',
  cancelledNotice: 'تم إلغاء الطلب.',
  amendNotice: 'تم إرسال تعديل إلى الجهة.',
};

/** Drive one full order-entry journey in the chosen locale. */
async function runJourney(page: Page, api: SyntheticOrderApi, words: JourneyWords): Promise<void> {
  await page.goto('/e2e/orderentry/orderentry-harness.html');
  await expect(page.getByTestId('orderentry-harness')).toBeVisible();

  if (words.locale === 'ar') {
    await page.getByRole('button', { name: 'العربية' }).click();
  }
  await expect(page.locator('html')).toHaveAttribute('dir', words.direction);
  await expect(page.locator('html')).toHaveAttribute('lang', words.locale);

  const panel = page.getByRole('region', { name: words.panelTitle });
  const delivery = page.getByRole('region', { name: words.deliveryTitle });
  const orders = page.getByRole('region', { name: words.ordersTab });

  await test.step('search and place a lab order sent electronically', async () => {
    await panel.getByLabel(words.search).fill(words.labSearch);
    await panel.getByRole('button', { name: `${words.add}: ${words.labItem}` }).click();
    await expect(panel.getByRole('region', { name: words.selectedItems })).toContainText(
      words.labItem,
    );
    await panel.getByLabel(words.deliveryModeLabel).selectOption('electronic');
    await panel.getByRole('button', { name: words.saveDraft }).click();
    await expect(panel.getByTestId('entry-status')).toHaveText(words.savedNotice);
    await expect.poll(() => api.calls).toContain('POST /api/v1/orders/drafts');

    await expect(delivery).toContainText(words.partner);
    await expect(delivery.getByRole('radio', { name: /.*/ }).nth(0)).toBeChecked();
    await delivery.getByRole('button', { name: words.confirmElectronic }).click();
    await expect.poll(() => api.calls).toContain('POST /api/v1/orders/ORD-SYN-1/deliver');

    const timeline = delivery.getByRole('region', { name: words.timelineTitle });
    await expect(timeline.getByText(words.milestoneTransmitted)).toBeVisible();
    await expect(timeline.getByText(words.milestoneDelivered)).toBeVisible();
    await expect(orders.getByTestId('order-status-ORD-SYN-1')).toContainText(words.statusDelivered);
  });

  await test.step('an imaging order requires a clinical question before it can be saved', async () => {
    await panel.getByLabel(words.search).fill(words.imagingSearch);
    await panel.getByRole('button', { name: `${words.add}: ${words.imagingItem}` }).click();
    await panel.getByRole('button', { name: words.saveDraft }).click();
    await expect(panel.getByTestId('entry-error')).toHaveText(words.clinicalQuestionRequired);
    expect(api.calls.filter((call) => call === 'POST /api/v1/orders/drafts')).toHaveLength(1);

    await panel.locator('#entry-question').fill(words.clinicalQuestion);
    await panel.getByRole('button', { name: words.saveDraft }).click();
    await expect(panel.getByTestId('entry-status')).toHaveText(words.savedNotice);
    await expect
      .poll(() => api.calls.filter((call) => call === 'POST /api/v1/orders/drafts'))
      .toHaveLength(2);
  });

  await test.step('the partner stub fails and the clinician prints instead', async () => {
    api.failNextSend = true;
    await delivery.getByRole('button', { name: words.confirmElectronic }).click();
    await expect.poll(() => api.calls).toContain('POST /api/v1/orders/ORD-SYN-2/deliver');
    const failure = delivery.getByTestId('delivery-failure');
    await expect(failure).toContainText(words.sendFailedTitle);
    await expect(failure).toContainText(words.sendFailedDetail);
    await expect(failure).toContainText(words.reference);
    await expect(failure.getByRole('button', { name: words.retry })).toBeVisible();
    // A failed send never places an order behind the clinician's back.
    expect(api.calls).not.toContain('POST /api/v1/orders/ORD-SYN-2/place');

    await failure.getByRole('button', { name: words.printInstead }).click();
    await expect(delivery.getByTestId('delivery-failure')).toHaveCount(0);
    await expect.poll(() => api.calls).toContain('POST /api/v1/orders/ORD-SYN-2/place');
    // Exactly one imaging order exists after the switch to print — the failed
    // electronic send did not leave a duplicate.
    await expect(orders.getByTestId('order-row-ORD-SYN-2')).toHaveCount(1);
    await expect(orders.getByTestId('order-status-ORD-SYN-2')).toContainText(words.statusPlaced);
    await expect(orders.getByTestId('order-delivery-ORD-SYN-2')).toContainText(
      words.printDeliveryMode,
    );
    await expect(orders.getByTestId('order-delivery-ORD-SYN-2')).toContainText(words.notDelivered);
    await expect(
      delivery.getByRole('region', { name: words.timelineTitle }).getByText(words.milestonePrinted),
    ).toBeVisible();
  });

  await test.step('cancel one order and amend another with a reason', async () => {
    const labRow = orders.getByTestId('order-row-ORD-SYN-1');
    await labRow.getByRole('button', { name: `${words.cancel}: ORD-SYN-1` }).click();
    const cancelForm = orders.getByTestId('order-action-form');
    await cancelForm.getByRole('button', { name: words.cancel, exact: true }).click();
    await expect(cancelForm.getByRole('alert')).toHaveText(words.reasonRequired);
    await cancelForm.locator('#reason-ORD-SYN-1').fill(words.cancelReason);
    await cancelForm.getByRole('button', { name: words.cancel, exact: true }).click();
    await expect(orders.getByTestId('tracking-status')).toHaveText(words.cancelledNotice);
    await expect(orders.getByTestId('order-status-ORD-SYN-1')).toContainText(words.statusCancelled);
    expect(api.calls).toContain('POST /api/v1/orders/ORD-SYN-1/cancel');

    const imagingRow = orders.getByTestId('order-row-ORD-SYN-2');
    await imagingRow.getByRole('button', { name: `${words.amend}: ORD-SYN-2` }).click();
    const amendForm = orders.getByTestId('order-action-form');
    await amendForm.locator('#reason-ORD-SYN-2').fill(words.amendReason);
    await amendForm.getByRole('button', { name: words.amend, exact: true }).click();
    await expect(orders.getByTestId('tracking-status')).toHaveText(words.amendNotice);
    expect(api.calls).toContain('POST /api/v1/orders/ORD-SYN-2/amend');
    await expect(orders.getByTestId('order-row-ORD-SYN-2')).toHaveCount(1);

    // No patient identifier is ever placed in the URL (WEB-02).
    expect(page.url()).not.toContain('patient-');
  });
}

test('a clinician places lab and imaging orders, fails over to print, then cancels and amends in English', async ({
  page,
  baseURL,
}) => {
  const api: SyntheticOrderApi = await installSyntheticOrderApi(page, baseURL);
  await runJourney(page, api, english);
});

test('the same order journey runs in Arabic with right-to-left wording', async ({
  page,
  baseURL,
}) => {
  const api: SyntheticOrderApi = await installSyntheticOrderApi(page, baseURL);
  await runJourney(page, api, arabic);
});
