/**
 * RESP-APP-ACCESSIBILITY-EVIDENCE-SUMMARY (PAT-01, PAT-02, UX-01).
 *
 * The evidence summary is presentation only: these tests render a synthetic
 * report at every window class, orientation and font scale and assert that the
 * responsive structure, the accessibility labels and RTL mirroring hold. No
 * patient data, persistence or API calls are involved (PAT-02).
 */
import { jest } from '@jest/globals';
import { act, render } from '@testing-library/react-native';
import { Dimensions } from 'react-native';

import { evaluateAccessibility, evidenceFailureText, evidenceSummaryLabel } from './accessibility';
import { AccessibilityEvidenceSummary } from './AccessibilityEvidenceSummary';
import { CONFORMING_ELEMENTS } from './fixtures';

/** Maximum inline size applied to a large window. */
const MAX_READABLE_WIDTH = 720;
/** Minimum target/row size in points on every window class (UX-01). */
const MIN_TARGET_SIZE = 44;

type WindowSize = { width: number; height: number; scale: number; fontScale: number };
type ChangeListener = (payload: { window: WindowSize }) => void;
type Style = Record<string, unknown>;

let mockWindow: WindowSize = { width: 390, height: 844, scale: 2, fontScale: 1 };
let mockInsets = { top: 12, right: 24, bottom: 36, left: 48 };
let changeListener: ChangeListener | undefined;

jest.mock('react-native-safe-area-context', () => ({
  useSafeAreaInsets: () => mockInsets,
}));

const report = evaluateAccessibility(CONFORMING_ELEMENTS);
const statusLabel = evidenceSummaryLabel(report);
const detailLabel = evidenceFailureText(report);

const WINDOWS = [
  { name: 'compact portrait', width: 390, height: 844, landscape: false, large: false },
  { name: 'compact landscape', width: 568, height: 320, landscape: true, large: false },
  { name: 'regular portrait', width: 768, height: 1024, landscape: false, large: false },
  { name: 'regular landscape', width: 800, height: 600, landscape: true, large: false },
  { name: 'large portrait', width: 1024, height: 1366, landscape: false, large: true },
  { name: 'large landscape', width: 1366, height: 1024, landscape: true, large: true },
] as const;

function styleOf(view: Awaited<ReturnType<typeof render>>, testID: string): Style {
  return view.getByTestId(testID).props.style as Style;
}

describe('AccessibilityEvidenceSummary responsive layout', () => {
  beforeEach(() => {
    mockWindow = { width: 390, height: 844, scale: 2, fontScale: 1 };
    mockInsets = { top: 12, right: 24, bottom: 36, left: 48 };
    changeListener = undefined;
    jest.spyOn(Dimensions, 'get').mockImplementation(() => mockWindow);
    jest.spyOn(Dimensions, 'addEventListener').mockImplementation((_type, handler) => {
      changeListener = handler as ChangeListener;
      return { remove: () => undefined };
    });
  });

  afterEach(() => {
    jest.restoreAllMocks();
  });

  it.each(WINDOWS)(
    'lays out $name with safe-area insets and a centred max width only when large',
    async ({ width, height, landscape, large }) => {
      expect(landscape).toBe(width > height);
      mockWindow = { width, height, scale: 2, fontScale: 1.5 };
      const view = await render(<AccessibilityEvidenceSummary report={report} isRTL={false} />);
      const style = styleOf(view, 'a11y-evidence-summary');

      expect(style.flexDirection).toBe('row');
      expect(style.flexWrap).toBe('wrap');
      expect(style.paddingTop).toBe(mockInsets.top);
      expect(style.paddingBottom).toBe(mockInsets.bottom);
      expect(style.paddingStart).toBe(mockInsets.left);
      expect(style.paddingEnd).toBe(mockInsets.right);
      expect(style.minHeight).toBe(MIN_TARGET_SIZE);
      expect(style.maxWidth).toBe(large ? MAX_READABLE_WIDTH : undefined);
      expect(style.alignSelf).toBe(large ? 'center' : undefined);
    },
  );

  it.each([1, 1.5, 2])(
    'keeps every text unclipped and at the target floor at font scale %s',
    async (fontScale) => {
      mockWindow = { ...mockWindow, fontScale };
      const view = await render(<AccessibilityEvidenceSummary report={report} isRTL={false} />);

      for (const id of ['a11y-evidence-title', 'a11y-evidence-status', 'a11y-evidence-detail']) {
        const style = styleOf(view, id);
        expect(style.numberOfLines).toBeUndefined();
        expect(style.adjustsFontSizeToFit).toBeUndefined();
        expect(style.maxFontSizeMultiplier).toBeUndefined();
        expect(style.allowFontScaling).not.toBe(false);
        expect(style.flexShrink).toBe(1);
        expect(style.minHeight).toBe(MIN_TARGET_SIZE);
        expect(style.minWidth).toBe(MIN_TARGET_SIZE);
      }
    },
  );

  it('mirrors direction and inline insets for RTL without changing labels', async () => {
    mockWindow = { width: 1366, height: 1024, scale: 2, fontScale: 2 };
    const view = await render(<AccessibilityEvidenceSummary report={report} isRTL={true} />);
    const style = styleOf(view, 'a11y-evidence-summary');

    expect(style.flexDirection).toBe('row-reverse');
    expect(style.paddingStart).toBe(mockInsets.right);
    expect(style.paddingEnd).toBe(mockInsets.left);
    expect(style.maxWidth).toBe(MAX_READABLE_WIDTH);
    expect(view.getByLabelText(statusLabel)).toBeTruthy();
    expect(view.getByLabelText(detailLabel)).toBeTruthy();
    expect(view.getByTestId('a11y-evidence-status').props.accessibilityRole).toBe('text');
    expect(view.getByTestId('a11y-evidence-title').props.accessibilityRole).toBe('header');
  });

  it('recomputes the layout class when a window rotates to large landscape', async () => {
    const view = await render(<AccessibilityEvidenceSummary report={report} isRTL={false} />);
    expect(styleOf(view, 'a11y-evidence-summary').maxWidth).toBeUndefined();
    expect(changeListener).toBeDefined();

    mockWindow = { width: 1366, height: 1024, scale: 2, fontScale: 2 };
    await act(async () => {
      changeListener?.({ window: mockWindow });
    });

    expect(styleOf(view, 'a11y-evidence-summary').maxWidth).toBe(MAX_READABLE_WIDTH);
    expect(view.getByLabelText(statusLabel)).toBeTruthy();
  });
});
