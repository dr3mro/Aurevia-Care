/**
 * Authenticated loader for the patient's released prescriptions (RX-02, PAT-02).
 *
 * The loader is the single production caller of the transient prescription
 * store: successful loads replace the held projection, and a failed load leaves
 * no partial clinical data behind. No clinical content is persisted to disk.
 */
import { PRESCRIPTION_COPY } from './messages';
import { isCancelled } from './status';
import { rememberPrescriptions } from './store';
import type { PrescriptionLocale, ReleasedPrescription } from './types';

/** Minimal typed client supplied by the authenticated app shell. */
export interface ReleasedPrescriptionClient {
  /** Fetch the released prescriptions for the signed-in patient. */
  listReleasedPrescriptions(): Promise<readonly ReleasedPrescription[]>;
}

/** Result of loading prescriptions for display. */
export interface PrescriptionLoadResult {
  readonly prescriptions: readonly ReleasedPrescription[];
  readonly error: string | null;
}

/**
 * Load prescriptions for the screen, replacing any failure with a generic
 * message and an empty projection.
 *
 * Cancelled prescriptions are terminal and are excluded before display, so a
 * cancelled item can never be shown with an in-progress or handed-over status.
 *
 * @param client authenticated prescription client
 * @param locale active display locale for the generic error message
 * @returns the prescriptions to show and any generic load error
 */
export async function loadReleasedPrescriptions(
  client: ReleasedPrescriptionClient,
  locale: PrescriptionLocale = 'en',
): Promise<PrescriptionLoadResult> {
  try {
    const released = (await client.listReleasedPrescriptions()).filter(
      (prescription) => !isCancelled(prescription),
    );
    rememberPrescriptions(released);
    return { prescriptions: released, error: null };
  } catch {
    rememberPrescriptions([]);
    return { prescriptions: [], error: PRESCRIPTION_COPY[locale].error };
  }
}
