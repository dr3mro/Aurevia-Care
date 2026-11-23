import { useState } from 'react';
import type { KeyboardEvent } from 'react';
import { MixedScriptText } from '../../../components/MixedScriptText';
import { annotatedName } from '../../../i18n/format';
import { useI18n } from '../../../i18n/I18nProvider';
import { useTheme } from '../../../theme/ThemeProvider';
import type { Contact, MessagingActor, MessagingPolicy, RecipientStatus } from './domain';
import { recipientRefusal, recipientStatus, searchContacts } from './domain';
import { directoryText, refusalKeyForCode } from './labels';

/** Props for the recipient picker shown in the staff contact directory. */
export interface RecipientPickerProps {
  contacts: readonly Contact[];
  actor: MessagingActor;
  policy: MessagingPolicy;
  selectedSubject: string | undefined;
  onSelect: (subject: string) => void;
}

/** One rendered result together with the decision that controls its row. */
interface DirectoryOption {
  contact: Contact;
  status: RecipientStatus;
  code: string | undefined;
  otherFacility: boolean;
}

/**
 * Search synthetic contacts and pick a message recipient. The combobox input
 * owns focus and the active-descendant contract; the listbox is a presentation
 * of the filtered options and is never a second tab stop. Facility crossing is
 * always marked, even when policy refuses the recipient, so the author can see
 * why a name looks addressable but cannot be selected.
 */
export function RecipientPicker({
  contacts,
  actor,
  policy,
  selectedSubject,
  onSelect,
}: RecipientPickerProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const [query, setQuery] = useState('');
  const [activeIndex, setActiveIndex] = useState(0);

  const options: DirectoryOption[] = searchContacts(contacts, query, locale).map((contact) => ({
    contact,
    status: recipientStatus(actor, contact, policy),
    ...recipientRefusal(actor, contact, policy),
  }));
  const activeOption = options[activeIndex];
  const activeDescendant =
    activeOption === undefined
      ? undefined
      : `recipient-option-${activeOption.contact.subject}`;

  function handleKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === 'Escape') {
      setQuery('');
      setActiveIndex(0);
      return;
    }
    if (options.length === 0) {
      return;
    }
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      setActiveIndex((current) => (current + 1) % options.length);
      return;
    }
    if (event.key === 'ArrowUp') {
      event.preventDefault();
      // Arrow keys follow the visual row order in both directions; RTL mirrors
      // the layout, not the meaning of Up and Down inside a vertical listbox.
      setActiveIndex((current) => (current <= 0 ? options.length - 1 : current - 1));
      return;
    }
    if (
      event.key === 'Enter' &&
      activeOption !== undefined &&
      activeOption.status !== 'blocked'
    ) {
      event.preventDefault();
      onSelect(activeOption.contact.subject);
    }
  }

  return (
    <section
      aria-label={directoryText(locale, 'title')}
      style={{ display: 'grid', gap: tokens.spacing.md }}
    >
      <div style={{ display: 'grid', gap: tokens.spacing.sm }}>
        <label htmlFor="staff-directory-search">{directoryText(locale, 'search')}</label>
        <input
          id="staff-directory-search"
          className="field touch"
          role="combobox"
          type="text"
          autoComplete="off"
          value={query}
          placeholder={directoryText(locale, 'searchHint')}
          aria-expanded={options.length > 0}
          aria-controls={options.length > 0 ? 'staff-directory-results' : undefined}
          aria-autocomplete="list"
          aria-activedescendant={activeDescendant}
          onChange={(event) => {
            setQuery(event.target.value);
            setActiveIndex(0);
          }}
          onKeyDown={handleKeyDown}
          style={{
            paddingBlock: tokens.spacing.sm,
            paddingInline: tokens.spacing.sm,
            border: `1px solid ${tokens.border}`,
            background: tokens.background,
            color: tokens.foreground,
          }}
        />
      </div>
      <p>{directoryText(locale, 'keyboardHint')}</p>
      {options.length === 0 ? (
        <p role="status">{directoryText(locale, 'empty')}</p>
      ) : (
        <div
          id="staff-directory-results"
          role="listbox"
          aria-label={directoryText(locale, 'title')}
          style={{ display: 'grid', gap: tokens.spacing.sm }}
        >
          {options.map((option) => {
            const name = annotatedName(option.contact.displayName, locale);
            const blocked = option.status === 'blocked';
            return (
              <button
                key={option.contact.subject}
                id={`recipient-option-${option.contact.subject}`}
                className="touch"
                type="button"
                role="option"
                tabIndex={-1}
                aria-selected={selectedSubject === option.contact.subject}
                aria-disabled={blocked}
                onClick={blocked ? undefined : () => onSelect(option.contact.subject)}
                style={{
                  display: 'grid',
                  gap: tokens.spacing.sm,
                  paddingBlock: tokens.spacing.sm,
                  paddingInline: tokens.spacing.md,
                  border: `1px solid ${tokens.border}`,
                  cursor: blocked ? 'not-allowed' : 'pointer',
                }}
              >
                <MixedScriptText text={name.text} lang={name.lang} />
                <span>
                  {directoryText(locale, 'facilityLabel')}:{' '}
                  <code>
                    <bdi dir="ltr">{option.contact.facilityCode}</bdi>
                  </code>
                </span>
                {option.otherFacility ? (
                  <span>{directoryText(locale, 'otherFacility')}</span>
                ) : null}
                {blocked ? (
                  <span role="alert">
                    {directoryText(locale, refusalKeyForCode(option.code))}
                  </span>
                ) : null}
              </button>
            );
          })}
        </div>
      )}
    </section>
  );
}
