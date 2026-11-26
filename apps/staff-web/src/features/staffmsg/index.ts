/**
 * Single public barrel for the staff messaging feature (MSG-02, MSG-03, WEB-09).
 *
 * Only components are exported here, so an owning screen has one stable import
 * path for every visible part. Prop, domain and wire types stay in the owning
 * sub-module barrels (for example `./mail` or `./settings`) so this surface does
 * not duplicate their contracts. All examples use synthetic, tenant-local
 * identifiers; nothing in this barrel reads real patient data.
 */
export { MailPage, MailScreen } from './mail';
export { MessengerPanel } from './messenger/MessengerPanel';
export { DirectoryPage, RecipientPicker } from './directory';
export { PatientReferenceChip, ReferenceInserter } from './references';
export { SettingsPage, UnreadBadge } from './settings';
