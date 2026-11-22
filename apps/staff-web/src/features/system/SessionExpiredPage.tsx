import { navigate } from '../../app/router';
import { SessionExpiredState } from '../../components/StateViews';

/**
 * Fixed page for an expired session; the sign-in action keeps the user's place.
 *
 * The page owns no layout styles: the shared session-expired state renders the
 * safe-area-aware `page` grid, the breakpoint-driven action row and logical
 * properties, so one DOM stays readable from 320 px upward in LTR and RTL.
 */
export function SessionExpiredPage() {
  return <SessionExpiredState onSignIn={() => navigate('/')} />;
}
