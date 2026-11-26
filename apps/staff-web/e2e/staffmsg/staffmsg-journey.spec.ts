import { expect, test } from '@playwright/test';
import type { Browser, Page, Route } from '@playwright/test';
import { expectNoBlockingViolations } from '../accessibility';
import {
  authorSubject,
  cleanDocumentId,
  cleanFileName,
  createStaffMessageApi,
  homeFacilityId,
  instantConversationId,
  organizationId,
  otherFacilityId,
  peerSubject,
  quarantinedDocumentId,
  quarantinedFileName,
  remoteSubject,
  syntheticPatientId,
} from './synthetic-staffmsg-api';
import type { StaffMessageApi } from './synthetic-staffmsg-api';

/**
 * Bilingual staff-messaging journey (MSG-02, MSG-03, WEB-09).
 *
 * Three synthetic staff views in two facilities drive the real messenger panel,
 * mail page and reference components through one shared in-memory API stub.
 * The journey proves explicit instant send with a clean attachment, a cross-
 * facility mail with Cc and confirmation, a published reply, a patient
 * reference that is blocked for one recipient, and a quarantined file that is
 * removed before a later send can publish. Every subject and identifier is
 * synthetic; bilingual expectations are asserted verbatim, not imported from
 * the labels under test.
 */

/** Words asserted verbatim in one locale. */
interface JourneyWords {
  readonly locale: 'en' | 'ar';
  readonly direction: 'ltr' | 'rtl';
  readonly switchToArabic: boolean;
  readonly messengerTitle: string;
  readonly composer: string;
  readonly messageLabel: string;
  readonly stage: string;
  readonly send: string;
  readonly pending: string;
  readonly delivered: string;
  readonly incoming: string;
  readonly unread: string;
  readonly removeFile: string;
  readonly replaceFile: string;
  readonly mailTitle: string;
  readonly newMail: string;
  readonly addTo: string;
  readonly addCc: string;
  readonly attach: string;
  readonly sendMail: string;
  readonly confirm: string;
  readonly resolvedRecipients: string;
  readonly reply: string;
  readonly autosave: string;
  readonly otherFacility: string;
  readonly sentCount: string;
  readonly refSubject: string;
  readonly insert: string;
  readonly blocked: string;
  readonly blockedDetail: string;
  readonly scanFailed: string;
  readonly errorState: string;
  readonly refresh: string;
  readonly instantBody: string;
  readonly mailSubject: string;
  readonly mailBody: string;
  readonly quarantineBody: string;
  readonly replyBody: string;
  readonly referenceSubject: string;
}

const english: JourneyWords = {
  locale: 'en',
  direction: 'ltr',
  switchToArabic: false,
  messengerTitle: 'Instant messages',
  composer: 'Compose a reply',
  messageLabel: 'Message',
  stage: 'Stage file',
  send: 'Send',
  pending: 'Nothing is sent until you press Send.',
  delivered: 'Delivered',
  incoming: 'Staff',
  unread: 'unread',
  removeFile: 'Remove file',
  replaceFile: 'Replace with clean file',
  mailTitle: 'Staff mail',
  newMail: 'New mail',
  addTo: 'Add to To',
  addCc: 'Add to Cc',
  attach: 'Attach a staged file',
  sendMail: 'Send mail',
  confirm: 'Confirm and send',
  resolvedRecipients: 'Resolved recipients',
  reply: 'Reply',
  autosave: 'Draft saved at version',
  otherFacility: 'Another facility',
  sentCount: 'Mail sent to 2 resolved recipients.',
  refSubject: 'Subject',
  insert: 'Insert',
  blocked: 'Blocked',
  blockedDetail: 'You do not have access to this record.',
  scanFailed: 'Quarantined — scan failed',
  errorState: 'Something went wrong',
  refresh: 'Refresh views',
  instantBody: 'Synthetic instant body',
  mailSubject: 'Synthetic cross-facility subject',
  mailBody: 'Synthetic cross-facility body',
  quarantineBody: 'Synthetic body after quarantine removal',
  replyBody: 'Synthetic reply body',
  referenceSubject: 'Synthetic follow-up',
};

