/**
 * Messenger-only bilingual labels (MSG-02, MSG-03, WEB-09).
 *
 * The shared bundle is outside this slice's ownership, so the feature keeps a
 * small parity-checked dictionary. Values that vary are named placeholders;
 * no clinical content, identifier or file name is embedded in a label. The
 * unread and pending-file counts are interpolated, never concatenated from
 * translated fragments, so Arabic word order stays correct.
 */

import type { Locale } from '../../../i18n/messages';

/** Every visible messenger label key. */
export const messengerKeys = [
  'title',
  'syntheticNotice',
  'emergencyBanner',
  'conversationsLabel',
  'emptyConversations',
  'unreadBadge',
  'threadLabel',
  'emptyThread',
  'noConversation',
  'deliverySending',
  'deliveryDelivered',
  'deliveryFailed',
  'outgoingLabel',
  'incomingLabel',
  'composerLabel',
  'bodyLabel',
  'attachmentTrayLabel',
  'emptyTray',
  'stageFileLabel',
  'attachLabel',
  'removeAttachment',
  'pendingFiles',
  'send',
  'sendFailed',
  'retry',
  'connectionLabel',
  'streaming',
  'reconnecting',
  'polling',
] as const;

/** One messenger label key. */
export type MessengerMessageKey = (typeof messengerKeys)[number];

/** Bilingual messenger labels with identical key sets. */
export const messengerMessages: Record<Locale, Record<MessengerMessageKey, string>> = {
  en: {
    title: 'Instant messages',
    syntheticNotice: 'Synthetic conversations only. Identifiers are local to this tenant.',
    emergencyBanner: 'This messenger is not for emergencies. Call emergency services for urgent clinical help.',
    conversationsLabel: 'Conversations',
    emptyConversations: 'No conversations in this facility.',
    unreadBadge: '{value} unread',
    threadLabel: 'Conversation thread',
    emptyThread: 'No messages in this thread yet.',
    noConversation: 'Select a conversation to read and reply.',
    deliverySending: 'Sending',
    deliveryDelivered: 'Delivered',
    deliveryFailed: 'Not sent',
    outgoingLabel: 'You',
    incomingLabel: 'Staff',
    composerLabel: 'Compose a reply',
    bodyLabel: 'Message',
    attachmentTrayLabel: 'Attachment tray',
    emptyTray: 'No files staged.',
    stageFileLabel: 'Choose a staged file',
    attachLabel: 'Stage file',
    removeAttachment: 'Remove file',
    pendingFiles: 'Pending files: {value}. Nothing is sent until you press Send.',
    send: 'Send',
    sendFailed: 'Message not sent. Your text is kept; retry to send the same message.',
    retry: 'Retry',
    connectionLabel: 'Live updates',
    streaming: 'Connected',
    reconnecting: 'Reconnecting…',
    polling: 'Reconnecting with polling',
  },
  ar: {
    title: 'الرسائل الفورية',
    syntheticNotice: 'محادثات توضيحية فقط. المعرّفات محلية لهذا المستأجر.',
    emergencyBanner: 'هذه الرسائل الفورية ليست للطوارئ. اتصل بخدمات الطوارئ للمساعدة السريرية العاجلة.',
    conversationsLabel: 'المحادثات',
    emptyConversations: 'لا توجد محادثات في هذا المرفق.',
    unreadBadge: '{value} غير مقروءة',
    threadLabel: 'مسار المحادثة',
    emptyThread: 'لا توجد رسائل في هذا المسار بعد.',
    noConversation: 'اختر محادثة للقراءة والرد.',
    deliverySending: 'جارٍ الإرسال',
    deliveryDelivered: 'تم الإرسال',
    deliveryFailed: 'لم تُرسل',
    outgoingLabel: 'أنت',
    incomingLabel: 'الموظف',
    composerLabel: 'كتابة رد',
    bodyLabel: 'الرسالة',
    attachmentTrayLabel: 'صندوق المرفقات',
    emptyTray: 'لا توجد ملفات مُجهّزة.',
    stageFileLabel: 'اختر ملفًا مُجهّزًا',
    attachLabel: 'تجهيز الملف',
    removeAttachment: 'إزالة الملف',
    pendingFiles: 'الملفات المعلقة: {value}. لا يُرسل شيء قبل الضغط على إرسال.',
    send: 'إرسال',
    sendFailed: 'لم تُرسل الرسالة. تم الاحتفاظ بنصك؛ أعد المحاولة لإرسال الرسالة نفسها.',
    retry: 'إعادة المحاولة',
    connectionLabel: 'التحديثات المباشرة',
    streaming: 'متصل',
    reconnecting: 'جارٍ إعادة الاتصال…',
    polling: 'إعادة الاتصال عبر الاستقصاء',
  },
};

/** Look up one messenger label for the active locale. */
export function messengerText(locale: Locale, key: MessengerMessageKey): string {
  return messengerMessages[locale][key];
}

/**
 * Interpolate one value into a messenger label. The placeholder is replaced
 * literally, so a synthetic identifier or file name never becomes a format
 * string, and both languages keep the same key set.
 */
export function messengerTextWithValue(
  locale: Locale,
  key: MessengerMessageKey,
  value: string | number,
): string {
  return messengerText(locale, key).replace('{value}', () => String(value));
}
