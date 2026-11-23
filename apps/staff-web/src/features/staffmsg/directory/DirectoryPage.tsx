import { useState } from 'react';
import { useI18n } from '../../../i18n/I18nProvider';
import { useTheme } from '../../../theme/ThemeProvider';
import { directoryContacts, mainActor, policyClosed } from './fixtures';
import { directoryText } from './labels';
import { RecipientPicker } from './RecipientPicker';

/**
 * Container for the synthetic contact directory. The unapproved cross-facility
 * policy is the safe default: a crossing is still marked but cannot be picked.
 * Routing is owned by a separate story, so this page only renders the feature.
 */
export function DirectoryPage() {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const [selectedSubject, setSelectedSubject] = useState<string | undefined>(undefined);
  return (
    <main style={{ display: 'grid', gap: tokens.spacing.lg }}>
      <h1>{directoryText(locale, 'title')}</h1>
      <p>{directoryText(locale, 'syntheticNotice')}</p>
      <RecipientPicker
        contacts={directoryContacts}
        actor={mainActor}
        policy={policyClosed}
        selectedSubject={selectedSubject}
        onSelect={setSelectedSubject}
      />
      {selectedSubject === undefined ? null : (
        <p role="status">
          {directoryText(locale, 'selected')}:{' '}
          <code>
            <bdi dir="ltr">{selectedSubject}</bdi>
          </code>
        </p>
      )}
    </main>
  );
}
