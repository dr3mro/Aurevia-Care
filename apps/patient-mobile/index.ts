/**
 * Expo entry point for the patient application.
 *
 * Registers the root React component with Expo. READY-33 introduces the minimum
 * runnable shell; READY-34 replaces the placeholder navigator with the four tabs.
 */
import { registerRootComponent } from 'expo';

import App from './App';

registerRootComponent(App);