const arabic: JourneyWords = {
  locale: 'ar',
  direction: 'rtl',
  switchToArabic: true,
  messengerTitle: 'الرسائل الفورية',
  composer: 'كتابة رد',
  messageLabel: 'الرسالة',
  stage: 'تجهيز الملف',
  send: 'إرسال',
  pending: 'لا يُرسل شيء قبل الضغط على إرسال.',
  delivered: 'تم الإرسال',
  incoming: 'الموظف',
  unread: 'غير مقروءة',
  removeFile: 'إزالة الملف',
  replaceFile: 'استبدال بملف نظيف',
  mailTitle: 'البريد الإداري',
  newMail: 'رسالة جديدة',
  addTo: 'إضافة إلى إلى',
  addCc: 'إضافة إلى النسخة',
  attach: 'إرفاق ملف مُجهّز',
  sendMail: 'إرسال البريد',
  confirm: 'تأكيد وإرسال',
  resolvedRecipients: 'المستلمون بعد التحويل',
  reply: 'رد',
  autosave: 'تم حفظ المسوّدة بالنسخة',
  otherFacility: 'من منشأة أخرى',
  sentCount: 'تم الإرسال إلى 2 من المستلمين.',
  refSubject: 'الموضوع',
  insert: 'إدراج',
  blocked: 'محجوب',
  blockedDetail: 'لا تملك صلاحية الوصول إلى هذا السجل.',
  scanFailed: 'محجوبة — فحص غير ناجح',
  errorState: 'حدث خطأ',
  refresh: 'تحديث العرض',
  instantBody: 'نص فوري توضيحي',
  mailSubject: 'موضوع توضيحي بين منشأتين',
  mailBody: 'نص توضيحي بين منشأتين',
  quarantineBody: 'نص توضيحي بعد إزالة الملف المحجوب',
  replyBody: 'نص رد توضيحي',
  referenceSubject: 'متابعة توضيحية',
};

/** Open one harness view, switch its locale and assert document direction. */
interface ViewChoice {
  readonly user: 'nora' | 'omar' | 'layla';
  readonly facility: string;
  readonly subject: string;
  readonly words: JourneyWords;
  readonly expectMessenger?: boolean | undefined;
}

async function openView(page: Page, choice: ViewChoice): Promise<void> {
  const { user, facility, subject, words } = choice;
  await page.goto(`/e2e/staffmsg/staffmsg-harness.html?user=${user}&facility=${facility}`);
  await expect(page.getByTestId('staffmsg-harness')).toBeVisible();
  if (words.switchToArabic) {
    await page.getByRole('button', { name: 'العربية' }).click();
  }
  await expect(page.locator('html')).toHaveAttribute('dir', words.direction);
  await expect(page.locator('html')).toHaveAttribute('lang', words.locale);
  await expect(page.getByTestId('staffmsg-identity')).toHaveText(`${subject}:${facility}`);
  // The safety banner is present in every messenger state; the full panel is
  // present only in the home facility, which owns the one conversation.
  await expect(page.getByTestId('messenger-emergency-banner')).toBeVisible();
  if (user !== 'layla' && choice.expectMessenger !== false) {
    await expect(page.getByRole('region', { name: words.messengerTitle })).toBeVisible();
  }
  await expect(page.getByRole('region', { name: words.mailTitle }).first()).toBeVisible();
}

/** Stage the named harness-composer file through the real attachment tray. */
async function stageInstantFile(page: Page, words: JourneyWords, documentId: string): Promise<void> {
  const messenger = page.getByRole('region', { name: words.messengerTitle });
  await messenger.locator('#messenger-stage-file').selectOption(documentId);
  await messenger.getByRole('button', { name: words.stage, exact: true }).click();
}

/** Compose and publish one mail through the real 428 confirmation flow. */
interface MailDraft {
  readonly owner: string;
  readonly to: string;
  readonly cc?: string | undefined;
  readonly body: string;
  readonly file: string;
}

