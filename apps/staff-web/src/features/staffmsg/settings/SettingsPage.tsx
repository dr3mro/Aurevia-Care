import { useEffect, useState } from 'react';
import { TransportError } from '../../../api/transport';
import { Button } from '../../../components/Button';
import { StateView } from '../../../components/StateViews';
import type { StateViewActions } from '../../../components/StateViews';
import { useI18n } from '../../../i18n/I18nProvider';
import type { Locale } from '../../../i18n/messages';
import { useTheme } from '../../../theme/ThemeProvider';
import {
  defaultNotificationSettings,
  isDoNotDisturbActive,
  validateSettings,
} from './domain';
import type { DoNotDisturbWindow, NotificationSettings, SettingsErrorKey } from './domain';
import { quietCurrentHour } from './fixtures';
import { settingsText } from './labels';

/** Props for the self-contained, synthetic notification settings screen. */
export interface SettingsPageProps {
  /** Injected command. The story owns no route and performs no network call. */
  onSave: (settings: NotificationSettings) => Promise<void>;
  /** Authoritative values used to initialise and to reload the form. */
  initialSettings?: NotificationSettings | undefined;
  /** Injected hour for the quiet-hours preview; defaults to a fixed synthetic hour. */
  currentHour?: number | undefined;
  /** Optional sign-in action for the expired-session state view. */
  onSignIn?: (() => void) | undefined;
}

/** Normalise any thrown value before a state view receives it (WEB-09). */
function toTransportError(failure: unknown): TransportError {
  if (failure instanceof TransportError) {
    return failure;
  }
  return new TransportError('server', {
    correlationId: undefined,
    code: undefined,
    retryAfterSeconds: undefined,
  });
}

/**
 * Ask before leaving while edits are unsaved. The listener is installed only
 * while the form is dirty, so a saved or reloaded form never blocks navigation.
 */
