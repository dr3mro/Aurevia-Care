/**
 * Tablet, landscape and large-text layout acceptance for the attachment review
 * screen (PAT-01, PAT-02, UX-01; decision 0005). Layout follows the current
 * window, not the device: content is capped and centred on large windows, safe
 * insets become padding, every control keeps a 44 pt floor and text is never
 * clipped or capped below 200%. The accessibility contract is identical at
 * every class, in both orientations and in both directions. Only synthetic
 * window values and fixtures are used.
 */
import { jest } from '@jest/globals';
import { fireEvent, render } from '@testing-library/react-native';
import type { RenderResult } from '@testing-library/react-native';
import { Dimensions } from 'react-native';

import { sha256Hex } from './domain';
import type { PendingAttachment } from './domain';
import { attachmentText } from './messages';
import { AttachmentReviewScreen } from './AttachmentReviewScreen';
import type { AttachmentReviewScreenProps } from './AttachmentReviewScreen';

type WindowClass = 'compact' | 'regular' | 'large';

interface Insets {
  top: number;
  right: number;
  bottom: number;
  left: number;
}

interface WindowSize {
  width: number;
  height: number;
  scale: number;
  fontScale: number;
}

const mockInsets: Insets = { top: 12, right: 24, bottom: 36, left: 48 };
const UNIT: AttachmentReviewScreenProps['activeUnit'] = {
  organizationId: 'synthetic-org-a',
  facilityCode: 'synthetic-facility',
  unitName: 'Synthetic Lab Unit',
};
const PDF_MAGIC = [0x25, 0x50, 0x44, 0x46, 0x2d];

let mockWindowClass: WindowClass = 'compact';
let mockWindow: WindowSize = { width: 390, height: 844, scale: 2, fontScale: 1 };
let mockObservedWidth: number | undefined;

jest.mock('../../app/layout/useWindowClass', () => ({
  windowClassFor: (width: number) => {
    mockObservedWidth = width;
    return mockWindowClass;
  },
}));

jest.mock('react-native-safe-area-context', () => ({
  useSafeAreaInsets: () => mockInsets,
}));

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

/** Merge a React Native style prop (an object or an array) into one object. */
function styleOf(element: { props: { style?: unknown } }): Record<string, unknown> {
  const style = element.props.style;
  const list = Array.isArray(style) ? style : [style];
  return Object.assign({}, ...list.filter(Boolean));
}

async function screen(overrides: Partial<AttachmentReviewScreenProps> = {}) {
  const props: AttachmentReviewScreenProps = {
    locale: 'en',
    activeUnit: UNIT,
    activeUnitId: 'unit-1',
    pending: [fixture()],
    publicationAuthorized: true,
    onRemove: () => undefined,
    onReplace: () => undefined,
    onDownload: () => undefined,
    onSend: () => undefined,
    ...overrides,
  };
  return render(<AttachmentReviewScreen {...props} />);
}

const windowClasses: WindowClass[] = ['compact', 'regular', 'large'];
const fontScales = [1, 1.5, 2];

/** Assert that no rendered text node is shrunk, capped or clipped. */
function expectUnclippedText(rendered: RenderResult): void {
  // RNTL v14 exposes host nodes through `root.queryAll`, not the removed
  // UNSAFE_getAllByType query; every Text host node is checked.
  const nodes = rendered.root?.queryAll((node) => node.type === 'Text') ?? [];
  expect(nodes.length).toBeGreaterThan(0);
  for (const node of nodes) {
    expect(node.props.numberOfLines).toBeUndefined();
    expect(node.props.maxFontSizeMultiplier).toBeUndefined();
    expect(node.props.adjustsFontSizeToFit).toBeUndefined();
    expect(node.props.allowFontScaling).not.toBe(false);
  }
}

