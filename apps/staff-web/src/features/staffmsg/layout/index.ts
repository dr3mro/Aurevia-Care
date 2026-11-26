/**
 * Public surface of the shared responsive messenger and mail layouts.
 *
 * MessagingLayout is the canonical shared wrapper. The original controlled
 * staff-message wrapper remains as a compatibility API and uses explicit label
 * aliases so both public surfaces stay importable without duplicate exports.
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
  layoutKeys,
  layoutMessages as staffMessageLayoutMessages,
  layoutText as staffMessageLayoutText,
} from './labels';
export type { LayoutMessageKey as StaffMessageLayoutMessageKey } from './labels';
