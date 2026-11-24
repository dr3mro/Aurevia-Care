import { describe, expect, it } from 'vitest';
import { mailKeys, mailMessages, mailText } from './labels';

describe('mail labels', () => {
  it('has a distinct key for every label', () => {
    expect(new Set(mailKeys).size).toBe(mailKeys.length);
    expect([...mailKeys].sort()).toEqual(
      [
        'addCc',
        'addTo',
        'attachmentHint',
        'attachmentsLabel',
        'attachLabel',
        'autosaveStatus',
        'blockedRecipient',
        'bodyLabel',
        'cancel',
        'ccLabel',
        'ccPickerLabel',
        'compareTitle',
        'composeLabel',
        'confirm',
        'confirmDetail',
        'confirmTitle',
        'copyNotice',
        'directoryLabel',
        'emptyFolder',
        'folderArchive',
        'folderDrafts',
        'folderInbox',
        'folderSent',
        'folderTrash',
        'foldersLabel',
        'forward',
        'fromLabel',
        'kindPerson',
        'kindRole',
        'kindUnitMailbox',
        'messagesLabel',
        'newMail',
        'noDirectory',
        'noSelection',
        'otherFacility',
        'readingPaneLabel',
        'recipientsLabel',
        'removeRecipient',
        'reply',
        'replyAll',
        'retryCommand',
        'send',
        'sentLabel',
        'sentStatus',
        'storedValues',
        'subjectLabel',
        'syntheticNotice',
        'title',
        'toLabel',
        'toPickerLabel',
        'validationNoDraft',
        'workingCopy',
        'yourValues',
      ].sort(),
    );
  });

  it('has a non-empty label for every key in both bundles', () => {
    for (const locale of ['en', 'ar'] as const) {
      for (const key of mailKeys) {
        expect(mailMessages[locale][key].length).toBeGreaterThan(0);
      }
    }
  });

  it('resolves each key from the active bundle', () => {
    for (const locale of ['en', 'ar'] as const) {
      for (const key of mailKeys) {
        expect(mailText(locale, key)).toBe(mailMessages[locale][key]);
      }
    }
  });

  it('translates the headings, folders and actions into Arabic', () => {
    for (const key of ['title', 'folderInbox', 'folderSent', 'send', 'confirmTitle'] as const) {
      expect(mailText('ar', key)).not.toBe(mailText('en', key));
      expect(mailText('ar', key)).toMatch(/[\u0600-\u06FF]/);
    }
  });
});
