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
