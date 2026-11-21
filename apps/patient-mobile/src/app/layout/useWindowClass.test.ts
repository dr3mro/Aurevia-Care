/**
 * PAT-01, PAT-02, UX-01: the window hook is the single responsive
 * classification boundary for the patient app. These tests cover every
 * breakpoint outcome, both orientations, the forwarded font scale and
 * safe-area insets, and a real Dimensions change event between renders.
 */
import { jest } from '@jest/globals';
import { act, renderHook } from '@testing-library/react-native';
import { Dimensions } from 'react-native';

import { useWindowClass, windowClassFor } from './useWindowClass';

type WindowSize = { width: number; height: number; scale: number; fontScale: number };
type ChangeListener = (payload: { window: WindowSize }) => void;

let mockWindow: WindowSize = { width: 599, height: 900, scale: 2, fontScale: 1 };
let mockInsets = { top: 12, right: 24, bottom: 36, left: 48 };
let changeListener: ChangeListener | undefined;

jest.mock('react-native-safe-area-context', () => ({
  useSafeAreaInsets: () => mockInsets,
}));

describe('windowClassFor', () => {
  it.each([
    [599, 'compact'],
    [600, 'regular'],
    [839, 'regular'],
    [840, 'large'],
  ] as const)('classifies width %i as %s', (width, expected) => {
    expect(windowClassFor(width)).toBe(expected);
  });
});

describe('useWindowClass', () => {
  beforeEach(() => {
    mockWindow = { width: 599, height: 900, scale: 2, fontScale: 1 };
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

  it('reports the compact class and portrait orientation for a narrow window', async () => {
    const { result } = await renderHook(() => useWindowClass());

    expect(result.current.windowClass).toBe('compact');
    expect(result.current.width).toBe(599);
    expect(result.current.height).toBe(900);
    expect(result.current.isLandscape).toBe(false);
  });

  it('reports the landscape flag when the window is wider than it is tall', async () => {
    mockWindow = { width: 840, height: 600, scale: 2, fontScale: 1 };

    const { result } = await renderHook(() => useWindowClass());

    expect(result.current.windowClass).toBe('large');
    expect(result.current.isLandscape).toBe(true);
  });

  it('forwards the system font scale and safe-area insets', async () => {
    mockWindow = { width: 600, height: 900, scale: 3, fontScale: 2 };

    const { result } = await renderHook(() => useWindowClass());

    expect(result.current.windowClass).toBe('regular');
    expect(result.current.fontScale).toBe(2);
    expect(result.current.insets).toEqual(mockInsets);
  });

  it('recomputes the layout values when the window dimensions change', async () => {
    const { result } = await renderHook(() => useWindowClass());
    expect(result.current.windowClass).toBe('compact');
    expect(changeListener).toBeDefined();

    mockWindow = { width: 840, height: 600, scale: 2, fontScale: 1.5 };
    await act(async () => {
      changeListener?.({ window: mockWindow });
    });

    expect(result.current.windowClass).toBe('large');
    expect(result.current.fontScale).toBe(1.5);
    expect(result.current.isLandscape).toBe(true);
  });
});
