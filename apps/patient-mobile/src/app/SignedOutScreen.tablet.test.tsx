/**
 * Tablet, landscape and large-text layout acceptance for the signed-out
 * destination (PAT-01, PAT-02, UX-01). Layout follows the current window
 * (decision 0005): content is constrained and centred on large windows, safe
 * insets and a 44 pt touch-target floor are honoured, text is never clipped,
 * and the accessibility contract is identical at every class and in both
 * directions. Only synthetic window values are used; the window classifier and
 * the safe-area hook are replaced with deterministic data so every class,
 * scale and orientation is reproducible.
 */
import { jest } from '@jest/globals';
import { fireEvent, render } from '@testing-library/react-native';
import { Dimensions } from 'react-native';

import { appText } from './messages';
import { SignedOutScreen } from './SignedOutScreen';

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

const insets: Insets = { top: 12, right: 24, bottom: 36, left: 48 };

let mockWindowClass: WindowClass = 'compact';
let mockWindow: WindowSize = { width: 390, height: 844, scale: 2, fontScale: 1 };
let mockObservedWidth: number | undefined;

jest.mock('./layout/useWindowClass', () => ({
  windowClassFor: (width: number) => {
    mockObservedWidth = width;
    return mockWindowClass;
  },
}));

jest.mock('react-native-safe-area-context', () => ({
  useSafeAreaInsets: () => insets,
}));

function setWindowClass(windowClass: WindowClass): void {
  mockWindowClass = windowClass;
}

/** Merge a React Native style prop (an object or an array) into one object. */
function styleOf(element: { props: { style?: unknown } }): Record<string, unknown> {
  const style = element.props.style;
  const list = Array.isArray(style) ? style : [style];
  return Object.assign({}, ...list.filter(Boolean));
}

const windowClasses: WindowClass[] = ['compact', 'regular', 'large'];
const fontScales = [1, 1.5, 2];

describe('SignedOutScreen responsive layout', () => {
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
      setWindowClass(windowClass);
      // Drive the real window hook with each matrix input so the class, text
      // scale and orientation genuinely differ between cases.
      mockWindow = {
        width: isLandscape ? 844 : 390,
        height: isLandscape ? 390 : 844,
        scale: 2,
        fontScale,
      };
      const view = await render(<SignedOutScreen locale="en" onSignIn={() => undefined} />);

      expect(mockObservedWidth).toBe(mockWindow.width);
      expect(view.getByTestId('signed-out-screen')).toBeTruthy();
      expect(view.getByTestId('signed-out-content')).toBeTruthy();
      expect(view.getByTestId('signed-out-title')).toBeTruthy();
      expect(view.getByTestId('signed-out-body')).toBeTruthy();
      expect(view.getByTestId('sign-in-action')).toBeTruthy();
      for (const testId of ['signed-out-title', 'signed-out-body']) {
        expect(view.getByTestId(testId).props.numberOfLines).toBeUndefined();
        expect(view.getByTestId(testId).props.maxFontSizeMultiplier).toBeUndefined();
      }
    },
  );

  it('constrains and centres content on a large window', async () => {
    setWindowClass('large');
    const view = await render(<SignedOutScreen locale="en" onSignIn={() => undefined} />);

    const content = styleOf(view.getByTestId('signed-out-content'));
    expect(content.maxWidth).toBe(640);
    expect(content.alignSelf).toBe('center');
  });

  it.each(['compact', 'regular'] as const)(
    'fills the available width without a cap on %s windows',
    async (windowClass) => {
      setWindowClass(windowClass);
      const view = await render(<SignedOutScreen locale="en" onSignIn={() => undefined} />);

      const content = styleOf(view.getByTestId('signed-out-content'));
      expect(content.maxWidth).toBeUndefined();
      expect(content.alignSelf).toBeUndefined();
    },
  );

  it('respects safe-area insets on every edge', async () => {
    const view = await render(<SignedOutScreen locale="en" onSignIn={() => undefined} />);

    const container = styleOf(view.getByTestId('signed-out-screen'));
    expect(container.paddingTop).toBe(insets.top + 16);
    expect(container.paddingBottom).toBe(insets.bottom + 16);
    expect(container.paddingLeft).toBe(insets.left + 16);
    expect(container.paddingRight).toBe(insets.right + 16);
  });

  it('keeps the sign-in touch target at least 44 by 44 points', async () => {
    const view = await render(<SignedOutScreen locale="en" onSignIn={() => undefined} />);

    const control = styleOf(view.getByTestId('sign-in-action'));
    expect(control.minHeight as number).toBeGreaterThanOrEqual(44);
    expect(control.minWidth as number).toBeGreaterThanOrEqual(44);
  });

  it('never clips or caps text scaling at 200 percent', async () => {
    const view = await render(<SignedOutScreen locale="en" onSignIn={() => undefined} />);

    for (const testId of ['signed-out-title', 'signed-out-body']) {
      const text = view.getByTestId(testId);
      expect(text.props.numberOfLines).toBeUndefined();
      expect(text.props.maxFontSizeMultiplier).toBeUndefined();
      expect(text.props.allowFontScaling).not.toBe(false);
    }
  });

  it.each(['en', 'ar'] as const)(
    'preserves accessibility roles and labels in %s at every window class',
    async (locale) => {
      for (const windowClass of windowClasses) {
        setWindowClass(windowClass);
        const view = await render(<SignedOutScreen locale={locale} onSignIn={() => undefined} />);

        expect(view.getByTestId('signed-out-screen').props.accessibilityRole).toBe('alert');
        const title = view.getByTestId('signed-out-title');
        expect(title.props.accessibilityRole).toBe('header');
        expect(title.props.accessibilityLanguage).toBe(locale);
        expect(view.getByTestId('signed-out-body').props.accessibilityLanguage).toBe(locale);
        const control = view.getByTestId('sign-in-action');
        expect(control.props.accessibilityRole).toBe('button');
        expect(control.props.accessibilityLabel).toBe(appText(locale, 'signIn'));
        expect(control.props.accessibilityLanguage).toBe(locale);
        expect(view.getByText(appText(locale, 'signIn')).props.accessibilityLanguage).toBe(locale);
      }
    },
  );

  it('mirrors direction for the right-to-left locale', async () => {
    const rtl = await render(<SignedOutScreen locale="ar" onSignIn={() => undefined} />);
    expect(styleOf(rtl.getByTestId('signed-out-content')).direction).toBe('rtl');

    const ltr = await render(<SignedOutScreen locale="en" onSignIn={() => undefined} />);
    expect(styleOf(ltr.getByTestId('signed-out-content')).direction).toBe('ltr');
  });

  it('still invokes the sign-in callback on large windows', async () => {
    setWindowClass('large');
    const onSignIn = jest.fn();
    const view = await render(<SignedOutScreen locale="en" onSignIn={onSignIn} />);
    fireEvent.press(view.getByTestId('sign-in-action'));
    expect(onSignIn).toHaveBeenCalledTimes(1);
  });
});
