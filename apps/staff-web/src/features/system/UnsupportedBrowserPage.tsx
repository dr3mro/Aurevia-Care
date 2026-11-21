import { SystemNotice } from './SystemNotice';

/**
 * Perform a document navigation so the client-support check is evaluated again
 * on a real load, for example after a viewport resize. The location is
 * injectable so this behavior can be asserted without a real navigation.
 */
export function assignHomeLocation(location: Pick<Location, 'assign'>): void {
  location.assign('/');
}

/**
 * Plain message shown instead of the whole application when the client fails
 * the support check. The entry point supplies the providers; this page renders
 * no clinical data, navigation or shell chrome (WEB-07). Its action performs a
 * document load rather than SPA navigation because no router is mounted in the
 * unsupported tree.
 */
export function UnsupportedBrowserPage() {
  return (
    <SystemNotice
      titleKey="unsupportedTitle"
      detailKey="unsupportedDetail"
      actionKey="goHome"
      onAction={() => assignHomeLocation(window.location)}
    />
  );
}
