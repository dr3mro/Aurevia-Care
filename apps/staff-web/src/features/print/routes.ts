import type { AppRoute } from '../../app/routes';

/**
 * Routes owned by the print feature.
 *
 * Printing has no screens of its own: its three buttons are mounted on the
 * prescription, order and chart screens owned by other features. The module
 * still honours the feature-route contract so the shared glob can discover the
 * feature uniformly, but it deliberately claims no path and adds no screen.
 */
export const routes: AppRoute[] = [];
