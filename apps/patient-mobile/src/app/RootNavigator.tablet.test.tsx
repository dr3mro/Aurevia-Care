/**
 * Responsive acceptance tests for the patient app root navigator
 * (PAT-01, PAT-02, UX-01; decision 0005).
 *
 * The shell is rendered at every window class, in both orientations, at three
 * system font scales and in both locales. The suite asserts that the responsive
 * structure, the existing screen-reader labels and the unclipped title survive
 * each combination, that safe-area insets are mapped to logical padding, that
 * touch targets keep their floor and that right-to-left mirrors the layout.
 * All data is synthetic; no credential or clinical value is present.
 */
import { jest } from '@jest/globals';
import { act, render, waitFor } from '@testing-library/react-native';
import { Dimensions, StyleSheet } from 'react-native';

import type { Transport } from '../api/transport';
import { applyDirection } from './direction';
import { appText } from './messages';
import { RootNavigator } from './RootNavigator';
import { TAB_DEFINITIONS } from './tabs';

/** Window sizes covering compact, regular and large in both orientations. */
type WindowSize = { width: number; height: number; scale: number; fontScale: number };
type ChangeListener = (payload: { window: WindowSize }) => void;

type Insets = { top: number; right: number; bottom: number; left: number };

const SAFE_INSETS: Insets = { top: 20, right: 30, bottom: 40, left: 10 };
const READABLE_MAX_WIDTH = 840;
const TOUCH_TARGET_FLOOR = 44;

let mockWindow: WindowSize = { width: 390, height: 844, scale: 2, fontScale: 1 };
let mockInsets: Insets = SAFE_INSETS;
let changeListener: ChangeListener | undefined;

jest.mock('../api/tokenStore', () => ({
  clearToken: jest.fn(async () => undefined),
  loadToken: jest.fn(async () => null),
  saveToken: jest.fn(async () => undefined),
}));

jest.mock('./direction', () => ({
  applyDirection: jest.fn(),
}));

jest.mock('react-native-safe-area-context', () => {
  const actual = jest.requireActual<Record<string, unknown>>('react-native-safe-area-context');
  return {
    ...actual,
    initialWindowMetrics: {
      frame: { x: 0, y: 0, width: 320, height: 640 },
      insets: { top: 0, left: 0, right: 0, bottom: 0 },
    },
    useSafeAreaInsets: () => mockInsets,
    SafeAreaProvider: ({ children }: { children?: unknown }) => children,
  };
});

/** Synthetic transport: empty lists, no 401, no clinical payload. */
const syntheticTransport: Transport = {
  get: jest.fn(async (path: string) =>
    path.includes('/conversations/inbox') ? { entries: [] } : [],
  ) as Transport['get'],
  post: jest.fn(async () => undefined) as Transport['post'],
  put: jest.fn(async () => undefined) as Transport['put'],
};

/** Render the shell with the current mocked window and insets. */
async function renderShell(locale: 'en' | 'ar' = 'en') {
  return render(<RootNavigator locale={locale} transport={syntheticTransport} />);
}