async function publishMail(
  page: Page,
  words: JourneyWords,
  draft: MailDraft,
  api: StaffMessageApi,
): Promise<void> {
  const mail = page.getByRole('region', { name: words.mailTitle }).first();
  await mail.getByRole('button', { name: words.newMail, exact: true }).click();
  await mail.locator('#mail-subject').fill(words.mailSubject);
  await mail.locator('#mail-body').fill(draft.body);
  await mail.locator('#mail-picker-to').selectOption(draft.to);
  await mail.getByRole('button', { name: words.addTo, exact: true }).click();
  if (draft.cc !== undefined) {
    await mail.locator('#mail-picker-cc').selectOption(draft.cc);
    await mail.getByRole('button', { name: words.addCc, exact: true }).click();
  }
  await mail.locator('#mail-attach').selectOption(draft.file);
  await mail.getByRole('button', { name: words.attach, exact: true }).click();
  await expect(mail.getByTestId('mail-attachment-hint')).toContainText(draft.file);
  // Do not rely on the first autosave notice: a queued later version may still
  // be in flight. Poll the stored draft until every composed value is present.
  await expect
    .poll(() => {
      const stored = [...api.store.drafts.values()].find(
        (entry) => entry.owner === draft.owner && entry.subject === words.mailSubject,
      );
      return (
        stored !== undefined &&
        stored.to.includes(draft.to) &&
        (draft.cc === undefined || stored.cc.includes(draft.cc)) &&
        stored.attachments.includes(draft.file)
      );
    })
    .toBe(true);
  await expect(page.getByText(words.autosave, { exact: false }).first()).toBeVisible();
  await mail.getByRole('button', { name: words.sendMail, exact: true }).click();
  await expect(page.getByTestId('mail-confirmation-recipients')).toContainText(draft.to);
  if (draft.cc !== undefined) {
    await expect(page.getByTestId('mail-confirmation-recipients')).toContainText(draft.cc);
  }
  await expect(page.getByTestId('mail-confirmation-files')).toContainText(draft.file);
  await mail.getByRole('button', { name: words.confirm, exact: true }).click();
  await expect(page.getByText(words.sentCount)).toBeVisible();
}

/** One deliberate boundary request made outside the production UI. */
interface RawCall {
  readonly path: string;
  readonly method: string;
  readonly body?: Record<string, unknown> | undefined;
  readonly tenant?: string | undefined;
  readonly csrf?: boolean | undefined;
  readonly ifMatch?: string | undefined;
}

/** Send a raw command through the same stub to prove its refusal branches. */
async function rawCall(page: Page, call: RawCall): Promise<{ status: number; body: unknown }> {
  return page.evaluate(async ({ request, defaultTenant }) => {
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
      'X-Active-Organization': request.tenant ?? defaultTenant,
    };
    if (request.csrf !== false) {
      headers['X-XSRF-TOKEN'] = 'synthetic-csrf-token';
    }
    if (request.ifMatch !== undefined) {
      headers['If-Match'] = request.ifMatch;
    }
    const init: RequestInit = { method: request.method, headers };
    if (request.body !== undefined) {
      init.body = JSON.stringify(request.body);
    }
    const response = await fetch(request.path, init);
    return { status: response.status, body: (await response.json()) as unknown };
  }, { request: call, defaultTenant: organizationId });
}

