import { jest } from '@jest/globals';
import { fireEvent, render } from '@testing-library/react-native';

import {
  MAX_SIZE_BYTES,
  canDownload,
  canSend,
  hasMatchingMagic,
  isBoundToUnit,
  sha256Hex,
  supportedMime,
  validateAttachment,
} from './domain';
import type { MimeType, PendingAttachment, ScanState } from './domain';
import { AttachmentReviewScreen } from './AttachmentReviewScreen';
import type { AttachmentReviewScreenProps } from './AttachmentReviewScreen';
import { attachmentKeys, attachmentText } from './messages';
import type { Locale } from './messages';

const PDF_MAGIC = [0x25, 0x50, 0x44, 0x46, 0x2d];
const JPEG_MAGIC = [0xff, 0xd8, 0xff];
const PNG_MAGIC = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];

const UNIT: AttachmentReviewScreenProps['activeUnit'] = {
  organizationId: 'synthetic-org-a',
  facilityCode: 'synthetic-facility',
  unitName: 'Synthetic Lab Unit',
};

function bytes(prefix: readonly number[], length: number): Uint8Array {
  const result = new Uint8Array(prefix.length + length);
  result.set(prefix);
  return result;
}

function fixture(overrides: Partial<PendingAttachment> = {}): PendingAttachment {
  const payload = bytes(PDF_MAGIC, 32);
  return {
    id: 'file-1',
    declaredMimeType: 'application/pdf',
    bytes: payload,
    declaredSha256: sha256Hex(payload),
    scanState: 'clean',
    unitId: 'unit-1',
    uploadedAt: '2026-11-19T12:00:00Z',
    senderSubject: 'synthetic-patient',
    ...overrides,
  };
}

function captureConsole() {
  const original = { log: console.log, warn: console.warn, error: console.error };
  const captured: string[] = [];
  const record = (...args: unknown[]): void => {
    captured.push(args.map((arg) => String(arg)).join(' '));
  };
  console.log = record;
  console.warn = record;
  console.error = record;
  return {
    captured,
    restore: (): void => {
      console.log = original.log;
      console.warn = original.warn;
      console.error = original.error;
    },
  };
}

async function screen(overrides: Partial<AttachmentReviewScreenProps> = {}) {
  const calls = {
    remove: [] as string[],
    replace: [] as string[],
    download: [] as string[],
    send: [] as string[][],
  };
  const props: AttachmentReviewScreenProps = {
    locale: 'en',
    activeUnit: UNIT,
    activeUnitId: 'unit-1',
    pending: [fixture()],
    publicationAuthorized: true,
    onRemove: (id) => calls.remove.push(id),
    onReplace: (id) => calls.replace.push(id),
    onDownload: (id) => calls.download.push(id),
    onSend: (ids) => calls.send.push([...ids]),
    ...overrides,
  };
  const result = await render(<AttachmentReviewScreen {...props} />);
  return { ...result, calls, props };
}

describe('SHA-256 integrity primitive', () => {
  it('matches published vectors for one and two-block messages', () => {
    expect(sha256Hex(new Uint8Array())).toBe(
      'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
    );
    expect(sha256Hex(new TextEncoder().encode('abc'))).toBe(
      'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad',
    );
    expect(sha256Hex(new TextEncoder().encode('a'.repeat(100)))).toBe(
      '2816597888e4a0d3a36b82b83316ab32680eb8f00f8cd3b904d681246d285a0e',
    );
  });
});