function useUnsavedGuard(dirty: boolean): void {
  useEffect(() => {
    if (!dirty) {
      return undefined;
    }
    const warn = (event: BeforeUnloadEvent): void => {
      event.preventDefault();
      // Legacy browsers still require the returnValue assignment.
      Reflect.set(event, 'returnValue', '');
    };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [dirty]);
}

/** Props for the presentational field group. */
interface SettingsFieldsProps {
  draft: NotificationSettings;
  locale: Locale;
  quietActive: boolean;
  validationError: SettingsErrorKey | undefined;
  onChange: (patch: Partial<NotificationSettings>) => void;
  onQuietChange: (window: DoNotDisturbWindow) => void;
}

/** The labelled, keyboard-reachable controls for every editable preference. */
function SettingsFields({
  draft,
  locale,
  quietActive,
  validationError,
  onChange,
  onQuietChange,
}: SettingsFieldsProps) {
  const { tokens } = useTheme();
  const invalid = validationError !== undefined;
  return (
    <div
      className="stack"
      style={{ display: 'grid', gap: tokens.spacing.md, color: tokens.foreground }}
    >
      <label htmlFor="settings-mute">{settingsText(locale, 'muteLabel')}</label>
      <input
        id="settings-mute"
        type="checkbox"
        checked={draft.muted}
        onChange={(event) => onChange({ muted: event.target.checked })}
      />

      <fieldset style={{ border: `1px solid ${tokens.border}`, display: 'grid', gap: tokens.spacing.sm }}>
        <legend>{settingsText(locale, 'quietHoursTitle')}</legend>
        <label htmlFor="settings-quiet-start">{settingsText(locale, 'quietStart')}</label>
        <input
          id="settings-quiet-start"
          className="field touch"
          type="number"
          min={0}
          max={23}
          value={draft.doNotDisturb.startHour}
          onChange={(event) =>
            onQuietChange({ ...draft.doNotDisturb, startHour: Number(event.target.value) })
          }
        />
        <label htmlFor="settings-quiet-end">{settingsText(locale, 'quietEnd')}</label>
        <input
          id="settings-quiet-end"
          className="field touch"
          type="number"
          min={0}
          max={23}
          value={draft.doNotDisturb.endHour}
          onChange={(event) =>
            onQuietChange({ ...draft.doNotDisturb, endHour: Number(event.target.value) })
          }
        />
        <p data-testid="settings-quiet-status">
          {quietActive ? settingsText(locale, 'quietActive') : settingsText(locale, 'quietInactive')}
        </p>
      </fieldset>

      <label htmlFor="settings-out-of-office">{settingsText(locale, 'outOfOfficeLabel')}</label>
      <input
        id="settings-out-of-office"
        type="checkbox"
        checked={draft.outOfOfficeEnabled}
        onChange={(event) => onChange({ outOfOfficeEnabled: event.target.checked })}
      />
      <label htmlFor="settings-reply">{settingsText(locale, 'replyLabel')}</label>
      <textarea
        id="settings-reply"
        className="field touch"
        value={draft.outOfOfficeReply}
        onChange={(event) => onChange({ outOfOfficeReply: event.target.value })}
      />
      <label htmlFor="settings-delegate">{settingsText(locale, 'delegateLabel')}</label>
      <input
        id="settings-delegate"
        className="field touch"
        type="text"
        autoComplete="off"
        spellCheck={false}
        value={draft.delegateSubject}
        aria-invalid={invalid}
        aria-describedby={invalid ? 'settings-delegate-error' : undefined}
        onChange={(event) => onChange({ delegateSubject: event.target.value })}
      />
      {validationError === undefined ? null : (
        <p id="settings-delegate-error" role="alert">
          {settingsText(locale, validationError)}
        </p>
      )}

      <label htmlFor="settings-sound">{settingsText(locale, 'soundLabel')}</label>
      <input
        id="settings-sound"
        type="checkbox"
        checked={draft.soundEnabled}
        onChange={(event) => onChange({ soundEnabled: event.target.checked })}
      />
    </div>
  );
}

/** Props for the status, recovery actions and stale-version working panels. */
interface SettingsNoticesProps {
  locale: Locale;
  status: string;
  error: TransportError | undefined;
  actions: StateViewActions;
  comparing: boolean;
  copied: NotificationSettings | undefined;
  draft: NotificationSettings;
  serverSettings: NotificationSettings;
}

/** Render status messages and the conflict recovery panels outside the form. */
function SettingsNotices({
  locale,
  status,
  error,
  actions,
  comparing,
  copied,
  draft,
  serverSettings,
}: SettingsNoticesProps) {
  return (
    <>
      {status === '' ? null : (
        <p role="status" data-testid="settings-status">
          {status}
        </p>
      )}
      {error === undefined ? null : StateView.forTransportError(error, actions)}
      {comparing ? (
        <section data-testid="settings-compare" className="stack">
          <h2>{settingsText(locale, 'compareTitle')}</h2>
          <b>{settingsText(locale, 'yourValues')}</b>
          <pre style={{ overflowWrap: 'anywhere', whiteSpace: 'pre-wrap' }}>
            {JSON.stringify(draft)}
          </pre>
          <b>{settingsText(locale, 'storedValues')}</b>
          <pre style={{ overflowWrap: 'anywhere', whiteSpace: 'pre-wrap' }}>
            {JSON.stringify(serverSettings)}
          </pre>
        </section>
      ) : null}
      {copied === undefined ? null : (
        <section data-testid="settings-working-copy" className="stack">
          <h2>{settingsText(locale, 'workingCopy')}</h2>
          <p>{settingsText(locale, 'copyNotice')}</p>
          {/* A frozen snapshot, not the live draft: it must survive a reload. */}
          <pre style={{ overflowWrap: 'anywhere', whiteSpace: 'pre-wrap' }}>
            {JSON.stringify(copied)}
          </pre>
        </section>
      )}
    </>
  );
}

/**
 * Synthetic notification settings screen (MSG-06, WEB-09).
 *
 * Every value lives in component state; nothing is written to browser storage.
 * The save callback is injected because this story owns no HTTP route. A stale
 * save leaves the typed values intact and offers compare, reload and a separate
 * working copy; any other failure maps to the shared typed state view. Logical
 * spacing and token colours keep the form readable in English and Arabic RTL.
 *
 * @param props component props
 * @returns the labelled settings form and its state surfaces
 */
export function SettingsPage({
  onSave,
  initialSettings,
  currentHour,
  onSignIn,
}: SettingsPageProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const serverSettings = initialSettings ?? defaultNotificationSettings();
  const [draft, setDraft] = useState<NotificationSettings>(serverSettings);
  const [dirty, setDirty] = useState(false);
  const [validationError, setValidationError] = useState<SettingsErrorKey | undefined>(undefined);
  const [error, setError] = useState<TransportError | undefined>(undefined);
  const [status, setStatus] = useState('');
  const [comparing, setComparing] = useState(false);
  const [copiedDraft, setCopiedDraft] = useState<NotificationSettings | undefined>(undefined);

  useUnsavedGuard(dirty);

  function updateDraft(next: NotificationSettings): void {
    setDraft(next);
    setDirty(true);
    setStatus('');
    setValidationError(undefined);
  }

  async function handleSave(): Promise<void> {
    const invalid = validateSettings(draft);
    if (invalid !== undefined) {
      setValidationError(invalid);
      return;
    }
    setValidationError(undefined);
    setStatus(settingsText(locale, 'saving'));
    try {
      await onSave(draft);
      setError(undefined);
      setDirty(false);
      setComparing(false);
      setCopiedDraft(undefined);
      setStatus(settingsText(locale, 'saved'));
    } catch (failure: unknown) {
      setError(toTransportError(failure));
      setStatus('');
    }
  }

  function handleReload(): void {
    setDraft(serverSettings);
    setDirty(false);
    setError(undefined);
    setValidationError(undefined);
    setComparing(false);
    setStatus('');
    // The working copy is deliberately kept: the user may want it after reload.
  }

  const actions: StateViewActions = {
    onCompare: () => setComparing(true),
    onReload: handleReload,
    onCopy: () => setCopiedDraft(draft),
    onSignIn,
    onRetry: () => {
      void handleSave();
    },
  };
  const hour = currentHour ?? quietCurrentHour;

  return (
    <main
      className="page"
      style={{ display: 'grid', gap: tokens.spacing.lg, color: tokens.foreground }}
    >
      <h1>{settingsText(locale, 'title')}</h1>
      <p>{settingsText(locale, 'syntheticNotice')}</p>
      <SettingsNotices
        locale={locale}
        status={status}
        error={error}
        actions={actions}
        comparing={comparing}
        copied={copiedDraft}
        draft={draft}
        serverSettings={serverSettings}
      />
      <form
        onSubmit={(event) => {
          event.preventDefault();
          void handleSave();
        }}
      >
        <SettingsFields
          draft={draft}
          locale={locale}
          quietActive={isDoNotDisturbActive(draft.doNotDisturb, hour)}
          validationError={validationError}
          onChange={(patch) => updateDraft({ ...draft, ...patch })}
          onQuietChange={(quietWindow) => updateDraft({ ...draft, doNotDisturb: quietWindow })}
        />
        <Button type="submit">{settingsText(locale, 'save')}</Button>
      </form>
    </main>
  );
}
