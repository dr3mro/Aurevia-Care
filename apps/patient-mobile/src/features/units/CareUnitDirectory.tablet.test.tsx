/**
 * Responsive presentation contract for the patient care-unit directory
 * (PAT-01, PAT-02, UX-01, decision 0005).
 *
 * The directory is a read-only list, so the window-width rule keeps the same
 * single list at every class: it never invents a detail pane and never adds a
 * persistent clinical cache. What changes with the window is presentation —
 * a centred readable column on large windows, wrapping so 200% text reflows
 * instead of clipping, 44x44 touch targets, safe-area padding and RTL mirroring.
 * Accessibility roles, labels and reading order stay identical across all
 * window classes, orientations and font scales.
 *
 * The font-scale matrix guards against props that suppress scaling (a layout
 * proxy): a headless renderer cannot paint 200% text. Real no-clip verification
 * remains a device UX-01 pass. The `useSafeAreaInsets` named-export path below is
 * covered here, while the `default`-export fallback path is covered by the
 * existing `index.test.tsx` through the shared Jest setup mock.
 */
import { jest } from '@jest/globals';
import { render } from '@testing-library/react-native';
import { Dimensions } from 'react-native';

import { CareUnitDirectory } from './CareUnitDirectory';
import type { CareUnit } from './types';

type WindowSize = { width: number; height: number; scale: number; fontScale: number };
type Insets = { top: number; right: number; bottom: number; left: number };

const MIN_TOUCH_TARGET = 44;
const GUTTER = 12;

let mockWindow: WindowSize = { width: 390, height: 844, scale: 2, fontScale: 1 };
let mockInsets: Insets = { top: 16, right: 24, bottom: 32, left: 20 };

jest.mock('react-native-safe-area-context', () => ({
  useSafeAreaInsets: (): Insets => mockInsets,
}));

const UNITS: readonly CareUnit[] = [
  {
    id: '11111111-1111-1111-1111-111111111111',
    organizationId: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
    facilityId: 'facility-1',
    name: 'Synthetic Laboratory',
    topics: ['collection', 'results'],
    active: true,
  },
  {
    id: '22222222-2222-2222-2222-222222222222',
    organizationId: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
    facilityId: 'facility-2',
    name: 'Synthetic Radiology',
    topics: ['imaging'],
    active: true,
  },
];

function setWindow(width: number, height: number, fontScale: number): void {
  mockWindow = { width, height, scale: 2, fontScale };
  jest.spyOn(Dimensions, 'get').mockImplementation(() => mockWindow);
}

/** Rendered view returned by the asynchronous Testing Library render. */
type RenderView = Awaited<ReturnType<typeof render>>;

/** Style of the element identified by a test ID. */
function styleOf(view: RenderView, testID: string): Record<string, unknown> {
  return view.getByTestId(testID).props.style as Record<string, unknown>;
}

/** Minimal shape of a React Native Testing Library JSON node. */
interface JsonNode {
  readonly type?: string;
  readonly props?: Record<string, unknown>;
  readonly children?: readonly JsonNode[] | null;
}

/** Collect every rendered Text node so truncation props can be asserted. */
function textNodes(node: JsonNode | null): JsonNode[] {
  if (node === null) {
    return [];
  }
  const self = node.type === 'Text' ? [node] : [];
  return [...self, ...(node.children ?? []).flatMap(textNodes)];
}

