import type {
  DisplayInstruction,
  InstructionKind,
  PatientInstruction,
  TextDirection,
} from './types';

/**
 * Pure, side-effect-free projections for the patient instruction inbox.
 *
 * Content stays inside the authenticated app: notifications use a generic
 * label only, and withdrawn or superseded revisions are never presented as
 * current instructions. All functions accept synthetic fixtures and keep the
 * server as the sole owner of clinical content.
 */

const KIND_LABELS: Record<InstructionKind, string> = {
  collection: 'collection',
  imaging_preparation: 'imaging preparation',
  therapy_home: 'therapy home',
  visit_follow_up: 'visit follow-up',
  pharmacy_pickup: 'pharmacy pickup',
  general: 'general',
};

/**
 * Select the current revision of each thread.
 *
 * Draft and superseded revisions are hidden; withdrawn revisions remain
 * visible with their state so the app can show that access ended.
 *
 * @param instructions instructions supplied by the authenticated API client
 * @return at most one current revision per thread
 */
export function currentInstructions(
  instructions: readonly PatientInstruction[],
): PatientInstruction[] {
  const latest = new Map<string, PatientInstruction>();
  for (const instruction of instructions) {
    if (instruction.state === 'draft' || instruction.state === 'superseded') {
      continue;
    }
    const existing = latest.get(instruction.threadId);
    if (existing === undefined || instruction.revision > existing.revision) {
      latest.set(instruction.threadId, instruction);
    }
  }
  return [...latest.values()];
}

/**
 * Report whether a revision has been withdrawn.
 *
 * @param instruction instruction to inspect
 * @return true when future patient access is stopped
 */
export function isWithdrawn(instruction: PatientInstruction): boolean {
  return instruction.state === 'withdrawn';
}

/**
 * Project an instruction for display with its language direction.
 *
 * @param instruction instruction to display
 * @return localized title, body and direction
 */
export function displayInstruction(instruction: PatientInstruction): DisplayInstruction {
  const direction: TextDirection = instruction.language.toLowerCase().startsWith('ar')
    ? 'rtl'
    : 'ltr';
  return { title: instruction.title, text: instruction.text, direction };
}

/**
 * Format the optional action due time in the given IANA time zone.
 *
 * @param instruction instruction carrying the optional instant
 * @param timeZone IANA time zone of the facility
 * @return formatted label, or null when no action time was requested
 */
export function actionDueLabel(
  instruction: PatientInstruction,
  timeZone: string,
): string | null {
  if (instruction.actionDueAt === null) {
    return null;
  }
  return new Intl.DateTimeFormat('en-GB', {
    dateStyle: 'medium',
    timeStyle: 'short',
    timeZone,
  }).format(new Date(instruction.actionDueAt));
}

/**
 * Build a push-notification string that reveals no clinical content.
 *
 * @param instruction instruction that triggered the notification
 * @return generic notification text; the app fetches details after auth
 */
export function genericNotification(instruction: PatientInstruction): string {
  return `You have a new ${KIND_LABELS[instruction.kind]} instruction from ${instruction.unitName}.`;
}

/**
 * Return the revision history of a thread in ascending order.
 *
 * @param instructions instructions supplied by the authenticated API client
 * @param threadId thread to select
 * @return ordered immutable revisions
 */
export function historyForThread(
  instructions: readonly PatientInstruction[],
  threadId: string,
): PatientInstruction[] {
  return instructions
    .filter((instruction) => instruction.threadId === threadId)
    .sort((left, right) => left.revision - right.revision);
}

/**
 * Decide whether a secure attachment may be opened.
 *
 * @param instruction owning instruction
 * @param attachmentId attachment identifier selected by the patient
 * @return true only for a sent revision that binds the attachment
 */
export function canOpenAttachment(
  instruction: PatientInstruction,
  attachmentId: string,
): boolean {
  return instruction.state === 'sent' && instruction.attachmentIds.includes(attachmentId);
}

/**
 * Compose the organization and unit shown to the patient before sending.
 *
 * @param instruction instruction carrying the sender identity
 * @return a stable, non-clinical label
 */
export function unitDisplayName(instruction: PatientInstruction): string {
  return `${instruction.organizationName} - ${instruction.unitName}`;
}
