import type { Locale } from '../../i18n/messages';
import type { WorklistStatus } from './domain';
import { radworklistText, statusMessageKey } from './labels';

/** Props for a status badge that conveys state in words, not colour alone. */
export interface StatusBadgeProps {
  status: WorklistStatus;
  locale: Locale;
}

/** Render one worklist status as an accessible, localized text badge. */
export function StatusBadge({ status, locale }: StatusBadgeProps) {
  return (
    <span data-testid="worklist-status" data-status={status}>
      {radworklistText(locale, statusMessageKey[status])}
    </span>
  );
}
