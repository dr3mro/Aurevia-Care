import { Alert } from 'react-native';

import type { DownloadAuthorizer } from './reportClient';
import type { DownloadAuthorization, ReleasedReport } from './types';

/** Explicit user-intent prompt; resolves true only on an affirmative choice. */
export type Confirmer = (message: string) => Promise<boolean>;

/**
 * Confirm intent before asking the server to authorize a download (PAT-02).
 *
 * The client-side prompt is not an authorization decision: the server re-checks the verified
 * patient link, the current release and the exact revision. Returning `null` on cancel means no
 * document reference is ever produced without explicit intent.
 */
export async function confirmAndAuthorizeDownload(
  client: DownloadAuthorizer,
  report: ReleasedReport,
  confirm: Confirmer,
): Promise<DownloadAuthorization | null> {
  const approved = await confirm(`Download ${report.reportId} revision ${report.revision}?`);
  if (!approved) {
    return null;
  }
  return client.authorizeDownload(report.reportId, report.revision);
}

/**
 * Hand a server-authorized document reference to the platform save surface.
 *
 * The seam receives only the validated authorization, never rendered bytes or a
 * checksum to retain. The reference is valid for the one confirmed action, so
 * the handoff owns it and the app keeps no clinical state after the call.
 */
export type DocumentHandoff = (authorization: DownloadAuthorization) => Promise<void> | void;

/**
 * Confirm intent, authorize the exact tapped revision, then hand off once.
 *
 * A cancellation returns before any request or handoff. An authorization that
 * is malformed, bound to another patient or no longer current is rejected by
 * the client and never reaches the handoff. This function keeps no document
 * data; it only forwards the server's decision to the injected seam.
 *
 * @param client fresh authorization port
 * @param report the revision the patient tapped
 * @param confirm explicit intent prompt
 * @param handoff platform save surface, called once for a valid authorization
 */
export async function downloadWithHandoff(
  client: DownloadAuthorizer,
  report: ReleasedReport,
  confirm: Confirmer,
  handoff: DocumentHandoff,
): Promise<void> {
  const authorization = await confirmAndAuthorizeDownload(client, report, confirm);
  if (authorization === null) {
    return;
  }
  await handoff(authorization);
}

/**
 * Build the production confirmation prompt using the native alert.
 *
 * The buttons carry generic text only; patient identifiers and clinical content stay in the
 * authenticated app and are never placed in notification or alert payloads.
 */
export function createAlertConfirmer(): Confirmer {
  return (message) => new Promise<boolean>((resolve) => {
    Alert.alert('Save a local copy', message, [
      { text: 'Cancel', style: 'cancel', onPress: () => resolve(false) },
      { text: 'Download', onPress: () => resolve(true) },
    ]);
  });
}
