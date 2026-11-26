/**
 * Public surface of the shared responsive messenger and mail layouts.
 *
 * The unqualified label helpers belong to the shared responsive messaging API.
 * The controlled staff-message wrapper and the grouped message wrapper are
 * exported under distinct names with their own label bundles, so both public
 * surfaces stay importable without ambiguous duplicate exports.
 */

export { MessagingLayout, useMessagingNavigation } from './MessagingLayout';
export type { MessagingLayoutProps, MessagingNavigation, MessagingPane } from './MessagingLayout';
export { layoutMessages, layoutText } from './layoutText';
export type { LayoutMessageKey } from './layoutText';
export { columnsFor, paneVisibility } from './layoutModel';
export type { MessagingStage, PaneId, PaneVisibility } from './layoutModel';

export { StaffMessageLayout } from './StaffMessageLayout';
export type { StaffMessageLayoutProps } from './StaffMessageLayout';
export {
  layoutMessages as staffMessageLayoutMessages,
  layoutText as staffMessageLayoutText,
} from './labels';
export type { LayoutMessageKey as StaffMessageLayoutMessageKey } from './labels';

export { MessageLayout } from './MessageLayout';
export type { MessageLayoutProps } from './MessageLayout';
export {
  layoutKeys,
  layoutMessages as messageLayoutMessages,
  layoutText as messageLayoutText,
} from './layoutMessages';
export type { LayoutMessageKey as MessageLayoutMessageKey } from './layoutMessages';
export { paneLayout } from './layoutStyles';
export type { LayoutShape, PaneLayout, PanePlacement } from './layoutStyles';
