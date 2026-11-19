import type { GenericNotification } from './types';

/**
 * Build the generic push notification representation for a new instruction.
 *
 * The text is fixed and carries no patient, unit, instruction or clinical
 * detail. Full content is retrieved only through an authenticated read after
 * the patient opens the app (MSG-05).
 *
 * @returns the generic notification without any clinical payload
 */
export function buildGenericNotification(): GenericNotification {
  return {
    title: 'New message',
    body: 'You have a new message. Open the app to read it.',
    deepLink: 'aurevia://messages/inbox',
  };
}