describe('attachment validation', () => {
  it('resolves each supported media type and rejects the rest', () => {
    expect(supportedMime('application/pdf')).toBe('application/pdf');
    expect(supportedMime('image/jpeg')).toBe('image/jpeg');
    expect(supportedMime('image/png')).toBe('image/png');
    expect(supportedMime('text/plain')).toBeNull();
  });

  it('matches each magic signature, rejects a short prefix and a wrong signature', () => {
    expect(hasMatchingMagic(bytes(PDF_MAGIC, 2), 'application/pdf')).toBe(true);
    expect(hasMatchingMagic(bytes(JPEG_MAGIC, 2), 'image/jpeg')).toBe(true);
    expect(hasMatchingMagic(bytes(PNG_MAGIC, 2), 'image/png')).toBe(true);
    expect(hasMatchingMagic(new Uint8Array([0x25]), 'application/pdf')).toBe(false);
    expect(hasMatchingMagic(bytes(PNG_MAGIC, 2), 'image/jpeg')).toBe(false);
  });

  it('accepts each supported type with correct bytes and checksum', () => {
    const signatures: Record<MimeType, readonly number[]> = {
      'application/pdf': PDF_MAGIC,
      'image/jpeg': JPEG_MAGIC,
      'image/png': PNG_MAGIC,
    };
    for (const mime of Object.keys(signatures) as MimeType[]) {
      const payload = bytes(signatures[mime], 16);
      expect(validateAttachment(fixture({
        declaredMimeType: mime,
        bytes: payload,
        declaredSha256: sha256Hex(payload),
      }))).toEqual([]);
    }
  });

  it('collects every independent failure without early return', () => {
    const invalid = fixture({
      declaredMimeType: 'text/plain',
      bytes: new Uint8Array(),
      declaredSha256: 'not-a-digest',
    });
    expect(validateAttachment(invalid)).toEqual([
      'unsupportedType',
      'sizeOutOfRange',
      'checksumMismatch',
    ]);
  });

  it('reports a magic mismatch while the declared type is otherwise supported', () => {
    const payload = bytes(PNG_MAGIC, 8);
    expect(validateAttachment(fixture({
      bytes: payload,
      declaredSha256: sha256Hex(payload),
    }))).toEqual(['magicMismatch']);
    expect(validateAttachment(fixture({ bytes: new Uint8Array([0x25]) }))).toContain('magicMismatch');
  });

  it('enforces the lower and upper size boundaries', () => {
    const empty = new Uint8Array();
    expect(validateAttachment(fixture({
      bytes: empty,
      declaredSha256: sha256Hex(empty),
    }))).toContain('sizeOutOfRange');
    const oversized = new Uint8Array(MAX_SIZE_BYTES + 1);
    oversized.set(PDF_MAGIC);
    expect(validateAttachment(fixture({
      bytes: oversized,
      declaredSha256: sha256Hex(oversized),
    }))).toContain('sizeOutOfRange');
    const exact = new Uint8Array(MAX_SIZE_BYTES);
    exact.set(PDF_MAGIC);
    expect(validateAttachment(fixture({
      bytes: exact,
      declaredSha256: sha256Hex(exact),
    }))).not.toContain('sizeOutOfRange');
  });

  it('compares the declared checksum case-insensitively', () => {
    const payload = bytes(PDF_MAGIC, 8);
    expect(validateAttachment(fixture({
      bytes: payload,
      declaredSha256: sha256Hex(payload).toUpperCase(),
    }))).toEqual([]);
    expect(validateAttachment(fixture({
      bytes: payload,
      declaredSha256: '0'.repeat(64),
    }))).toEqual(['checksumMismatch']);
  });
});

describe('access rules', () => {
  it('binds every rule to the exact care unit', () => {
    expect(isBoundToUnit(fixture(), 'unit-1')).toBe(true);
    expect(isBoundToUnit(fixture(), 'unit-2')).toBe(false);
  });

  it('requires clean, published, bound and valid facts for a download', () => {
    expect(canDownload(fixture(), 'unit-1', true)).toBe(true);
    for (const state of ['quarantined', 'failed'] as ScanState[]) {
      expect(canDownload(fixture({ scanState: state }), 'unit-1', true)).toBe(false);
    }
    expect(canDownload(fixture(), 'unit-1', false)).toBe(false);
    expect(canDownload(fixture(), 'unit-2', true)).toBe(false);
    expect(canDownload(fixture({ declaredSha256: '0'.repeat(64) }), 'unit-1', true)).toBe(false);
  });

  it('requires every file to be clean and valid before an explicit send', () => {
    expect(canSend([])).toBe(false);
    expect(canSend([fixture()])).toBe(true);
    expect(canSend([fixture({ scanState: 'quarantined' })])).toBe(false);
    expect(canSend([fixture({ scanState: 'failed' })])).toBe(false);
    expect(canSend([fixture({ declaredSha256: '0'.repeat(64) })])).toBe(false);
    expect(canSend([fixture(), fixture({ id: 'file-2', scanState: 'quarantined' })])).toBe(false);
  });
});

describe('bilingual labels', () => {
  it('exposes the same key set in English and Arabic', () => {
    const locales: Locale[] = ['en', 'ar'];
    for (const locale of locales) {
      for (const key of attachmentKeys) {
        expect(attachmentText(locale, key).length).toBeGreaterThan(0);
      }
    }
  });
});

