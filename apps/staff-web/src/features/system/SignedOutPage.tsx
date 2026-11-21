import { navigate } from '../../app/router';
import { SystemNotice } from './SystemNotice';

/** Fixed page shown after sign out; it holds no patient data (WEB-03). */
export function SignedOutPage() {
  return (
    <SystemNotice
      titleKey="signedOutTitle"
      detailKey="signedOutDetail"
      actionKey="signIn"
      onAction={() => navigate('/')}
    />
  );
}