describe.each([
  ['compact', 390, 844],
  ['regular', 700, 1000],
  ['large', 1024, 768],
] as const)('CareUnitDirectory on a %s window', (_windowClass, width, height) => {
  beforeEach(() => {
    mockInsets = { top: 16, right: 24, bottom: 32, left: 20 };
  });

  afterEach(() => {
    jest.restoreAllMocks();
  });

  it.each([1, 1.5, 2])('keeps structure, labels and touch targets at font scale %s', async (fontScale) => {
    setWindow(width, height, fontScale);
    const onConnect = jest.fn<(unit: CareUnit) => void>();
    const view = await render(<CareUnitDirectory units={UNITS} isRTL={false} onConnect={onConnect} />);

    // Same list, same order, same accessible connect actions at every class.
    expect(view.getByTestId('unit-directory')).toBeTruthy();
    for (const unit of UNITS) {
      expect(view.getByTestId(`unit-${unit.id}`)).toBeTruthy();
      expect(view.getByLabelText(`Connect to ${unit.name}`)).toBeTruthy();
    }
    expect(view.getAllByLabelText(/^Connect to /).map((node) => node.props.accessibilityLabel)).toEqual([
      'Connect to Synthetic Laboratory',
      'Connect to Synthetic Radiology',
    ]);

    // Content wraps instead of clipping, and touch targets never drop below 44pt.
    const listStyle = styleOf(view, 'unit-directory');
    expect(listStyle.flexWrap).toBe('wrap');
    for (const unit of UNITS) {
      const buttonStyle = styleOf(view, `connect-${unit.id}`) as { minHeight?: number; minWidth?: number };
      expect(buttonStyle.minHeight).toBeGreaterThanOrEqual(MIN_TOUCH_TARGET);
      expect(buttonStyle.minWidth).toBeGreaterThanOrEqual(MIN_TOUCH_TARGET);
    }

    // At 200% text nothing is truncated, shrunk or font-scaling-capped.
    const texts = textNodes(view.toJSON() as unknown as JsonNode);
    expect(texts.length).toBeGreaterThan(0);
    for (const text of texts) {
      expect(text.props?.numberOfLines).toBeUndefined();
      expect(text.props?.adjustsFontSizeToFit).toBeUndefined();
      expect(text.props?.maxFontSizeMultiplier).toBeUndefined();
      expect(text.props?.allowFontScaling).not.toBe(false);
    }

    // Safe-area insets are honoured at all four edges. Exact values are asserted
    // so a swapped left/right or top/bottom mapping would fail.
    expect(listStyle.paddingTop).toBe(mockInsets.top + GUTTER);
    expect(listStyle.paddingBottom).toBe(mockInsets.bottom + GUTTER);
    expect(listStyle.paddingStart).toBe(mockInsets.left + GUTTER);
    expect(listStyle.paddingEnd).toBe(mockInsets.right + GUTTER);

    // A card never grows wider than its window, so a long identifier cannot
    // force the row wider than a compact viewport at 200% text.
    for (const unit of UNITS) {
      expect(styleOf(view, `unit-${unit.id}`).maxWidth).toBe('100%');
    }

    // Large windows get a centred, bounded reading column; small ones fill space.
    if (width >= 840) {
      expect(listStyle.alignSelf).toBe('center');
      expect(typeof listStyle.maxWidth).toBe('number');
      expect(listStyle.maxWidth as number).toBeGreaterThan(0);
      expect(Number.isFinite(listStyle.maxWidth)).toBe(true);
    } else {
      expect(listStyle.alignSelf).not.toBe('center');
    }
  });

  it('mirrors the list for RTL without changing labels or reading order', async () => {
    setWindow(width, height, 2);
    const view = await render(<CareUnitDirectory units={UNITS} isRTL={true} onConnect={() => undefined} />);

    expect(styleOf(view, 'unit-directory').flexDirection).toBe('row-reverse');
    expect(view.getAllByLabelText(/^Connect to /).map((node) => node.props.accessibilityLabel)).toEqual([
      'Connect to Synthetic Laboratory',
      'Connect to Synthetic Radiology',
    ]);
    // Insets follow the reading direction: the inline start is the right edge in RTL.
    expect(styleOf(view, 'unit-directory').paddingStart).toBe(mockInsets.right + GUTTER);
    expect(styleOf(view, 'unit-directory').paddingEnd).toBe(mockInsets.left + GUTTER);
  });
});

describe('CareUnitDirectory orientation and empty state', () => {
  afterEach(() => {
    jest.restoreAllMocks();
  });

  it('keeps the full list and actions in compact landscape at 200% text', async () => {
    setWindow(667, 375, 2);
    const view = await render(<CareUnitDirectory units={UNITS} isRTL={false} onConnect={() => undefined} />);
    expect(view.getAllByLabelText(/^Connect to /)).toHaveLength(UNITS.length);
    expect(styleOf(view, 'unit-directory').flexWrap).toBe('wrap');
  });

  it.each([390, 700, 1024])('keeps the accessible empty state at width %i', async (width) => {
    setWindow(width, 800, 2);
    const view = await render(<CareUnitDirectory units={[]} isRTL={false} onConnect={() => undefined} />);
    expect(view.getByText('No eligible care units')).toBeTruthy();
  });
});
