/** Public surface of the synthetic staff notification settings slice. */
export { UnreadBadge } from './UnreadBadge';
export type { UnreadBadgeProps } from './UnreadBadge';
export { SettingsPage } from './SettingsPage';
export type { SettingsPageProps } from './SettingsPage';
export type { DoNotDisturbWindow, NotificationSettings, SettingsErrorKey } from './domain';
export {
  defaultNotificationSettings,
  formatUnreadCount,
  isDoNotDisturbActive,
  validateSettings,
} from './domain';
export { settingsKeys, settingsMessages, settingsText, unreadBadgeLabel } from './labels';
export type { SettingsMessageKey } from './labels';
