import { RootNavigator } from './src/app/RootNavigator';

/**
 * Root React component registered by the Expo entry point.
 *
 * The patient application starts with a small, presentational shell: the
 * four-tab navigator mounts synthetic, presentational screens and owns no
 * authentication, network access or push notification code.
 *
 * @returns the patient application's root navigator
 */
export default function App() {
  return <RootNavigator locale="en" />;
}
