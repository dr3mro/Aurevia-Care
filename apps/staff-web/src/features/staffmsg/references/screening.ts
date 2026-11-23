/**
 * Fixed codes a subject-line screener may return. `NONE` is the only code that
 * produces no warning; the other four are safe to show as translated copy.
 */
export type SubjectReasonCode = 'NONE' | 'UUID_LIKE' | 'DIGIT_RUN' | 'EMAIL_LIKE' | 'MRN_LIKE';

/**
 * Screening result narrowed by its own status. When `suspicious` is true the
 * reason is guaranteed to be one of the four displayable warning codes.
 */
export type SubjectScreening =
  | { readonly suspicious: false; readonly reason: 'NONE' }
  | { readonly suspicious: true; readonly reason: Exclude<SubjectReasonCode, 'NONE'> };

/**
 * Server-derived outcomes mirrored from the reference access contract. The UI
 * only distinguishes `ALLOWED` from "everything else is blocked"; the specific
 * denial is never shown as target data.
 */
export type AccessOutcome =
  | 'ALLOWED'
  | 'NO_RELATIONSHIP'
  | 'REVOKED_OR_STALE'
  | 'UNKNOWN_TARGET'
  | 'WRONG_TENANT';

// These patterns mirror the backend `ReferenceInputs` screener. The order is
// part of the contract: a long digit run wins over an MRN-like phrase when both
// match, so the client and server report the same reason for the same subject.
const UUID_LIKE = /[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/;
const DIGIT_RUN = /\d{7,}/;
const EMAIL_LIKE = /[^\s@]+@[^\s@]+/;
const MRN_LIKE = /\b(?:mrn|patient|chart|encounter|order)\b[:\s#-]*\d+/i;

/**
 * Screen a subject line for likely identifiers without ever echoing it.
 *
 * @param subject raw subject line; blank input is simply unsuspicious
 * @returns a fixed reason code and a suspicion flag, never the input text
 */
export function screenSubject(subject: string): SubjectScreening {
  if (subject.trim() === '') {
    return { suspicious: false, reason: 'NONE' };
  }
  if (UUID_LIKE.test(subject)) {
    return { suspicious: true, reason: 'UUID_LIKE' };
  }
  if (DIGIT_RUN.test(subject)) {
    return { suspicious: true, reason: 'DIGIT_RUN' };
  }
  if (EMAIL_LIKE.test(subject)) {
    return { suspicious: true, reason: 'EMAIL_LIKE' };
  }
  if (MRN_LIKE.test(subject)) {
    return { suspicious: true, reason: 'MRN_LIKE' };
  }
  return { suspicious: false, reason: 'NONE' };
}
