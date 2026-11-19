import type { ReactNode } from 'react';

/**
 * Jest setup for the patient-mobile foundation. React Navigation's native
 * peers ship no Jest entry point, so `react-native-screens` is replaced with
 * a minimal stub that keeps the navigator on plain React Native views, and
 * `react-native-safe-area-context` uses the mock shipped by the library.
 * These substitutions exist only for headless tests (UX-01).
 */
jest.mock('react-native-screens', () => {
  const passthrough = ({ children }: { children?: ReactNode }): ReactNode => children;
  return {
    screensEnabled: () => false,
    enableFreeze: () => undefined,
    Screen: passthrough,
    ScreenContainer: passthrough,
  };
});

jest.mock('react-native-safe-area-context', () =>
  jest.requireActual('react-native-safe-area-context/jest/mock'),
);
