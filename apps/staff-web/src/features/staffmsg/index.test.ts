import { describe, expect, it } from 'vitest';
import * as staffMessaging from './index';

/** Component names that make up the single public surface of this feature. */
const PUBLIC_COMPONENTS = [
  'DirectoryPage',
  'MailPage',
  'MailScreen',
  'MessengerPanel',
  'PatientReferenceChip',
  'RecipientPicker',
  'ReferenceInserter',
  'SettingsPage',
  'UnreadBadge',
];

describe('staff messaging feature exports', () => {
  it('exports only the public components', () => {
    expect(Object.keys(staffMessaging).sort()).toEqual([...PUBLIC_COMPONENTS].sort());
  });
});
