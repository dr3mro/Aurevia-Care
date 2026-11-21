import { navigate } from '../../app/router';
import { SystemNotice } from './SystemNotice';

/** Fixed page for an unregistered route; no data is fetched or displayed. */
export function NotFoundPage() {
  return (
    <SystemNotice
      titleKey="notFoundTitle"
      detailKey="notFoundDetail"
      actionKey="goHome"
      onAction={() => navigate('/')}
    />
  );
}
