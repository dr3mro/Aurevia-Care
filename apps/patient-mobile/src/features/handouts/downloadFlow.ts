/**
 * Explicit save/share intent before a handout copy is authorized (PAT-02).
 *
 * The confirmation is not an authorization decision: the server re-checks the
 * verified patient link, the exact version and the current release. Returning
 * `null` on cancel means no document reference is ever produced without an
 * explicit patient choice.
 */
import { Alert } from 'react-native';

import type { HandoutAuthorizer } from './handoutClient';
import type { HandoutAuthorization, ReleasedHandout } from './types';

/** The patient's chosen disposition for a local copy. */
export type HandoutIntent = 'save' | 'share' | 'cancel';

/** Resolves only when the patient chooses an action. */
export type Confirmer = (message: string) => Promise<HandoutIntent>;

/** An authorization paired with the intent the patient confirmed. */
export interface ConfirmedHandoutDownload {
  readonly authorization: HandoutAuthorization;
  readonly intent: 'save' | 'share';
}

/**
 * Confirm intent, then ask the server to authorize the exact tapped version.
 *
 * @param client download authorizer
 * @param handout the handout the patient tapped
 * @param confirm intent prompt
 * @returns the authorization and intent, or `null` when the patient cancelled
 */
export async function confirmAndAuthorizeDownload(
  client: HandoutAuthorizer,
  handout: ReleasedHandout,
  confirm: Confirmer,
): Promise<ConfirmedHandoutDownload | null> {
  const intent = await confirm(`Save or share handout ${handout.handoutId} version ${handout.version}?`);
  if (intent === 'cancel') {
    return null;
  }
  const authorization = await client.authorizeDownload(handout.handoutId, handout.version);
  return { authorization, intent };
}

/**
 * Build the production intent prompt using the native alert.
 *
 * Buttons carry generic text only; patient identifiers and clinical content
 * stay in the authenticated app and never enter a notification payload.
 */
export function createHandoutAlertConfirmer(): Confirmer {
  return (message) => new Promise<HandoutIntent>((resolve) => {
    Alert.alert('Handout copy', message, [
      { text: 'Cancel', style: 'cancel', onPress: () => resolve('cancel') },
      { text: 'Share', onPress: () => resolve('share') },
      { text: 'Save', onPress: () => resolve('save') },
    ]);
  });
}
