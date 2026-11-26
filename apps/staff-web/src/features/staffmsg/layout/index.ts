/**
 * Public surface of the shared responsive messenger and mail layouts.
 *
 * The responsive messaging API keeps the unqualified label names. The original
 * message wrapper bundle is aliased so both implementations stay importable
 * without an ambiguous duplicate export.
 */

export { MessagingLayout, useMessagingNavigation } from './MessagingLayout';
export type {
  MessagingLayoutProps,
  MessagingNavigation,
  MessagingPane,
} from './MessagingLayout';
export { layoutMessages, layoutText } from './layoutText';
export type { LayoutMessageKey } from './layoutText';
export { columnsFor, paneVisibility } from './layoutModel';
export type { MessagingStage, PaneId, PaneVisibility } from './layoutModel';

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
