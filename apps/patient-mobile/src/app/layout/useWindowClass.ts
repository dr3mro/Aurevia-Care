/**
 * Window-size classification for the patient app's responsive layout
 * (PAT-01, PAT-02, UX-01). Layout follows the current window, not the device,
 * so rotation, split-screen and Slide Over all use the same rule.
 */
import { useWindowDimensions } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

/** Layout class derived from the current window width. */
export type WindowClass = 'compact' | 'regular' | 'large';

/** Responsive window values consumed by presentation components. */
export interface WindowLayout {
  /** Breakpoint class for the current window width. */
  windowClass: WindowClass;
  /** Current window width in density-independent pixels. */
  width: number;
  /** Current window height in density-independent pixels. */
  height: number;
  /** True when the window is wider than it is tall. */
  isLandscape: boolean;
  /** System font scale, where 1 is 100% and 2 is 200% text. */
  fontScale: number;
  /** Safe-area insets from the app's existing safe-area library. */
  insets: ReturnType<typeof useSafeAreaInsets>;
}

/**
 * Classify a window width into the responsive layout class.
 *
 * The breakpoints are 600 dp (compact to regular) and 840 dp (regular to
 * large); a boundary width belongs to the larger class.
 *
 * @param width current window width in density-independent pixels
 * @returns `compact` below 600, `regular` below 840, otherwise `large`
 */
export function windowClassFor(width: number): WindowClass {
  if (width < 600) {
    return 'compact';
  }
  if (width < 840) {
    return 'regular';
  }
  return 'large';
}

/**
 * Read the current window's responsive values. React Native's window hook
 * re-renders the caller on a dimension change (rotation, split-screen or
 * Slide Over), so the class and orientation are recomputed from the new width.
 *
 * @returns the layout class, raw dimensions, orientation, font scale and
 * safe-area insets for the current window
 */
export function useWindowClass(): WindowLayout {
  const { width, height, fontScale } = useWindowDimensions();
  const insets = useSafeAreaInsets();

  return {
    windowClass: windowClassFor(width),
    width,
    height,
    isLandscape: width > height,
    fontScale,
    insets,
  };
}
