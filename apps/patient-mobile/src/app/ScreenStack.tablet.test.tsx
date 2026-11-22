/**
 * PAT-01, PAT-02, UX-01: responsive acceptance for the shared stack used by
 * the patient app's nested list-and-detail flows (messages and account).
 *
 * The table covers every layout class with every supported system font scale
 * and both orientations, then checks the large-window two-pane structure,
 * unchanged accessibility labels and focus order, safe-area insets, 44 pt
 * touch targets, unclipped text and right-to-left mirroring. Data is synthetic.
 */
import { jest } from '@jest/globals';
import { fireEvent, render, waitFor } from '@testing-library/react-native';
import { Dimensions, I18nManager, StyleSheet, Text, type TextStyle, type ViewStyle } from 'react-native';

import type { WindowClass, WindowLayout } from './layout/useWindowClass';
import { ScreenStack, type StackRoute } from './ScreenStack';

const routes: readonly StackRoute[] = [
  { key: 'root', label: 'Root menu', render: () => <Text testID="root-content">Root</Text> },
  { key: 'first', label: 'First screen', render: () => <Text testID="first-content">First</Text> },
  { key: 'second', label: 'Second screen', render: () => <Text testID="second-content">Second</Text> },
];

/** Synthetic window values fed to React Native's window-dimensions hook. */
let mockWindow: WindowSize;
/** Synthetic safe-area insets fed to the safe-area hook. */
let mockInsets: WindowLayout['insets'];

type WindowSize = { width: number; height: number; scale: number; fontScale: number };

jest.mock('react-native-safe-area-context', () => ({
  useSafeAreaInsets: () => mockInsets,
}));

/** Landscape widths chosen to stay inside the class named in each test row. */
const PORTRAIT_WIDTH: Record<WindowClass, number> = { compact: 390, regular: 720, large: 1024 };
const LANDSCAPE_WIDTH: Record<WindowClass, number> = { compact: 520, regular: 760, large: 1024 };

/** Build one synthetic case for a class, orientation and text scale. */
function caseFor(
  windowClass: WindowClass,
  isLandscape: boolean,
  fontScale: number,
): { window: WindowSize; insets: WindowLayout['insets'] } {
  return {
    window: {
      width: isLandscape ? LANDSCAPE_WIDTH[windowClass] : PORTRAIT_WIDTH[windowClass],
      height: isLandscape ? 360 : 920,
      scale: 2,
      fontScale,
    },
    insets: { top: 24, right: 32, bottom: 40, left: 28 },
  };
}

/** Install one synthetic case before rendering. */
function applyCase(windowClass: WindowClass, isLandscape: boolean, fontScale: number): void {
  const chosen = caseFor(windowClass, isLandscape, fontScale);
  mockWindow = chosen.window;
  mockInsets = chosen.insets;
  jest.spyOn(Dimensions, 'get').mockImplementation(() => mockWindow);
  jest.spyOn(Dimensions, 'addEventListener').mockImplementation(() => ({ remove: () => undefined }));
}

async function renderStack() {
  return await render(
    <ScreenStack
      testID="stack"
      title="Stack title"
      backLabel="Back"
      rootKey="root"
      routes={routes}
    />,
  );
}

/** A rendered node whose style prop can be flattened for layout assertions. */
type StyledNode = { readonly props: { readonly style?: Parameters<typeof StyleSheet.flatten>[0] } };

/** A rendered text node with the scaling props the contract forbids. */
type ScalableTextNode = StyledNode & {
  readonly props: {
    readonly allowFontScaling?: boolean;
    readonly maxFontSizeMultiplier?: number | null;
    readonly numberOfLines?: number;
    readonly adjustsFontSizeToFit?: boolean;
  };
};

/** Flatten a rendered style into a plain object regardless of its array shape. */
function styleOf(node: StyledNode): ViewStyle & TextStyle {
  return (StyleSheet.flatten(node.props.style) ?? {}) as ViewStyle & TextStyle;
}

/** Assert a text node keeps system scaling and is never shrunk or truncated. */
function expectScalable(node: ScalableTextNode): void {
  expect(node.props.allowFontScaling).not.toBe(false);
  const multiplier = node.props.maxFontSizeMultiplier;
  expect(multiplier == null || multiplier >= 2).toBe(true);
  expect(node.props.numberOfLines).toBeUndefined();
  expect(node.props.adjustsFontSizeToFit).not.toBe(true);
  const textStyle = styleOf(node);
  expect(textStyle.lineHeight).toBeUndefined();
  expect(textStyle.height).toBeUndefined();
}

/** Assert the role/label order is the same in every presentation. */
function expectMenuOrder(view: { getAllByRole: (role: 'button') => readonly { props: { accessibilityLabel?: string } }[] }): void {
  expect(view.getAllByRole('button').map(button => button.props.accessibilityLabel)).toEqual([
    'First screen',
    'Second screen',
  ]);
}

