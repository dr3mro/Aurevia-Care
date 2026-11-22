/**
 * PAT-01, PAT-02, UX-01: tablet, landscape and 200%-text layout of the shared
 * patient resource boundary (decision 0005). The responsive container is
 * presentational only: it must cap and centre content on large windows, switch
 * a list-plus-detail pair to two panes, honour safe-area insets with logical
 * padding, mirror under right-to-left, and leave every accessibility label and
 * the focus order of the state views untouched. No clinical data is cached.
 *
 * All data below is synthetic. Window values are injected through React
 * Native's window dimensions and the app's safe-area inset boundary, never
 * through a device-type guess.
 */
import { jest } from '@jest/globals';
import { act, render, waitFor } from '@testing-library/react-native';
import { Dimensions, I18nManager, Text, View } from 'react-native';

import { PatientResource } from './PatientResource';
import type { WindowClass } from './layout/useWindowClass';
import { screenMessages } from './screenStates';

/** Insets with distinct sides so a mirrored mapping is observable. */
const INSETS = { top: 12, right: 24, bottom: 36, left: 48 };

type WindowSize = { width: number; height: number; scale: number; fontScale: number };

interface WindowCase {
  readonly windowClass: WindowClass;
  readonly fontScale: number;
  readonly isLandscape: boolean;
}

let mockWindow: WindowSize = { width: 390, height: 900, scale: 2, fontScale: 1 };
let mockInsets = INSETS;

jest.mock('react-native-safe-area-context', () => ({
  useSafeAreaInsets: () => mockInsets,
}));

/** Point the current window at one class, orientation and system font scale. */
function setWindow(testCase: WindowCase): void {
  const width =
    testCase.windowClass === 'compact' ? 500 : testCase.windowClass === 'regular' ? 700 : 1024;
  mockWindow = {
    width,
    height: testCase.isLandscape ? 360 : 900,
    scale: 2,
    fontScale: testCase.fontScale,
  };
}

/** Read the flattened style of the responsive container under test. */
function layoutStyle(view: Awaited<ReturnType<typeof render>>): Record<string, unknown> {
  const style = view.getByTestId('patient-resource-layout').props.style;
  expect(typeof style).toBe('object');
  expect(Array.isArray(style)).toBe(false);
  return style as Record<string, unknown>;
}

/** Render a resource that resolves to synthetic rows and wait for the ready state. */
async function renderReady(testCase: WindowCase) {
  setWindow(testCase);
  const view = await render(
    <PatientResource
      load={async () => ['SYN-ROW-1', 'SYN-ROW-2']}
      isEmpty={(rows: readonly string[]) => rows.length === 0}
      emptyMessage="Nothing released"
      messages={screenMessages('en')}
      onSessionExpired={() => undefined}>
      {(rows) => <Text>{rows.join(',')}</Text>}
    </PatientResource>,
  );
  await waitFor(() => expect(view.getByText('SYN-ROW-1,SYN-ROW-2')).toBeTruthy());
  return view;
}

const WINDOW_CLASSES: readonly WindowClass[] = ['compact', 'regular', 'large'];
const FONT_SCALES: readonly number[] = [1, 1.5, 2];

