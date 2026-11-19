/**
 * Pure attachment domain for S-G3-06 (MSG-03, ARC-06).
 *
 * This module holds no React, Expo or network dependency. Every input is a
 * synthetic fixture supplied by the caller, so validation and access rules can
 * be exercised deterministically on both iOS and Android test targets.
 */

/** The only three attachment media types accepted by V1. */
export type MimeType = 'application/pdf' | 'image/jpeg' | 'image/png';

/** Independent quarantine result. A quarantined file is never accessible. */
export type ScanState = 'quarantined' | 'clean' | 'failed';

/** A synthetic validation failure, shown to the sender with stable labels. */
export type ValidationError =
  | 'unsupportedType'
  | 'magicMismatch'
  | 'sizeOutOfRange'
  | 'checksumMismatch';

/** Recipient care unit shown before the patient explicitly sends. */
export interface CareUnitRef {
  /** Organization identifier of the care unit. */
  organizationId: string;
  /** Facility code of the care unit. */
  facilityCode: string;
  /** Human-readable unit name. */
  unitName: string;
}

/** One pending attachment with the metadata needed for local validation. */
export interface PendingAttachment {
  /** Synthetic local identifier, never a real record identifier. */
  id: string;
  /** MIME type declared by the sender. */
  declaredMimeType: string;
  /** Original payload bytes, synthetic in every fixture. */
  bytes: Uint8Array;
  /** SHA-256 digest declared by the sender, lowercase or uppercase hex. */
  declaredSha256: string;
  /** Current quarantine verdict. */
  scanState: ScanState;
  /** Care unit the upload is bound to by the server. */
  unitId: string;
  /** UTC instant of the synthetic upload. */
  uploadedAt: string;
  /** Synthetic subject that bound the upload. */
  senderSubject: string;
}

/** Largest accepted payload, ten mebibytes. */
export const MAX_SIZE_BYTES = 10 * 1024 * 1024;

/** Leading-byte signatures for the three supported media types. */
const MAGIC: Record<MimeType, readonly number[]> = {
  'application/pdf': [0x25, 0x50, 0x44, 0x46, 0x2d],
  'image/jpeg': [0xff, 0xd8, 0xff],
  'image/png': [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a],
};

/** SHA-256 round constants. */
const K: readonly number[] = [
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
  0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
  0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
  0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
  0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
  0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
  0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
  0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
];

/** Right rotation of a 32-bit word. */
const rotr = (value: number, bits: number): number =>
  (value >>> bits) | (value << (32 - bits));

/**
 * Compute a SHA-256 digest in pure TypeScript so no native crypto bridge is
 * needed. The original bytes stay in memory only for this call and are never
 * logged, rendered or persisted by this module.
 *
 * @param bytes original synthetic payload bytes
 * @returns lowercase 64-character hexadecimal digest
 */