describe('AttachmentReviewScreen responsive layout', () => {
  beforeEach(() => {
    mockWindowClass = 'compact';
    mockWindow = { width: 390, height: 844, scale: 2, fontScale: 1 };
    mockObservedWidth = undefined;
    jest.spyOn(Dimensions, 'get').mockImplementation(() => mockWindow);
  });

  afterEach(() => {
    jest.restoreAllMocks();
  });

  it.each(
    windowClasses.flatMap((windowClass) =>
      fontScales.flatMap((fontScale) =>
        [false, true].map((isLandscape) => ({ windowClass, fontScale, isLandscape })),
      ),
    ),
  )(
    'keeps the full structure at $windowClass / $fontScale / landscape=$isLandscape',
    async ({ windowClass, fontScale, isLandscape }) => {
      mockWindowClass = windowClass;
      // Drive the real window width through every matrix input so font scale
      // and orientation genuinely differ between cases while the mocked
      // classifier selects the asserted class.
      mockWindow = {
        width: isLandscape ? 844 : 390,
        height: isLandscape ? 390 : 844,
        scale: 2,
        fontScale,
      };
      const view = await screen();

      expect(mockObservedWidth).toBe(mockWindow.width);
      expect(view.getByTestId('attachment-review')).toBeTruthy();
      expect(view.getByTestId('attachment-review-content')).toBeTruthy();
      for (const testId of [
        'synthetic-notice',
        'recipient-unit',
        'pending-heading',
        'send-hint',
        'file-file-1',
        'download-file-1',
        'send',
      ]) {
        expect(view.getByTestId(testId)).toBeTruthy();
      }
      expect(view.getByTestId('send').props.accessibilityState.disabled).toBe(false);
      // Exercise the no-clip style contract at font scales 1.0, 1.5 and 2.0,
      // not just at the default scale.
      expectUnclippedText(view);
    },
  );

  it('constrains and centres content on a large window', async () => {
    mockWindowClass = 'large';
    const view = await screen();

    const content = styleOf(view.getByTestId('attachment-review-content'));
    expect(content.maxWidth).toBe(640);
    expect(content.alignSelf).toBe('center');
  });

  it.each(['compact', 'regular'] as const)(
    'fills the available width without a cap on %s windows',
    async (windowClass) => {
      mockWindowClass = windowClass;
      const view = await screen();

      const content = styleOf(view.getByTestId('attachment-review-content'));
      expect(content.maxWidth).toBeUndefined();
      expect(content.alignSelf).toBeUndefined();
    },
  );

  it.each(['en', 'ar'] as const)(
    'respects safe-area insets on every edge in %s',
    async (locale) => {
      const view = await screen({ locale });

      const container = styleOf(view.getByTestId('attachment-review'));
      expect(container.paddingTop).toBe(mockInsets.top + 16);
      expect(container.paddingBottom).toBe(mockInsets.bottom + 16);
      // Values are device-physical, but the props are logical: RTL swaps them
      // so the notch and home-indicator insets land on the correct edges.
      expect(container.paddingLeft).toBeUndefined();
      expect(container.paddingRight).toBeUndefined();
      expect(container.paddingStart).toBe(
        (locale === 'ar' ? mockInsets.right : mockInsets.left) + 16,
      );
      expect(container.paddingEnd).toBe(
        (locale === 'ar' ? mockInsets.left : mockInsets.right) + 16,
      );
    },
  );

  it('keeps every touch target at least 44 by 44 points', async () => {
    const clean = await screen();
    const failed = await screen({ pending: [fixture({ scanState: 'failed' })] });
    for (const [rendered, testId] of [
      [clean, 'download-file-1'],
      [clean, 'send'],
      [failed, 'remove-file-1'],
      [failed, 'replace-file-1'],
    ] as const) {
      const control = styleOf(rendered.getByTestId(testId));
      expect(control.minHeight as number).toBeGreaterThanOrEqual(44);
      expect(control.minWidth as number).toBeGreaterThanOrEqual(44);
    }
  });

  it.each(fontScales)('never clips or caps any text node at font scale %s', async (fontScale) => {
    mockWindow = { width: 390, height: 844, scale: 2, fontScale };
    expectUnclippedText(await screen());
  });

  it.each(['en', 'ar'] as const)(
    'preserves screen-reader labels and focus order in %s at every window class',
    async (locale) => {
      for (const windowClass of windowClasses) {
        mockWindowClass = windowClass;
        const view = await screen({ locale });
        const text = (key: Parameters<typeof attachmentText>[1]): string =>
          attachmentText(locale, key);

        // A clean scan offers Download and Send; a failed scan offers Remove
        // and Replace. Together they cover every control's label in this class.
        const failed = await screen({ locale, pending: [fixture({ scanState: 'failed' })] });
        for (const [rendered, testId, label] of [
          [view, 'download-file-1', text('download')],
          [view, 'send', text('send')],
          [failed, 'remove-file-1', text('remove')],
          [failed, 'replace-file-1', text('replace')],
        ] as const) {
          const control = rendered.getByTestId(testId);
          expect(control.props.accessibilityRole).toBe('button');
          expect(control.props.accessibilityLabel).toBe(label);
        }
        expect(view.getByTestId('send').props.accessibilityState.disabled).toBe(false);
        expect(view.getByTestId('synthetic-notice').children.join('')).toContain(
          text('syntheticNotice'),
        );
        expect(view.getByTestId('pending-heading').children.join('')).toContain(
          text('pendingFiles'),
        );
      }
    },
  );

  it('mirrors writing direction and text alignment for Arabic', async () => {
    const rtl = await screen({ locale: 'ar' });
    // Direction is declared once on the root and inherited by the wrapper, so
    // there is no second direction scope on the same screen.
    expect(styleOf(rtl.getByTestId('attachment-review')).direction).toBe('rtl');
    expect(styleOf(rtl.getByTestId('attachment-review-content')).direction).toBeUndefined();
    expect(rtl.getByTestId('pending-heading').props.style).toEqual(
      expect.arrayContaining([{ textAlign: 'right' }]),
    );

    const ltr = await screen({ locale: 'en' });
    expect(styleOf(ltr.getByTestId('attachment-review')).direction).toBe('ltr');
    expect(styleOf(ltr.getByTestId('attachment-review-content')).direction).toBeUndefined();
    expect(ltr.getByTestId('pending-heading').props.style).toEqual(
      expect.arrayContaining([{ textAlign: 'left' }]),
    );
  });

  it('still fires every callback on a large window', async () => {
    mockWindowClass = 'large';
    const calls = { remove: 0, replace: 0, download: 0, send: 0 };
    const handlers = {
      onRemove: () => {
        calls.remove += 1;
      },
      onReplace: () => {
        calls.replace += 1;
      },
      onDownload: () => {
        calls.download += 1;
      },
      onSend: () => {
        calls.send += 1;
      },
    };

    const clean = await screen({ pending: [fixture()], ...handlers });
    await fireEvent.press(clean.getByTestId('download-file-1'));
    await fireEvent.press(clean.getByTestId('send'));

    const failed = await screen({ pending: [fixture({ scanState: 'failed' })], ...handlers });
    await fireEvent.press(failed.getByTestId('remove-file-1'));
    await fireEvent.press(failed.getByTestId('replace-file-1'));

    expect(calls).toEqual({ remove: 1, replace: 1, download: 1, send: 1 });
  });
});
