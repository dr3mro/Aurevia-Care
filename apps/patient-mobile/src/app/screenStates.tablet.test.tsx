/**
 * Tablet, landscape and large-text acceptance for the shared patient screen
 * states (PAT-01, PAT-02, UX-01).
 *
 * The matrix renders the component at every window class, font scale and
 * orientation and asserts the responsive container, unchanged accessibility
 * semantics, safe-area insets, 44 pt touch targets and untruncated text. All
 * data is synthetic; no clinical record or credential is used.
 */
import { jest } from '@jest/globals';
import * as fs from 'node:fs';
import * as path from 'node:path';

import { render } from '@testing-library/react-native';
import { Dimensions, Text } from 'react-native';

import { TransportError } from '../api/transport';

import type { ScreenMessages } from './screenStates';
import { ScreenStateView } from './screenStates';
import type { WindowLayout } from './layout/useWindowClass';

const labels: ScreenMessages = {
  loading: 'Loading…',
  error: 'Something went wrong',
  offline: 'You appear to be offline',
  sessionExpired: 'Your session has expired',
  retry: 'Retry',
  signIn: 'Sign in',
};

const windowClasses = ['compact', 'regular', 'large'] as const;
const fontScales = [1, 1.5, 2] as const;
const orientations = [false, true] as const;

type WindowSize = { width: number; height: number; scale: number; fontScale: number };
type Insets = WindowLayout['insets'];

interface Layout {
  readonly windowClass: (typeof windowClasses)[number];
  readonly fontScale: (typeof fontScales)[number];
  readonly isLandscape: boolean;
}

const layouts: readonly Layout[] = windowClasses.flatMap((windowClass) =>
  fontScales.flatMap((fontScale) =>
    orientations.map((isLandscape) => ({ windowClass, fontScale, isLandscape })),
  ),
);

/** Safe-area insets; landscape has a wider side inset to prove both are used. */
function insetsFor(isLandscape: boolean): Insets {
  return {
    top: 20,
    right: isLandscape ? 44 : 16,
    bottom: 24,
    left: isLandscape ? 44 : 16,
  };
}

/** Window sizes that stay inside the requested breakpoint and orientation. */
function sizeFor(layout: Layout): WindowSize {
  const portrait: Record<Layout['windowClass'], [number, number]> = {
    compact: [390, 844],
    regular: [700, 1000],
    large: [1024, 1366],
  };
  const landscape: Record<Layout['windowClass'], [number, number]> = {
    compact: [568, 320],
    regular: [760, 480],
    large: [1194, 834],
  };
  const [width, height] = (layout.isLandscape ? landscape : portrait)[layout.windowClass];
  return { width, height, scale: 2, fontScale: layout.fontScale };
}

let mockWindow: WindowSize = sizeFor({ windowClass: 'compact', fontScale: 1, isLandscape: false });
let mockInsets: Insets = insetsFor(false);

jest.mock('react-native-safe-area-context', () => ({
  useSafeAreaInsets: () => mockInsets,
}));

beforeEach(() => {
  jest.spyOn(Dimensions, 'get').mockImplementation(() => mockWindow);
});

afterEach(() => {
  jest.restoreAllMocks();
});

function setLayout(layout: Layout): void {
  mockWindow = sizeFor(layout);
  mockInsets = insetsFor(layout.isLandscape);
}

function renderInWindow(element: Parameters<typeof render>[0]) {
  return render(element);
}

/** Walk the rendered tree so host props and child order can be inspected. */
interface Node {
  readonly type: string;
  readonly props: Record<string, unknown>;
  readonly children: readonly (Node | string)[] | null;
}

function flatten(node: Node | string | null, into: Node[]): Node[] {
  if (node === null || typeof node === 'string') {
    return into;
  }
  into.push(node);
  for (const child of node.children ?? []) {
    flatten(child, into);
  }
  return into;
}

function nodes(view: { toJSON: () => unknown }): Node[] {
  return flatten(view.toJSON() as Node | null, []);
}

function nodeByTestId(tree: readonly Node[], testID: string): Node {
  const found = tree.find((node) => node.props.testID === testID);
  if (!found) {
    throw new Error(`No rendered node carries testID ${testID}`);
  }
  return found;
}

/** Resolve a React Native style prop that may be an object or an array. */
function flattenedStyle(node: Node): Record<string, unknown> {
  const style = node.props.style;
  const entries = Array.isArray(style) ? style : [style];
  return entries.reduce<Record<string, unknown>>((acc, entry) => {
    if (entry !== null && typeof entry === 'object') {
      return { ...acc, ...(entry as Record<string, unknown>) };
    }
    return acc;
  }, {});
}

const retryState = {
  kind: 'error' as const,
  error: new TransportError('server', { correlationId: 'corr-7' }),
};

function renderError(onRetry: () => void) {
  return renderInWindow(
    <ScreenStateView state={retryState} messages={labels} emptyMessage="Nothing yet" onRetry={onRetry}>
      {() => null}
    </ScreenStateView>,
  );
}