describe('ScreenStack responsive layout', () => {
  beforeEach(() => {
    applyCase('compact', false, 1);
  });

  afterEach(() => {
    jest.restoreAllMocks();
  });

  it.each([
    ['compact', false, 1],
    ['compact', true, 1.5],
    ['compact', false, 2],
    ['regular', false, 1],
    ['regular', true, 1.5],
    ['regular', false, 2],
    ['large', false, 1],
    ['large', true, 1.5],
    ['large', false, 2],
    ['large', true, 2],
  ] as const)(
    'renders labels, safe area and touch targets at %s (landscape=%s, fontScale=%s)',
    async (windowClass, isLandscape, fontScale) => {
      applyCase(windowClass, isLandscape, fontScale);
      const view = await renderStack();

      expect(view.getByTestId('stack')).toBeTruthy();
      expect(view.getByRole('header')).toBeTruthy();
      expect(view.getByText('Stack title')).toBeTruthy();
      expect(view.getByTestId('root-content')).toBeTruthy();

      if (windowClass === 'large') {
        expect(view.getByTestId('stack-panes')).toBeTruthy();
        expect(view.queryByTestId('stack-single')).toBeNull();
      } else {
        expect(view.getByTestId('stack-single')).toBeTruthy();
        expect(view.queryByTestId('stack-panes')).toBeNull();
      }

      const first = view.getByTestId('stack-open-first');
      const second = view.getByTestId('stack-open-second');
      expect(first.props.accessibilityRole).toBe('button');
      expect(first.props.accessibilityLabel).toBe('First screen');
      expect(second.props.accessibilityRole).toBe('button');
      expect(second.props.accessibilityLabel).toBe('Second screen');
      expect(view.queryByTestId('stack-back')).toBeNull();
      expectMenuOrder(view);

      const container = styleOf(view.getByTestId('stack'));
      expect(container.paddingTop).toBeGreaterThanOrEqual(mockInsets.top);
      expect(container.paddingBottom).toBeGreaterThanOrEqual(mockInsets.bottom);
      expect(container.paddingStart).toBeGreaterThanOrEqual(mockInsets.left);
      expect(container.paddingEnd).toBeGreaterThanOrEqual(mockInsets.right);
      expect(container.paddingLeft).toBeUndefined();
      expect(container.paddingRight).toBeUndefined();

      for (const control of [first, second]) {
        const controlStyle = styleOf(control);
        expect(controlStyle.minHeight).toBeGreaterThanOrEqual(44);
        expect(controlStyle.minWidth).toBeGreaterThanOrEqual(44);
      }

      expectScalable(view.getByText('Stack title'));
      expectScalable(view.getByText('First screen'));
      expectScalable(view.getByText('Second screen'));
      expectScalable(view.getByText('Root'));
    },
  );

  it.each(['compact', 'regular'] as const)('uses one pane on a %s window', async windowClass => {
    applyCase(windowClass, false, 2);
    const view = await renderStack();

    expect(view.getByTestId('stack-single')).toBeTruthy();
    expect(view.queryByTestId('stack-panes')).toBeNull();
    expectMenuOrder(view);
  });

  it('uses a centred two-pane list/detail layout on large windows', async () => {
    applyCase('large', true, 2);
    const view = await renderStack();

    expect(view.getByTestId('stack-panes')).toBeTruthy();
    expect(view.getByTestId('stack-pane-list')).toBeTruthy();
    expect(view.getByTestId('stack-pane-detail')).toBeTruthy();
    expect(view.queryByTestId('stack-single')).toBeNull();

    // The heading and both panes share the 960 dp reading cap from ScreenStack.
    const container = styleOf(view.getByTestId('stack'));
    expect(container.maxWidth).toBe(960);
    expect(container.alignSelf).toBe('center');
    const panes = styleOf(view.getByTestId('stack-panes'));
    expect(panes.flexDirection).toBe('row');
    expect(panes.width).toBe('100%');
    expectMenuOrder(view);
  });

  it('keeps the list visible and updates the detail pane when pushed on a large window', async () => {
    applyCase('large', false, 1.5);
    const view = await renderStack();

    fireEvent.press(view.getByTestId('stack-open-first'));

    await waitFor(() => expect(view.getByTestId('first-content')).toBeTruthy());
    expect(view.getByTestId('stack-pane-list')).toBeTruthy();
    expect(view.getByTestId('stack-open-second')).toBeTruthy();
    expect(view.queryByTestId('root-content')).toBeNull();

    const back = view.getByTestId('stack-back');
    expect(back.props.accessibilityRole).toBe('button');
    expect(back.props.accessibilityLabel).toBe('Back');
    expect(styleOf(back).minHeight).toBeGreaterThanOrEqual(44);
    expect(styleOf(back).minWidth).toBeGreaterThanOrEqual(44);
    expectScalable(view.getByText('Back'));
    expect(view.getAllByRole('button').map(button => button.props.accessibilityLabel)).toEqual([
      'First screen',
      'Second screen',
      'Back',
    ]);

    fireEvent.press(back);
    await waitFor(() => expect(view.getByTestId('root-content')).toBeTruthy());
    expect(view.queryByTestId('stack-back')).toBeNull();
  });

  it('mirrors insets and direction under right-to-left without reversing the row twice', async () => {
    applyCase('large', false, 2);
    jest.replaceProperty(I18nManager, 'isRTL', true);
    const view = await renderStack();

    const container = styleOf(view.getByTestId('stack'));
    expect(container.direction).toBe('rtl');
    expect(container.paddingStart).toBeGreaterThanOrEqual(mockInsets.right);
    expect(container.paddingEnd).toBeGreaterThanOrEqual(mockInsets.left);

    // 'row' mirrors once under the native RTL layout mode; forcing
    // 'row-reverse' here would flip the panes back to LTR order.
    const panes = styleOf(view.getByTestId('stack-panes'));
    expect(panes.flexDirection).toBe('row');
    expect(view.getByTestId('stack-pane-list')).toBeTruthy();
    expect(view.getByTestId('stack-pane-detail')).toBeTruthy();
    expectMenuOrder(view);
    expect(view.getByRole('header')).toBeTruthy();
  });
});
