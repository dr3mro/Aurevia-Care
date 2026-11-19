/**
 * Bilingual labels for the S-G3-06 attachment review surface (UX-01).
 *
 * The feature owns a small, parity-checked en/ar dictionary so it can be
 * verified without the application shell. Arabic strings are RTL and the
 * screen mirrors its layout from the same locale prop.
 */

/** Supported display locales. */
export type Locale = 'en' | 'ar';

/** Every label key used by the attachment review screen. */
export const attachmentKeys = [
  'syntheticNotice',
  'recipient',
  'organization',
  'facility',
  'unit',
  'pendingFiles',
  'noFiles',
  'file',
  'scanState',
  'quarantined',
  'clean',
  'failed',
  'patientSupplied',
  'provenance',
  'checksum',
  'size',
  'type',
  'boundUnit',
  'uploadedAt',
  'unsupportedType',
  'magicMismatch',
  'sizeOutOfRange',
  'checksumMismatch',
  'remove',
  'replace',
  'download',
  'send',
  'sendHint',
  'notAvailable',
  'hiddenOtherUnit',
] as const;

/** One attachment label key. */
export type AttachmentMessageKey = (typeof attachmentKeys)[number];

/** Bilingual labels with identical key sets. */
const labels: Record<Locale, Record<AttachmentMessageKey, string>> = {
  en: {
    syntheticNotice: 'Synthetic fixtures only. No real clinic, patient or endpoint is contacted.',
    recipient: 'Recipient care unit',
    organization: 'Organization',
    facility: 'Facility',
    unit: 'Unit',
    pendingFiles: 'Pending files — review before sending',
    noFiles: 'No pending files.',
    file: 'File',
    scanState: 'Scan state',
    quarantined: 'In quarantine — not accessible yet',
    clean: 'Clean — ready after authorized send',
    failed: 'Scan failed — remove or replace',
    patientSupplied: 'Patient-supplied material',
    provenance: 'Provenance',
    checksum: 'SHA-256',
    size: 'Size',
    type: 'Type',
    boundUnit: 'Bound unit',
    uploadedAt: 'Uploaded at (UTC)',
    unsupportedType: 'Only PDF, JPEG and PNG files are supported.',
    magicMismatch: 'File contents do not match the declared type.',
    sizeOutOfRange: 'File must be between 1 byte and 10 MiB.',
    checksumMismatch: 'File integrity check failed.',
    remove: 'Remove',
    replace: 'Replace',
    download: 'Download',
    send: 'Send to unit',
    sendHint: 'Nothing is sent until you press Send.',
    notAvailable: 'Not available until a clean scan and authorized publication.',
    hiddenOtherUnit: 'A file bound to another unit is never shown here.',
  },
  ar: {
    syntheticNotice: 'بيانات توضيحية فقط؛ لا يتم الاتصال بأي عيادة أو مريض أو خدمة حقيقية.',
    recipient: 'وحدة الرعاية المستقبِلة',
    organization: 'المؤسسة',
    facility: 'المنشأة',
    unit: 'الوحدة',
    pendingFiles: 'الملفات المعلّقة — راجعها قبل الإرسال',
    noFiles: 'لا توجد ملفات معلّقة.',
    file: 'ملف',
    scanState: 'حالة الفحص',
    quarantined: 'في الحجر — غير متاح بعد',
    clean: 'نظيف — جاهز بعد الإرسال المصرّح به',
    failed: 'فشل الفحص — احذف أو استبدل',
    patientSupplied: 'مواد قدّمها المريض',
    provenance: 'السجل المصدر',
    checksum: 'SHA-256',
    size: 'الحجم',
    type: 'النوع',
    boundUnit: 'الوحدة المرتبطة',
    uploadedAt: 'وقت الرفع (UTC)',
    unsupportedType: 'يُدعم PDF و JPEG و PNG فقط.',
    magicMismatch: 'محتوى الملف لا يطابق النوع المعلن.',
    sizeOutOfRange: 'يجب أن يكون الملف بين بايت واحد و١٠ ميغابايت.',
    checksumMismatch: 'فحص سلامة الملف فشل.',
    remove: 'حذف',
    replace: 'استبدال',
    download: 'تنزيل',
    send: 'إرسال إلى الوحدة',
    sendHint: 'لا يُرسل أي شيء حتى تضغط زر الإرسال.',
    notAvailable: 'غير متاح حتى يكتمل الفحص النظيف والنشر المصرّح به.',
    hiddenOtherUnit: 'الملف المرتبط بوحدة أخرى لا يظهر هنا أبداً.',
  },
};

/** Look up one attachment label for the active locale. */
export function attachmentText(locale: Locale, key: AttachmentMessageKey): string {
  return labels[locale][key];
}