describe('PatientResource responsive container (PAT-01, PAT-02, UX-01)', () => {
  beforeEach(() => {
    jest.replaceProperty(I18nManager, 'isRTL', false);
    mockInsets = INSETS;
    setWindow({ windowClass: 'compact', fontScale: 1, isLandscape: false });
    jest.spyOn(Dimensions, 'get').mockImplementation(() => mockWindow as never);
  });

  afterEach(() => {
    jest.restoreAllMocks();
  });

  it.each(
    WINDOW_CLASSES.flatMap((windowClass) =>
      FONT_SCALES.flatMap((fontScale) => [
        { windowClass, fontScale, isLandscape: false },
        { windowClass, fontScale, isLandscape: true },
      ]),
    ),
  )(
    'lays out $windowClass at font scale $fontScale (landscape=$isLandscape) with capped, centred content and safe-area insets',
    async (testCase) => {
      const view = await renderReady(testCase);
      const style = layoutStyle(view);
      const isLarge = testCase.windowClass === 'large';

      expect(style.maxWidth).toBe(isLarge ? 1200 : undefined);
      expect(style.alignSelf).toBe(isLarge ? 'center' : 'stretch');
      expect(style.flexDirection).toBe(isLarge ? 'row' : 'column');
      expect(style.justifyContent).toBe(isLarge ? 'center' : 'flex-start');
      expect(style.width).toBe('100%');
      expect(style.minHeight).toBe(44);

      expect(style.paddingTop).toBe(INSETS.top);
      expect(style.paddingBottom).toBe(INSETS.bottom);
      expect(style.paddingStart).toBe(INSETS.left);
      expect(style.paddingEnd).toBe(INSETS.right);
      expect(style.paddingLeft).toBeUndefined();
      expect(style.paddingRight).toBeUndefined();

      // No fixed height, cap or line count that could clip text at 200%.
      expect(style.height).toBeUndefined();
      expect(style.maxHeight).toBeUndefined();
      expect(view.getByText('SYN-ROW-1,SYN-ROW-2').props.numberOfLines).toBeUndefined();
    },
  );

  it('shows a list and a detail as two panes on a large window and stacks them when compact', async () => {
    const children = (): React.JSX.Element => (
      <>
        <View testID="synthetic-list-pane" />
        <View testID="synthetic-detail-pane" />
      </>
    );
    setWindow({ windowClass: 'large', fontScale: 2, isLandscape: true });
    const large = await render(
      <PatientResource
        load={async () => ['SYN-ROW']}
        isEmpty={(rows: readonly string[]) => rows.length === 0}
        emptyMessage="Nothing released"
        messages={screenMessages('en')}
        onSessionExpired={() => undefined}>
        {children}
      </PatientResource>,
    );
    await waitFor(() => expect(large.getByTestId('synthetic-detail-pane')).toBeTruthy());
    expect(layoutStyle(large).flexDirection).toBe('row');

    await act(async () => {
      large.unmount();
    });
    setWindow({ windowClass: 'compact', fontScale: 1, isLandscape: false });
    const compact = await render(
      <PatientResource
        load={async () => ['SYN-ROW']}
        isEmpty={(rows: readonly string[]) => rows.length === 0}
        emptyMessage="Nothing released"
        messages={screenMessages('en')}
        onSessionExpired={() => undefined}>
        {children}
      </PatientResource>,
    );
    await waitFor(() => expect(compact.getByTestId('synthetic-detail-pane')).toBeTruthy());
    expect(layoutStyle(compact).flexDirection).toBe('column');
  });

  it('mirrors the safe-area insets with logical padding under right-to-left', async () => {
    jest.replaceProperty(I18nManager, 'isRTL', true);
    const view = await renderReady({ windowClass: 'large', fontScale: 2, isLandscape: false });
    const style = layoutStyle(view);

    expect(style.paddingStart).toBe(INSETS.right);
    expect(style.paddingEnd).toBe(INSETS.left);
    expect(style.paddingLeft).toBeUndefined();
    expect(style.paddingRight).toBeUndefined();
    expect(style.flexDirection).toBe('row');
  });

  it.each(
    WINDOW_CLASSES.flatMap((windowClass) =>
      FONT_SCALES.map((fontScale) => ({ windowClass, fontScale, isLandscape: fontScale === 1.5 })),
    ),
  )(
    'keeps state-view accessibility roles, labels and target size at $windowClass / $fontScale',
    async (testCase) => {
      setWindow(testCase);
      const view = await render(
        <PatientResource
          load={async () => {
            throw new Error('synthetic offline');
          }}
          isEmpty={(rows: readonly string[]) => rows.length === 0}
          emptyMessage="Nothing released"
          messages={screenMessages('en')}
          onSessionExpired={() => undefined}>
          {(rows) => <Text>{rows.join(',')}</Text>}
        </PatientResource>,
      );
      await waitFor(() => expect(view.getByTestId('screen-offline')).toBeTruthy());
      expect(view.getByTestId('screen-offline').props.accessibilityRole).toBe('alert');
      const retry = view.getByTestId('screen-retry');
      expect(retry.props.accessibilityRole).toBe('button');
      expect(retry.props.accessibilityLabel).toBe(screenMessages('en').retry);
      expect(retry.props.style.minHeight).toBe(44);
    },
  );
});
