import { useI18n } from '../../i18n/I18nProvider';
import { labText, stateMessageKey } from './messages';
import type { AccessionState } from './types';

/** Props for the accession lifecycle state badge. */
export interface AccessionStateBadgeProps {
  state: AccessionState;
}

/**
 * Render the state as a word from the message bundle, never as colour alone.
 * The state is the one the API returned; the client never infers a nicer name.
 */
export function AccessionStateBadge({ state }: AccessionStateBadgeProps) {
  const { locale } = useI18n();
  return <span data-testid="accession-state">{labText(locale, stateMessageKey[state])}</span>;
}
