import { defaultNotificationSettings } from './domain';
import type { NotificationSettings } from './domain';

/** Synthetic unread counts spanning every badge branch. */
export const unreadCountZero = 0;
export const unreadCountOne = 1;
export const unreadCountSeven = 7;
export const unreadCountMany = 120;

/** An opaque, synthetic delegate subject; it names nobody and resolves nowhere. */
export const syntheticDelegate = 'synthetic-delegate-01';

/** An hour outside the default quiet window, so the default preview is inactive. */
export const quietCurrentHour = 12;

/** An hour inside the default 18-08 quiet window, used to prove the active preview. */
export const quietHourInside = 22;

/** The concealed defaults exposed to the settings screen and its tests. */
export const defaultSettings: NotificationSettings = defaultNotificationSettings();

/** A valid out-of-office edit with a delegate and a synthetic auto-reply. */
export const outOfOfficeSettings: NotificationSettings = {
  ...defaultSettings,
  outOfOfficeEnabled: true,
  outOfOfficeReply: 'Synthetic reply: the recipient is away and has a delegate.',
  delegateSubject: syntheticDelegate,
};