/** Drive the whole three-view journey in one locale. */
async function runJourney(page: Page, browser: Browser, words: JourneyWords): Promise<void> {
  const api: StaffMessageApi = createStaffMessageApi();
  const omarContext = await browser.newContext();
  const laylaContext = await browser.newContext();
  try {
    const omar = await omarContext.newPage();
    const layla = await laylaContext.newPage();
    const baseURL = process.env.E2E_BASE_URL ?? 'http://127.0.0.1:5173';
    await api.installOn(page, baseURL, authorSubject);
    await api.installOn(omar, baseURL, peerSubject);
    await api.installOn(layla, baseURL, remoteSubject);
    await openView(page, { user: 'nora', facility: homeFacilityId, subject: authorSubject, words });
    await openView(omar, { user: 'omar', facility: homeFacilityId, subject: peerSubject, words });
    await openView(layla, { user: 'layla', facility: otherFacilityId, subject: remoteSubject, words });

    await test.step('an attachment is pending until explicit send, then reaches the peer', async () => {
      const messenger = page.getByRole('region', { name: words.messengerTitle });
      await stageInstantFile(page, words, cleanDocumentId);
      await expect(page.getByTestId('messenger-pending-files')).toContainText(cleanFileName);
      await expect(page.getByTestId('messenger-pending-files')).toContainText(words.pending);
      expect(api.store.messages).toHaveLength(0);

      await messenger.locator('#messenger-composer-body').fill(words.instantBody);
      await messenger.getByRole('button', { name: words.send, exact: true }).click();
      const bubble = messenger.locator('[data-message]').filter({ hasText: words.instantBody });
      await expect(bubble).toBeVisible();
      await expect(bubble).toContainText(`[file:${cleanFileName}]`);
      await expect(bubble).toContainText(words.delivered);
      expect(api.store.messages).toHaveLength(1);
      expect(api.store.messages[0]?.attachmentNames).toEqual([cleanFileName]);
      expect(api.store.facts).toContain(`instant.send:${authorSubject}:${api.store.messages[0]?.id}`);

      const sent = api.store.requests.find(
        (entry) =>
          entry.method === 'POST' &&
          entry.path === `/api/v1/staff/instant/${instantConversationId}/messages`,
      );
      expect(sent?.tenant).toBe(organizationId);
      expect(sent?.csrf).toBe('synthetic-csrf-token');
      expect(sent?.idempotencyKey).toBe('synthetic-instant-send');

      await omar.getByTestId('staffmsg-refresh').click();
      const inbound = omar
        .getByRole('region', { name: words.messengerTitle })
        .locator('[data-message]')
        .filter({ hasText: words.instantBody });
      await expect(inbound).toBeVisible();
      await expect(inbound).toContainText(words.incoming);
      await expectNoBlockingViolations(page);
    });

    await test.step('a mail crosses facilities with Cc, is confirmed, and a reply returns', async () => {
      const laylaOption = page.locator(`#mail-picker-cc option[value="${remoteSubject}"]`);
      await expect(laylaOption).toContainText(words.otherFacility);
      await publishMail(
        page,
        words,
        {
          owner: authorSubject,
          to: peerSubject,
          cc: remoteSubject,
          body: words.mailBody,
          file: cleanDocumentId,
        },
        api,
      );
      expect(api.store.mails).toHaveLength(1);
      expect(api.store.mails[0]?.to).toEqual([peerSubject]);
      expect(api.store.mails[0]?.cc).toEqual([remoteSubject]);
      expect(api.store.mails[0]?.attachmentNames).toEqual([cleanDocumentId]);

      await omar.getByTestId('staffmsg-refresh').click();
      await expect(
        omar.getByRole('region', { name: words.messengerTitle }).getByRole('button', {
          name: /Synthetic triage peer/,
        }),
      ).toContainText(`1 ${words.unread}`);
      await expect(
        omar.getByRole('region', { name: words.mailTitle }).first().getByText(words.mailSubject).first(),
      ).toBeVisible();
      await layla.getByTestId('staffmsg-refresh').click();
      await expect(
        layla.getByRole('region', { name: words.mailTitle }).first().getByText(words.mailSubject).first(),
      ).toBeVisible();

      const remoteMail = layla.getByRole('region', { name: words.mailTitle }).first();
      await remoteMail.getByRole('button', { name: words.reply, exact: true }).click();
      await expect(remoteMail.locator('[data-recipient-list="to"]')).toContainText(authorSubject);
      await remoteMail.locator('#mail-body').fill(words.replyBody);
      await expect
        .poll(() => {
          const stored = [...api.store.drafts.values()].find(
            (entry) => entry.owner === remoteSubject && entry.body === words.replyBody,
          );
          return stored?.to.includes(authorSubject) === true;
        })
        .toBe(true);
      await expect(remoteMail.getByText(words.autosave, { exact: false }).first()).toBeVisible();
      await remoteMail.getByRole('button', { name: words.sendMail, exact: true }).click();
      await remoteMail.getByRole('button', { name: words.confirm, exact: true }).click();
      expect(api.store.mails).toHaveLength(2);
      expect(api.store.mails[1]?.senderSubject).toBe(remoteSubject);
      expect(api.store.mails[1]?.to).toEqual([authorSubject]);

      await page.getByTestId('staffmsg-refresh').click();
      await expect(
        page.getByRole('region', { name: words.mailTitle }).first().getByText(words.replyBody).first(),
      ).toBeVisible();
    });

    await test.step('a patient reference is allowed for one recipient and blocked for the other', async () => {
      for (const view of [page, layla]) {
        const field = view
          .getByTestId('staffmsg-reference')
          .getByRole('textbox', { name: words.refSubject });
        await field.fill(words.referenceSubject);
        await view.getByTestId('staffmsg-reference').getByRole('button', { name: words.insert }).click();
      }
      const allowed = page.getByTestId('staffmsg-reference').getByRole('link', { name: 'Synthetic chart reference' });
      await expect(allowed).toBeVisible();
      await expect(allowed).toHaveAttribute('href', `/patients/${syntheticPatientId}`);
      const blocked = layla.getByTestId('patient-reference-blocked');
      await expect(blocked).toBeVisible();
      await expect(blocked).toContainText(words.blocked);
      await expect(blocked).toContainText(words.blockedDetail);
      expect(await layla.content()).not.toContain(syntheticPatientId);
      expect(await layla.content()).not.toContain('Synthetic chart reference');
      expect(api.store.facts).toContain(`reference.open:${authorSubject}:ALLOWED`);
      expect(api.store.facts).toContain(`reference.open:${remoteSubject}:NO_RELATIONSHIP`);
      expect(api.calls).toContain('POST /api/v1/staff/references/open');
      expect(
        api.store.requests.some(
          (entry) =>
            entry.method === 'POST' &&
            entry.path === '/api/v1/staff/references/attach' &&
            entry.tenant === organizationId,
        ),
      ).toBe(true);
    });

    await test.step('a quarantined file is refused, removed and replaced before a later send', async () => {
      const scanRow = page.getByTestId(`scan-result-${quarantinedDocumentId}`);
      await expect(scanRow).toHaveAttribute('data-verdict', 'quarantined');
      await expect(scanRow).toContainText(words.scanFailed);
      await stageInstantFile(page, words, quarantinedDocumentId);
      const messenger = page.getByRole('region', { name: words.messengerTitle });
      await messenger.locator('#messenger-composer-body').fill(words.quarantineBody);
      const messagesBefore = api.store.messages.length;
      const sendsBefore = api.store.facts.filter((fact) =>
        fact.startsWith(`instant.send:${authorSubject}:`),
      ).length;
      await messenger.getByRole('button', { name: words.send, exact: true }).click();
      await expect(page.getByTestId('messenger-send-failure')).toBeVisible();
      expect(api.store.messages).toHaveLength(messagesBefore);
      expect(api.store.facts).toContain('attachment.refuse:QUARANTINED_FILE');
      expect(
        api.store.facts.filter((fact) => fact.startsWith(`instant.send:${authorSubject}:`)),
      ).toHaveLength(sendsBefore);

      await messenger
        .getByRole('button', { name: `${words.removeFile}: ${quarantinedFileName}` })
        .click();
      await scanRow.getByRole('button', { name: words.removeFile, exact: true }).click();
      await scanRow.getByRole('button', { name: words.replaceFile, exact: true }).click();
      await expect(page.getByTestId('staffmsg-available-attachments')).not.toContainText(quarantinedFileName);
      await expect(page.getByTestId('staffmsg-available-attachments')).toContainText(cleanFileName);
      await stageInstantFile(page, words, cleanDocumentId);
      await messenger.getByRole('button', { name: words.send, exact: true }).click();
      await expect(
        messenger.locator('[data-message]').filter({ hasText: words.quarantineBody }),
      ).toBeVisible();
      expect(api.store.messages).toHaveLength(messagesBefore + 1);
      expect(api.store.messages.at(-1)?.attachmentNames).toEqual([cleanFileName]);
      await expect(page.locator('html')).toHaveAttribute('dir', words.direction);
      await expect(page.locator('html')).toHaveAttribute('lang', words.locale);
      await expectNoBlockingViolations(page);
      await expectNoBlockingViolations(layla);
    });

    await test.step('a failed read or attach keeps the error state and writes no access decision', async () => {
      const failed = await browser.newPage();
      const refuseConversations = async (route: Route): Promise<void> => {
        await route.fulfill({ status: 500, contentType: 'application/json', body: '{}' });
      };
      try {
        await api.installOn(failed, baseURL, authorSubject);
        await failed.route('**/api/v1/staff/instant/conversations?*', refuseConversations);
        await openView(failed, {
          user: 'nora',
          facility: homeFacilityId,
          subject: authorSubject,
          words,
          expectMessenger: false,
        });
        await expect(failed.getByRole('heading', { name: words.errorState })).toBeVisible();
        await failed.unroute('**/api/v1/staff/instant/conversations?*', refuseConversations);
        await failed.getByTestId('staffmsg-refresh').click();
        await expect(failed.getByRole('region', { name: words.messengerTitle })).toBeVisible();

        // A read that fails after mount reaches the refresh recovery branch,
        // not the initial-load branch; both must show the same error state.
        await failed.route('**/api/v1/staff/instant/conversations?*', refuseConversations);
        await failed.getByTestId('staffmsg-refresh').click();
        await expect(failed.getByRole('heading', { name: words.errorState })).toBeVisible();
        await failed.unroute('**/api/v1/staff/instant/conversations?*', refuseConversations);
        await failed.getByTestId('staffmsg-refresh').click();
        await expect(failed.getByRole('region', { name: words.messengerTitle })).toBeVisible();

        const opensBefore = api.store.facts.filter((fact) => fact.startsWith('reference.open:')).length;
        const refuseAttach = async (route: Route): Promise<void> => {
          await route.fulfill({ status: 500, contentType: 'application/json', body: '{}' });
        };
        await failed.route('**/api/v1/staff/references/attach', refuseAttach);
        const field = failed
          .getByTestId('staffmsg-reference')
          .getByRole('textbox', { name: words.refSubject });
        await field.fill(words.referenceSubject);
        await failed.getByTestId('staffmsg-reference').getByRole('button', { name: words.insert }).click();
        await expect(failed.getByTestId('staffmsg-reference').getByRole('link')).toHaveCount(0);
        expect(api.store.facts.filter((fact) => fact.startsWith('reference.open:'))).toHaveLength(
          opensBefore,
        );
      } finally {
        await failed.close();
      }
    });

    await test.step('boundary refusals write no fact and leave stored projections unchanged', async () => {
      const messagesBefore = api.store.messages.length;
      const mailsBefore = api.store.mails.length;
      const publishesBefore = api.store.facts.filter((fact) => fact.startsWith('mail.publish:')).length;

      const wrongTenant = await rawCall(page, {
        path: '/api/v1/staff/instant/conversations',
        method: 'GET',
        tenant: 'org-synthetic-other',
      });
      expect(wrongTenant.status).toBe(403);
      expect(wrongTenant.body).toEqual({ code: 'FORBIDDEN' });

      const noCsrf = await rawCall(page, {
        path: `/api/v1/staff/instant/${instantConversationId}/messages`,
        method: 'POST',
        body: { body: 'Synthetic bypass' },
        csrf: false,
      });
      expect(noCsrf.status).toBe(403);
      expect(noCsrf.body).toEqual({ code: 'CSRF_MISSING' });

      const unknownCommand = await rawCall(page, {
        path: '/api/v1/staff/mail/unknown',
        method: 'POST',
        body: {},
      });
      expect(unknownCommand.status).toBe(404);
      expect(unknownCommand.body).toEqual({ code: 'NOT_FOUND' });

      const unknownThread = await rawCall(page, {
        path: '/api/v1/staff/instant/synthetic-unknown/messages',
        method: 'GET',
      });
      expect(unknownThread.status).toBe(404);
      const unknownSend = await rawCall(page, {
        path: '/api/v1/staff/instant/synthetic-unknown/messages',
        method: 'POST',
        body: { body: 'Synthetic bypass' },
      });
      expect(unknownSend.status).toBe(404);
      expect(api.store.messages).toHaveLength(messagesBefore);

      const created = await rawCall(page, {
        path: '/api/v1/staff/mail/drafts',
        method: 'POST',
        body: {
          subject: 'Synthetic boundary subject',
          body: 'Synthetic boundary body',
          to: [{ kind: 'person', value: peerSubject }],
          cc: [],
          attachmentDocumentIds: [],
        },
      });
      expect(created.status).toBe(201);
      const draftId = (created.body as { id?: string }).id ?? '';
      expect(draftId.length > 0).toBe(true);

      const stale = await rawCall(page, {
        path: `/api/v1/staff/mail/drafts/${draftId}`,
        method: 'PUT',
        ifMatch: '0',
        body: { subject: 'Synthetic stale', body: '', to: [], cc: [], attachmentDocumentIds: [] },
      });
      expect(stale.status).toBe(412);
      expect(stale.body).toEqual({ code: 'VERSION_CONFLICT' });

      const emptyConfirmation = await rawCall(page, {
        path: `/api/v1/staff/mail/drafts/${draftId}/send`,
        method: 'POST',
        body: { confirmedRecipients: [] },
      });
      expect(emptyConfirmation.status).toBe(428);
      expect(emptyConfirmation.body).toEqual({ code: 'CONFIRM_REQUIRED' });

      const saved = await rawCall(page, {
        path: `/api/v1/staff/mail/drafts/${draftId}`,
        method: 'PUT',
        ifMatch: '1',
        body: {
          subject: 'Synthetic quarantined mail',
          body: 'Synthetic body',
          to: [{ kind: 'person', value: peerSubject }],
          cc: [],
          attachmentDocumentIds: [quarantinedDocumentId],
        },
      });
      expect(saved.status).toBe(200);
      const refusedMail = await rawCall(page, {
        path: `/api/v1/staff/mail/drafts/${draftId}/send`,
        method: 'POST',
        body: { confirmedRecipients: [peerSubject] },
      });
      expect(refusedMail.status).toBe(409);
      expect(refusedMail.body).toEqual({ code: 'QUARANTINED_FILE' });
      expect(api.store.mails).toHaveLength(mailsBefore);
      expect(api.store.facts.filter((fact) => fact.startsWith('mail.publish:'))).toHaveLength(
        publishesBefore,
      );

      const digitRun = await rawCall(page, {
        path: '/api/v1/staff/references/attach',
        method: 'POST',
        body: {
          targetId: syntheticPatientId,
          connectionId: 'synthetic-connection',
          kind: 'patient',
          label: 'Synthetic chart reference',
          subject: 'Synthetic MRN 1234567',
        },
      });
      expect(digitRun.status).toBe(201);
      expect(digitRun.body).toMatchObject({ reason: 'DIGIT_RUN' });
      expect(api.store.facts).toContain(`reference.attach:${authorSubject}:DIGIT_RUN`);
      expect(api.store.facts.some((fact) => fact.includes('1234567'))).toBe(false);

      for (const id of [cleanDocumentId, quarantinedDocumentId]) {
        const scanned = await rawCall(page, {
          path: `/api/v1/staff/attachments/${id}/scan`,
          method: 'POST',
          body: { verdict: 'clean' },
        });
        expect(scanned.status).toBe(200);
      }
      expect(api.store.facts).toContain(`attachment.scan:${cleanDocumentId}:clean`);
      expect(api.store.facts).toContain(`attachment.scan:${quarantinedDocumentId}:quarantined`);

      const events = await rawCall(page, {
        path: `/api/v1/staff/instant/events?facilityId=${homeFacilityId}`,
        method: 'GET',
      });
      expect(events.status).toBe(200);
      expect(events.body).toEqual({ events: [] });
      const read = await rawCall(page, {
        path: '/api/v1/staff/instant/read',
        method: 'POST',
        body: { conversationId: instantConversationId, upToSequence: 1 },
      });
      expect(read.status).toBe(200);
      expect(read.body).toEqual({ conversationId: instantConversationId, unreadCount: 0 });
      expect(api.store.messages).toHaveLength(messagesBefore);
    });
  } finally {
    await omarContext.close();
    await laylaContext.close();
  }
}

