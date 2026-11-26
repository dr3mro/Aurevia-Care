/** Public surface of the shared responsive messenger and mail layout. */

export { MessagingLayout, useMessagingNavigation } from './MessagingLayout';
export type { MessagingLayoutProps, MessagingNavigation, MessagingPane } from './MessagingLayout';
export { layoutMessages, layoutText } from './layoutText';
export type { LayoutMessageKey } from './layoutText';
export { columnsFor, paneVisibility } from './layoutModel';
export type { MessagingStage, PaneId, PaneVisibility } from './layoutModel';