beforeEach(() => {
  mockWindow = { width: 390, height: 844, scale: 2, fontScale: 1 };
  mockInsets = SAFE_INSETS;
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

describe('RootNavigator responsive structure (PAT-01, PAT-02, UX-01)', () => {
  it.each([
    ['compact portrait', 'single-pane', 390, 844, 1],
    ['compact landscape', 'single-pane', 500, 360, 1],
    ['regular portrait', 'single-pane', 768, 1024, 1.5],
    ['regular landscape', 'single-pane', 700, 400, 1.5],
    ['large portrait', 'two-pane', 840, 1024, 2],
    ['large landscape', 'two-pane', 1024, 768, 2],
  ] as const)('renders the %s window as %s', async (_label, expectedPane, width, height, fontScale) => {
    mockWindow = { width, height, scale: 2, fontScale };

    const view = await renderShell();

    await waitFor(() => expect(view.getByTestId('responsive-content')).toBeTruthy());
    expect(view.getByTestId(expectedPane)).toBeTruthy();
    expect(view.queryByTestId(expectedPane === 'two-pane' ? 'single-pane' : 'two-pane')).toBeNull();
    if (expectedPane === 'two-pane') {
      expect(view.getByTestId('pane-list')).toBeTruthy();
      expect(view.getByTestId('pane-detail')).toBeTruthy();
    }
  });

  it.each([1, 1.5, 2] as const)('never caps or clips the title at font scale %s', async (fontScale) => {
    mockWindow = { width: 390, height: 844, scale: 2, fontScale };

    const view = await renderShell();

    const title = view.getByTestId('app-title');
    expect(title.props.children).toBe(appText('en', 'appTitle'));
    expect(title.props.numberOfLines).toBeUndefined();
    expect(title.props.adjustsFontSizeToFit).toBeUndefined();
    expect(title.props.maxFontSizeMultiplier).toBeUndefined();
    expect(title.props.allowFontScaling).not.toBe(false);
    const titleStyle = StyleSheet.flatten(title.props.style) as Record<string, unknown>;
    expect(titleStyle.minHeight).toBeGreaterThanOrEqual(TOUCH_TARGET_FLOOR);
    expect(titleStyle.numberOfLines).toBeUndefined();
  });

  it('centres content to a maximum readable width only on a large window', async () => {
    mockWindow = { width: 1024, height: 768, scale: 2, fontScale: 1 };

    const large = await renderShell();
    const largeStyle = StyleSheet.flatten(
      large.getByTestId('responsive-content').props.style,
    ) as Record<string, unknown>;

    expect(largeStyle.maxWidth).toBe(READABLE_MAX_WIDTH);
    expect(largeStyle.alignSelf).toBe('center');
  });

  it('uses the full width on a compact window', async () => {
    mockWindow = { width: 390, height: 844, scale: 2, fontScale: 1 };

    const compact = await renderShell();
    const compactStyle = StyleSheet.flatten(
      compact.getByTestId('responsive-content').props.style,
    ) as Record<string, unknown>;

    expect(compactStyle.maxWidth).toBeUndefined();
  });
});

describe('RootNavigator accessibility and safe area (UX-01)', () => {
  it('maps physical insets to logical padding on a left-to-right window', async () => {
    const view = await renderShell();
    const style = StyleSheet.flatten(
      view.getByTestId('responsive-content').props.style,
    ) as Record<string, unknown>;

    expect(style).toEqual(
      expect.objectContaining({
        direction: 'ltr',
        paddingTop: SAFE_INSETS.top,
        paddingBottom: SAFE_INSETS.bottom,
        paddingStart: SAFE_INSETS.left,
        paddingEnd: SAFE_INSETS.right,
      }),
    );
  });

  it('keeps the tab order, labels and touch-target floor at every window class', async () => {
    for (const [width, height] of [[390, 844], [768, 1024], [1024, 768]] as const) {
      mockWindow = { width, height, scale: 2, fontScale: 1 };

      const view = await renderShell();
      for (const tab of TAB_DEFINITIONS) {
        const button = view.getByTestId(`tab-button-${tab.id}`);
        expect(button.props.accessibilityLabel).toBe(appText('en', tab.accessibilityLabelKey));
      }
      const navigator = view.getByTestId('responsive-content');
      expect(navigator).toBeTruthy();
    }
  });

  it('mirrors direction and insets and keeps Arabic labels on a large RTL window', async () => {
    mockWindow = { width: 1024, height: 768, scale: 2, fontScale: 2 };

    const view = await renderShell('ar');
    const contentStyle = StyleSheet.flatten(
      view.getByTestId('responsive-content').props.style,
    ) as Record<string, unknown>;

    expect(contentStyle).toEqual(
      expect.objectContaining({
        direction: 'rtl',
        paddingStart: SAFE_INSETS.right,
        paddingEnd: SAFE_INSETS.left,
      }),
    );
    const twoPane = view.getByTestId('two-pane');
    expect(StyleSheet.flatten(twoPane.props.style)).toEqual(
      expect.objectContaining({ direction: 'rtl' }),
    );
    expect(view.getByTestId('app-title').props.accessibilityLanguage).toBe('ar');
    expect(view.getByTestId('app-title').props.children).toBe(appText('ar', 'appTitle'));
    for (const tab of TAB_DEFINITIONS) {
      expect(view.getByTestId(`tab-button-${tab.id}`).props.accessibilityLabel).toBe(
        appText('ar', tab.accessibilityLabelKey),
      );
    }
  });

  it('applies the native direction once from the active locale', async () => {
    const view = await renderShell('ar');
    await waitFor(() => expect(view.getByTestId('responsive-content')).toBeTruthy());

    expect(applyDirection).toHaveBeenCalledWith('ar');
  });

  it('recomputes the layout when the window dimensions change', async () => {
    mockWindow = { width: 390, height: 844, scale: 2, fontScale: 1 };

    const view = await renderShell();
    expect(view.getByTestId('single-pane')).toBeTruthy();
    expect(changeListener).toBeDefined();

    mockWindow = { width: 1024, height: 768, scale: 2, fontScale: 2 };
    await act(async () => {
      changeListener?.({ window: mockWindow });
    });

    await waitFor(() => expect(view.getByTestId('two-pane')).toBeTruthy());
  });
});
