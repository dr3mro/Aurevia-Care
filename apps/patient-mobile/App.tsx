import { RootNavigator } from './src/app/RootNavigator';

/**
 * Root React component registered by the Expo entry point.
 *
 * The patient application deliberately starts with a small, presentational
 * shell: there is no authentication, navigation state or network access here.
 * READY-34 replaces the placeholder navigator with the four-tab navigator.
 *
 * @returns the patient application's root navigator
 */
export default function App() {
  return <RootNavigator locale="en" />;
}