describe('attachment review screen', () => {
  jest.setTimeout(15000);
  it('shows the recipient unit and a synthetic-only notice before sending', async () => {
    const view = await screen();
    expect(view.getByTestId('synthetic-notice').children.join('')).toContain('Synthetic fixtures only');
    expect(view.getByTestId('recipient-organization').children.join('')).toContain(UNIT.organizationId);
    expect(view.getByTestId('recipient-facility').children.join('')).toContain(UNIT.facilityCode);
    expect(view.getByTestId('recipient-unit-name').children.join('')).toContain(UNIT.unitName);
    expect(view.getByTestId('send-hint')).toBeTruthy();
  });

  it('mirrors layout and text direction in Arabic', async () => {
    const view = await screen({ locale: 'ar' });
    const container = view.getByTestId('attachment-review');
    expect(container.props.style).toEqual(expect.arrayContaining([{ direction: 'rtl' }]));
    expect(view.getByTestId('pending-heading').props.style).toEqual(
      expect.arrayContaining([{ textAlign: 'right' }]),
    );
  });

  it('renders one generic label per in-unit file and hides other-unit files', async () => {
    const inUnit = fixture();
    const otherUnit = fixture({ id: 'file-2', unitId: 'unit-2' });
    const view = await screen({ pending: [inUnit, otherUnit] });
    expect(view.getByTestId('file-label-file-1')).toBeTruthy();
    expect(view.queryByTestId('file-label-file-2')).toBeNull();
    expect(view.getByTestId('hidden-other-unit')).toBeTruthy();
    expect(view.getByTestId('supplied-file-1')).toBeTruthy();
    expect(view.getByTestId('provenance-file-1').children.join('')).toContain(inUnit.declaredSha256);
  });

  it('renders an empty state when no file belongs to the active unit', async () => {
    const view = await screen({ pending: [fixture({ unitId: 'unit-2' })] });
    expect(view.getByTestId('no-files')).toBeTruthy();
  });

  it('renders each validation error and each scan-state label', async () => {
    const invalid = fixture({
      bytes: new Uint8Array(),
      declaredMimeType: 'text/plain',
      declaredSha256: 'bad',
      scanState: 'failed',
    });
    const view = await screen({ pending: [invalid] });
    expect(view.getByTestId('error-file-1-unsupportedType')).toBeTruthy();
    expect(view.getByTestId('error-file-1-sizeOutOfRange')).toBeTruthy();
    expect(view.getByTestId('error-file-1-checksumMismatch')).toBeTruthy();
    expect(view.getByTestId('scan-file-1').children.join('')).toContain('Scan failed');
    expect(view.queryByTestId('error-file-1-magicMismatch')).toBeNull();
  });

  it('shows the quarantine label without a magic error when bytes are correct', async () => {
    const view = await screen({ pending: [fixture({ scanState: 'quarantined' })] });
    expect(view.getByTestId('scan-file-1').children.join('')).toContain('In quarantine');
    expect(view.getByTestId('download-unavailable-file-1')).toBeTruthy();
  });

  it('offers Remove and Replace only for a failed scan and fires both callbacks', async () => {
    const view = await screen({ pending: [fixture({ scanState: 'failed' })] });
    await fireEvent.press(view.getByTestId('remove-file-1'));
    await fireEvent.press(view.getByTestId('replace-file-1'));
    expect(view.calls.remove).toEqual(['file-1']);
    expect(view.calls.replace).toEqual(['file-1']);

    const cleanView = await screen();
    expect(cleanView.queryByTestId('remove-file-1')).toBeNull();
    expect(cleanView.queryByTestId('replace-file-1')).toBeNull();
  });

  it('offers a mediated download only for a clean, published, in-unit file', async () => {
    const view = await screen();
    await fireEvent.press(view.getByTestId('download-file-1'));
    expect(view.calls.download).toEqual(['file-1']);

    const unpublished = await screen({ publicationAuthorized: false });
    expect(unpublished.getByTestId('download-unavailable-file-1')).toBeTruthy();
    expect(unpublished.queryByTestId('download-file-1')).toBeNull();
  });

  it('keeps Send disabled until every visible file is clean and valid', async () => {
    const disabled = await screen({ pending: [] });
    expect(disabled.getByTestId('send').props.accessibilityState.disabled).toBe(true);
    await fireEvent.press(disabled.getByTestId('send'));
    expect(disabled.calls.send).toEqual([]);

    const invalid = await screen({ pending: [fixture({ declaredSha256: '0'.repeat(64) })] });
    expect(invalid.getByTestId('send').props.accessibilityState.disabled).toBe(true);

    const enabled = await screen();
    expect(enabled.getByTestId('send').props.accessibilityState.disabled).toBe(false);
    expect(enabled.calls.send).toEqual([]);
    await fireEvent.press(enabled.getByTestId('send'));
    expect(enabled.calls.send).toEqual([['file-1']]);
  });

  it('never renders payload bytes, URLs or clinical narrative and logs nothing', async () => {
    const sentinel = 'https://synthetic.invalid/clinical-narrative';
    const consoleCapture = captureConsole();
    let tree = '';
    try {
      const view = await screen({ pending: [fixture({ senderSubject: sentinel })] });
      await fireEvent.press(view.getByTestId('send'));
      await fireEvent.press(view.getByTestId('download-file-1'));
      tree = JSON.stringify(view.toJSON());
    } finally {
      consoleCapture.restore();
    }
    expect(tree).not.toContain(sentinel);
    expect(tree).not.toContain('http');
    expect(consoleCapture.captured).toEqual([]);
  });
});
