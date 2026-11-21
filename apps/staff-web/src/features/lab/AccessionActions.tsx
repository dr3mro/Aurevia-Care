import { Button } from '../../components/Button';
import { useI18n } from '../../i18n/I18nProvider';
import { actionsForState } from './domain';
import { actionMessageKey, labText } from './messages';
import type { AccessionAction, AccessionState } from './types';

/** Props for the state-scoped action group. */
export interface AccessionActionsProps {
  state: AccessionState;
  onAction: (action: AccessionAction) => void;
}

/**
 * Render exactly the commands the LAB-01 state machine allows for `state`.
 * A forbidden command is never rendered, so the screen cannot offer a
 * transition the server would reject.
 */
export function AccessionActions({ state, onAction }: AccessionActionsProps) {
  const { locale } = useI18n();
  return (
    <div role="group" aria-label={labText(locale, 'actions')}>
      {actionsForState(state).map((action) => (
        <Button key={action} onClick={() => onAction(action)}>
          {labText(locale, actionMessageKey[action])}
        </Button>
      ))}
    </div>
  );
}