export function sha256Hex(bytes: Uint8Array): string {
  const paddedLength = Math.ceil((bytes.length + 9) / 64) * 64;
  const padded = new Uint8Array(paddedLength);
  padded.set(bytes);
  padded[bytes.length] = 0x80;
  const bitLength = bytes.length * 8;
  const view = new DataView(padded.buffer);
  view.setUint32(paddedLength - 8, Math.floor(bitLength / 0x100000000));
  view.setUint32(paddedLength - 4, bitLength >>> 0);

  let h0 = 0x6a09e667;
  let h1 = 0xbb67ae85;
  let h2 = 0x3c6ef372;
  let h3 = 0xa54ff53a;
  let h4 = 0x510e527f;
  let h5 = 0x9b05688c;
  let h6 = 0x1f83d9ab;
  let h7 = 0x5be0cd19;

  const words = new Uint32Array(64);
  for (let offset = 0; offset < paddedLength; offset += 64) {
    for (let index = 0; index < 16; index += 1) {
      words[index] = view.getUint32(offset + index * 4);
    }
    for (let index = 16; index < 64; index += 1) {
      const w15 = words[index - 15]!;
      const w2 = words[index - 2]!;
      const s0 = rotr(w15, 7) ^ rotr(w15, 18) ^ (w15 >>> 3);
      const s1 = rotr(w2, 17) ^ rotr(w2, 19) ^ (w2 >>> 10);
      words[index] = (words[index - 16]! + s0 + words[index - 7]! + s1) >>> 0;
    }

    let a = h0;
    let b = h1;
    let c = h2;
    let d = h3;
    let e = h4;
    let f = h5;
    let g = h6;
    let h = h7;
    for (let index = 0; index < 64; index += 1) {
      const s1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
      const choose = (e & f) ^ (~e & g);
      const temp1 = (h + s1 + choose + K[index]! + words[index]!) >>> 0;
      const s0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
      const majority = (a & b) ^ (a & c) ^ (b & c);
      const temp2 = (s0 + majority) >>> 0;
      h = g;
      g = f;
      f = e;
      e = (d + temp1) >>> 0;
      d = c;
      c = b;
      b = a;
      a = (temp1 + temp2) >>> 0;
    }
    h0 = (h0 + a) >>> 0;
    h1 = (h1 + b) >>> 0;
    h2 = (h2 + c) >>> 0;
    h3 = (h3 + d) >>> 0;
    h4 = (h4 + e) >>> 0;
    h5 = (h5 + f) >>> 0;
    h6 = (h6 + g) >>> 0;
    h7 = (h7 + h) >>> 0;
  }
  return [h0, h1, h2, h3, h4, h5, h6, h7]
    .map((word) => word.toString(16).padStart(8, '0'))
    .join('');
}

/** Resolve a declared MIME type to a supported type, or null when unsupported. */
export function supportedMime(value: string): MimeType | null {
  if (value === 'application/pdf') return 'application/pdf';
  if (value === 'image/jpeg') return 'image/jpeg';
  if (value === 'image/png') return 'image/png';
  return null;
}

/** Check that the leading bytes agree with the resolved media type. */
export function hasMatchingMagic(bytes: Uint8Array, mime: MimeType): boolean {
  const signature = MAGIC[mime];
  if (bytes.length < signature.length) return false;
  return signature.every((byte, index) => bytes[index] === byte);
}

/** Collect every validation failure; an invalid file may have more than one. */
export function validateAttachment(attachment: PendingAttachment): readonly ValidationError[] {
  const errors: ValidationError[] = [];
  const mime = supportedMime(attachment.declaredMimeType);
  if (mime === null) {
    errors.push('unsupportedType');
  } else if (!hasMatchingMagic(attachment.bytes, mime)) {
    errors.push('magicMismatch');
  }
  if (attachment.bytes.length < 1 || attachment.bytes.length > MAX_SIZE_BYTES) {
    errors.push('sizeOutOfRange');
  }
  if (sha256Hex(attachment.bytes) !== attachment.declaredSha256.toLowerCase()) {
    errors.push('checksumMismatch');
  }
  return errors;
}

/** Report whether an attachment is bound to the given care unit. */
export function isBoundToUnit(attachment: PendingAttachment, unitId: string): boolean {
  return attachment.unitId === unitId;
}

/**
 * Decide whether a mediated download may be offered. The clean verdict,
 * authorized publication and unit binding are separate facts; all must hold.
 */
export function canDownload(
  attachment: PendingAttachment,
  activeUnitId: string,
  publicationAuthorized: boolean,
): boolean {
  return (
    attachment.scanState === 'clean' &&
    publicationAuthorized &&
    isBoundToUnit(attachment, activeUnitId) &&
    validateAttachment(attachment).length === 0
  );
}

/** Decide whether an explicit send is allowed for the whole pending set. */
export function canSend(pending: readonly PendingAttachment[]): boolean {
  return (
    pending.length > 0 &&
    pending.every((item) => item.scanState === 'clean' && validateAttachment(item).length === 0)
  );
}
