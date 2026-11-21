import { navigate } from '../../app/router';
import { SessionExpiredState } from '../../components/StateViews';

/** Fixed page for an expired session; the sign-in action keeps the user's place. */
export function SessionExpiredPage() {
  return <SessionExpiredState onSignIn={() => navigate('/')} />;
}