test('staff messaging completes the bilingual three-view journey in English', async ({ page, browser }) => {
  await runJourney(page, browser, english);
});

test('staff messaging completes the bilingual three-view journey in Arabic RTL', async ({ page, browser }) => {
  await runJourney(page, browser, arabic);
});

test('an instant send without staged files publishes no file marker', async ({ page }) => {
  const api = createStaffMessageApi();
  await api.installOn(page, process.env.E2E_BASE_URL ?? 'http://127.0.0.1:5173', authorSubject);
  await openView(page, {
    user: 'nora',
    facility: homeFacilityId,
    subject: authorSubject,
    words: english,
  });
  const messenger = page.getByRole('region', { name: english.messengerTitle });
  await messenger.locator('#messenger-composer-body').fill(english.instantBody);
  await messenger.getByRole('button', { name: english.send, exact: true }).click();
  await expect(messenger.locator('[data-message]').filter({ hasText: english.instantBody })).toBeVisible();
  expect(api.store.messages).toHaveLength(1);
  expect(api.store.messages[0]?.body).toBe(english.instantBody);
  expect(api.store.messages[0]?.attachmentNames).toEqual([]);

  // An unknown document handle is kept as its own name rather than guessed.
  await rawCall(page, {
    path: '/api/v1/staff/attachments/send',
    method: 'POST',
    body: { documentIds: [{ value: 'synthetic-unknown-document' }] },
  });
  const rawSend = await rawCall(page, {
    path: `/api/v1/staff/instant/${instantConversationId}/messages`,
    method: 'POST',
    body: { body: english.quarantineBody },
  });
  expect(rawSend.status).toBe(201);
  const published = rawSend.body as { body?: string };
  expect(published.body).toContain('[file:synthetic-unknown-document]');
  expect(api.store.messages[1]?.attachmentNames).toEqual(['synthetic-unknown-document']);
});