describe('ScreenStateView responsive layout (PAT-01, PAT-02, UX-01)', () => {
  it.each(layouts)(
    'lays out the error state at $windowClass, font scale $fontScale, landscape=$isLandscape',
    async (layout) => {
      setLayout(layout);
      const view = await renderError(() => undefined);
      const tree = nodes(view);
      const insets = insetsFor(layout.isLandscape);

      const error = nodeByTestId(tree, 'screen-error');
      expect(error.props.accessibilityRole).toBe('alert');
      const style = flattenedStyle(error);
      expect(style.paddingTop).toBe(16 + insets.top);
      expect(style.paddingBottom).toBe(16 + insets.bottom);
      expect(style.paddingStart).toBe(16 + Math.max(insets.left, insets.right));
      expect(style.paddingEnd).toBe(16 + Math.max(insets.left, insets.right));
      expect(style.minHeight).toBe(44);

      if (layout.windowClass === 'large') {
        expect(style.maxWidth).toBe(720);
        expect(style.width).toBe('100%');
        expect(style.alignSelf).toBe('center');
      } else {
        expect(style.maxWidth).toBeUndefined();
        expect(style.width).toBeUndefined();
        expect(style.alignSelf).toBeUndefined();
      }

      const retry = nodeByTestId(tree, 'screen-retry');
      expect(retry.props.accessibilityRole).toBe('button');
      expect(retry.props.accessibilityLabel).toBe(labels.retry);
      const controlStyle = flattenedStyle(retry);
      expect(controlStyle.minHeight).toBeGreaterThanOrEqual(44);
      expect(controlStyle.minWidth).toBeGreaterThanOrEqual(44);
    },
  );

  it.each(layouts)(
    'keeps messages untruncated and in the same order at $windowClass/$fontScale/$isLandscape',
    async (layout) => {
      setLayout(layout);
      const view = await renderError(() => undefined);
      const textNodes = nodes(view).filter((node) => node.type === 'Text');

      for (const text of textNodes) {
        expect(text.props.numberOfLines).toBeUndefined();
        expect(text.props.adjustsFontSizeToFit).toBeUndefined();
        expect(text.props.maxFontSizeMultiplier).toBeUndefined();
        expect(text.props.allowFontScaling).not.toBe(false);
      }
      expect(textNodes.map((node) => node.children?.join('') ?? '')).toEqual([
        labels.error,
        'Reference: corr-7',
        labels.retry,
      ]);
    },
  );

  it.each(windowClasses)('gives every state the same container on a %s window', async (windowClass) => {
    setLayout({ windowClass, fontScale: 2, isLandscape: false });
    const cases = [
      {
        testID: 'screen-loading',
        role: 'text',
        state: { kind: 'loading' as const },
        emptyMessage: 'Nothing yet',
      },
      {
        testID: 'screen-empty',
        role: 'text',
        state: { kind: 'empty' as const },
        emptyMessage: 'No appointments',
      },
      {
        testID: 'screen-offline',
        role: 'alert',
        state: { kind: 'offline' as const, error: new TransportError('network', {}) },
        emptyMessage: 'Nothing yet',
      },
      {
        testID: 'screen-session-expired',
        role: 'alert',
        state: { kind: 'session-expired' as const, error: new TransportError('unauthenticated', {}) },
        emptyMessage: 'Nothing yet',
      },
    ];

    for (const item of cases) {
      const view = await renderInWindow(
        <ScreenStateView state={item.state} messages={labels} emptyMessage={item.emptyMessage}>
          {() => null}
        </ScreenStateView>,
      );
      const stateNode = nodeByTestId(nodes(view), item.testID);
      expect(stateNode.props.accessibilityRole).toBe(item.role);
      const style = flattenedStyle(stateNode);
      expect(style.paddingStart).toBe(32);
      expect(style.minHeight).toBe(44);
      expect(style.maxWidth).toBe(windowClass === 'large' ? 720 : undefined);
    }
  });

  it('keeps the polite announcement and the loaded screen structure unchanged', async () => {
    setLayout({ windowClass: 'large', fontScale: 1.5, isLandscape: true });
    const loading = await renderInWindow(
      <ScreenStateView state={{ kind: 'loading' }} messages={labels} emptyMessage="Nothing yet">
        {() => null}
      </ScreenStateView>,
    );
    const loadingNode = nodeByTestId(nodes(loading), 'screen-loading');
    expect(loadingNode.props.accessibilityRole).toBe('text');
    expect(loadingNode.props.accessibilityLiveRegion).toBe('polite');
    expect(flattenedStyle(loadingNode).maxWidth).toBe(720);

    const ready = await renderInWindow(
      <ScreenStateView state={{ kind: 'ready', data: 7 }} messages={labels} emptyMessage="Nothing yet">
        {(data) => <Text>{`count ${data}`}</Text>}
      </ScreenStateView>,
    );
    const tree = nodes(ready);
    expect(tree.some((node) => String(node.props.testID ?? '').startsWith('screen-'))).toBe(false);
    expect(tree.some((node) => node.children?.includes('count 7'))).toBe(true);
  });

  it('mirrors with logical padding and unchanged labels under RTL', async () => {
    setLayout({ windowClass: 'large', fontScale: 2, isLandscape: true });
    const view = await renderError(() => undefined);
    const tree = nodes(view);
    const style = flattenedStyle(nodeByTestId(tree, 'screen-error'));

    expect(style.paddingStart).toBe(60);
    expect(style.paddingEnd).toBe(60);
    expect(style.paddingLeft).toBeUndefined();
    expect(style.paddingRight).toBeUndefined();
    expect(style.left).toBeUndefined();
    expect(style.right).toBeUndefined();
    expect(nodeByTestId(tree, 'screen-retry').props.accessibilityLabel).toBe(labels.retry);

    const source = fs.readFileSync(path.join(__dirname, 'screenStates.tsx'), 'utf8');
    expect(source).not.toMatch(/\b(?:margin|padding)(?:Left|Right)\s*:/);
    expect(source).not.toMatch(/\b(?:left|right)\s*:/);
  });
});
