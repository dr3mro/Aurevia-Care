import type { ReactElement } from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { TransportError } from '../../../api/transport';
import { stateViewText } from '../../../components/stateViewMessages';
import { I18nProvider } from '../../../i18n/I18nProvider';
import type { Locale } from '../../../i18n/messages';
import { ThemeProvider } from '../../../theme/ThemeProvider';
import {
  defaultSettings,
  outOfOfficeSettings,
  quietHourInside,
  syntheticDelegate,
  unreadCountSeven,
} from './fixtures';
import { settingsText } from './labels';
import { SettingsPage } from './SettingsPage';
import type { SettingsPageProps } from './SettingsPage';
import { UnreadBadge } from './UnreadBadge';

/** Narrow an optional DOM lookup without a non-null assertion. */
function required<T>(value: T | null | undefined): T {
  if (value === null || value === undefined) {
    throw new Error('Expected a rendered element');
  }
  return value;
}

/** Mount any component under the providers its theme and i18n hooks require. */
function wrap(ui: ReactElement, locale: Locale = 'en') {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>{ui}</I18nProvider>
    </ThemeProvider>,
  );
}

/** Render the page with a synthetic, resolved save callback. */
function renderPage(overrides: Partial<SettingsPageProps> = {}, locale: Locale = 'en') {
  const onSave = vi.fn<SettingsPageProps['onSave']>().mockResolvedValue(undefined);
  const props: SettingsPageProps = { onSave, ...overrides };
  const view = wrap(<SettingsPage {...props} />, locale);
  return { onSave, ...view };
}

/** A checkbox looked up by its visible, translated label. */
function checkbox(key: Parameters<typeof settingsText>[1], locale: Locale = 'en') {
  return screen.getByRole<HTMLInputElement>('checkbox', { name: settingsText(locale, key) });
}

/** A text input or textarea looked up by its visible, translated label. */
function textbox(key: Parameters<typeof settingsText>[1], locale: Locale = 'en') {
  return screen.getByRole<HTMLTextAreaElement>('textbox', { name: settingsText(locale, key) });
}

/** An hour field looked up by its visible, translated label. */
function spinbutton(key: Parameters<typeof settingsText>[1], locale: Locale = 'en') {
  return screen.getByRole<HTMLInputElement>('spinbutton', { name: settingsText(locale, key) });
}

/** The form's native submit control, which Tab reaches and Enter activates. */
function saveButton(locale: Locale = 'en') {
  return screen.getByRole<HTMLButtonElement>('button', { name: settingsText(locale, 'save') });
}

/** Submit the form exactly as a browser does when its primary action is activated. */
function submitSettings(locale: Locale = 'en') {
  fireEvent.submit(required(saveButton(locale).closest('form')));
}

describe('SettingsPage save behavior', () => {
  it('saves the typed settings with sounds still off by default', async () => {
    const { onSave } = renderPage();
    expect(checkbox('soundLabel').checked).toBe(false);
    expect(screen.queryByTestId('settings-status')).toBeNull();
    expect(screen.queryByTestId('settings-working-copy')).toBeNull();

    fireEvent.click(checkbox('muteLabel'));
    fireEvent.change(textbox('replyLabel'), { target: { value: 'Synthetic reply' } });
    fireEvent.change(textbox('delegateLabel'), { target: { value: syntheticDelegate } });
    fireEvent.click(checkbox('outOfOfficeLabel'));
    submitSettings();

    expect(await screen.findByText(settingsText('en', 'saved'))).toBeTruthy();
    expect(onSave).toHaveBeenCalledExactlyOnceWith(
      expect.objectContaining({
        muted: true,
        soundEnabled: false,
        outOfOfficeEnabled: true,
        outOfOfficeReply: 'Synthetic reply',
        delegateSubject: syntheticDelegate,
      }),
    );
  });

  it('lets a user explicitly opt in to sounds', async () => {
    const { onSave } = renderPage();
    fireEvent.click(checkbox('soundLabel'));
    submitSettings();
    expect(await screen.findByText(settingsText('en', 'saved'))).toBeTruthy();
    expect(onSave).toHaveBeenCalledExactlyOnceWith(
      expect.objectContaining({ soundEnabled: true }),
    );
  });

  it('blocks a save and keeps typed text when out of office has no delegate', async () => {
    const { onSave } = renderPage();
    fireEvent.click(checkbox('outOfOfficeLabel'));
    fireEvent.change(textbox('replyLabel'), { target: { value: 'Keep this reply' } });
    submitSettings();

    expect(await screen.findByRole('alert').then((alert) => alert.textContent)).toContain(
      settingsText('en', 'delegateRequired'),
    );
    expect(onSave).not.toHaveBeenCalled();
    expect(textbox('replyLabel').value).toBe('Keep this reply');
    expect(textbox('delegateLabel').getAttribute('aria-invalid')).toBe('true');
    expect(textbox('delegateLabel').getAttribute('aria-describedby')).toBe(
      'settings-delegate-error',
    );

    fireEvent.change(textbox('delegateLabel'), { target: { value: syntheticDelegate } });
    expect(screen.queryByRole('alert')).toBeNull();
    submitSettings();
    expect(await screen.findByText(settingsText('en', 'saved'))).toBeTruthy();
    expect(onSave).toHaveBeenCalledTimes(1);
  });

  it('announces progress while the injected save promise is pending', async () => {
    let finish: (() => void) | undefined;
    const pending = new Promise<void>((resolve) => {
      finish = resolve;
    });
    const onSave = vi.fn<SettingsPageProps['onSave']>().mockReturnValue(pending);
    renderPage({ onSave });

    submitSettings();
    expect(screen.getByTestId('settings-status').textContent).toContain(
      settingsText('en', 'saving'),
    );
    required(finish)();
    expect(await screen.findByText(settingsText('en', 'saved'))).toBeTruthy();
  });
});

describe('SettingsPage conflict and error recovery', () => {
  it('keeps typed work on a conflict and wires compare, copy and reload', async () => {
    const conflict = new TransportError('conflict', {
      correlationId: 'corr-synthetic-conflict',
      code: undefined,
      retryAfterSeconds: undefined,
    });
    const onSave = vi.fn<SettingsPageProps['onSave']>().mockRejectedValue(conflict);
    renderPage({ onSave });

    fireEvent.change(textbox('replyLabel'), { target: { value: 'Typed reply survives' } });
    submitSettings();

    expect(await screen.findByRole('alert').then((alert) => alert.textContent)).toContain(
      stateViewText('en', 'conflict'),
    );
    expect(textbox('replyLabel').value).toBe('Typed reply survives');

    fireEvent.click(screen.getByRole('button', { name: stateViewText('en', 'compare') }));
    expect(screen.getByTestId('settings-compare').textContent).toContain('Typed reply survives');
    expect(screen.getByTestId('settings-compare').textContent).toContain(
      settingsText('en', 'compareTitle'),
    );

    fireEvent.click(screen.getByRole('button', { name: stateViewText('en', 'copyMyChanges') }));
    expect(screen.getByTestId('settings-working-copy').textContent).toContain(
      'Typed reply survives',
    );
    expect(textbox('replyLabel').value).toBe('Typed reply survives');

    fireEvent.click(screen.getByRole('button', { name: stateViewText('en', 'reload') }));
    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.queryByTestId('settings-compare')).toBeNull();
    // The frozen working copy survives the reload; the live fields do not.
    expect(screen.getByTestId('settings-working-copy').textContent).toContain(
      'Typed reply survives',
    );
    expect(textbox('replyLabel').value).toBe(defaultSettings.outOfOfficeReply);
  });

  it('clears the working copy once the corrected edit saves', async () => {
    const onSave = vi
      .fn<SettingsPageProps['onSave']>()
      .mockRejectedValueOnce(
        new TransportError('conflict', {
          correlationId: undefined,
          code: undefined,
          retryAfterSeconds: undefined,
        }),
      )
      .mockResolvedValue(undefined);
    renderPage({ onSave });

    fireEvent.change(textbox('replyLabel'), { target: { value: 'Copy then save' } });
    submitSettings();
    expect(await screen.findByRole('alert')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: stateViewText('en', 'copyMyChanges') }));
    expect(screen.getByTestId('settings-working-copy').textContent).toContain('Copy then save');

    fireEvent.click(screen.getByRole('button', { name: stateViewText('en', 'reload') }));
    submitSettings();
    expect(await screen.findByText(settingsText('en', 'saved'))).toBeTruthy();
    expect(screen.queryByTestId('settings-working-copy')).toBeNull();
  });

  it('initialises from injected settings and reloads them after a conflict', async () => {
    const onSave = vi.fn<SettingsPageProps['onSave']>().mockRejectedValue(
      new TransportError('conflict', {
        correlationId: undefined,
        code: undefined,
        retryAfterSeconds: undefined,
      }),
    );
    renderPage({ onSave, initialSettings: outOfOfficeSettings });
    expect(checkbox('outOfOfficeLabel').checked).toBe(true);
    expect(textbox('replyLabel').value).toBe(outOfOfficeSettings.outOfOfficeReply);

    fireEvent.change(textbox('replyLabel'), { target: { value: 'Local conflict reply' } });
    submitSettings();
    expect(await screen.findByRole('alert')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: stateViewText('en', 'reload') }));
    expect(textbox('replyLabel').value).toBe(outOfOfficeSettings.outOfOfficeReply);
  });

  it('shows the correlation ID for a non-conflict failure and retries the command', async () => {
    const serverError = new TransportError('server', {
      correlationId: 'corr-synthetic-server',
      code: undefined,
      retryAfterSeconds: undefined,
    });
    const onSave = vi
      .fn<SettingsPageProps['onSave']>()
      .mockRejectedValueOnce(serverError)
      .mockResolvedValue(undefined);
    renderPage({ onSave });

    submitSettings();
    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain(stateViewText('en', 'error'));
    expect(alert.textContent).toContain('corr-synthetic-server');

    fireEvent.click(screen.getByRole('button', { name: stateViewText('en', 'retry') }));
    expect(await screen.findByText(settingsText('en', 'saved'))).toBeTruthy();
    expect(onSave).toHaveBeenCalledTimes(2);
  });

  it('normalizes an untyped failure without echoing its message', async () => {
    const onSave = vi.fn<SettingsPageProps['onSave']>().mockRejectedValue(new Error('secret payload'));
    renderPage({ onSave });
    submitSettings();

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain(stateViewText('en', 'errorDetail'));
    expect(alert.textContent).toContain(stateViewText('en', 'correlationUnavailable'));
    expect(alert.textContent).not.toContain('secret payload');
  });
});

describe('SettingsPage presentation and accessibility', () => {
  it('edits both quiet-hour fields and saves the changed window', async () => {
    const { onSave } = renderPage({ currentHour: quietHourInside });
    expect(screen.getByTestId('settings-quiet-status').textContent).toContain(
      settingsText('en', 'quietActive'),
    );

    fireEvent.change(spinbutton('quietStart'), { target: { value: '10' } });
    fireEvent.change(spinbutton('quietEnd'), { target: { value: '12' } });
    expect(spinbutton('quietStart').value).toBe('10');
    expect(spinbutton('quietEnd').value).toBe('12');
    expect(screen.getByTestId('settings-quiet-status').textContent).toContain(
      settingsText('en', 'quietInactive'),
    );

    submitSettings();
    expect(await screen.findByText(settingsText('en', 'saved'))).toBeTruthy();
    expect(onSave).toHaveBeenCalledExactlyOnceWith(
      expect.objectContaining({ doNotDisturb: { startHour: 10, endHour: 12 } }),
    );
  });

  it('reports quiet hours from the supplied hour and from the injected default', () => {
    const view = renderPage();
    expect(view.getByTestId('settings-quiet-status').textContent).toContain(
      settingsText('en', 'quietInactive'),
    );
    view.unmount();

    renderPage({ currentHour: quietHourInside });
    expect(screen.getByTestId('settings-quiet-status').textContent).toContain(
      settingsText('en', 'quietActive'),
    );
  });

  it('keeps the primary action in native tab order and activates it through the form', async () => {
    const { onSave, getByRole } = renderPage();
    const save = getByRole('button', { name: settingsText('en', 'save') });
    expect(save.tagName).toBe('BUTTON');
    expect(save.getAttribute('type')).toBe('submit');
    expect(save.hasAttribute('tabindex')).toBe(false);
    fireEvent.keyDown(save, { key: 'Tab' });
    expect(onSave).not.toHaveBeenCalled();
    // jsdom does not synthesise the browser's Enter-to-submit default action.
    submitSettings();
    expect(await screen.findByText(settingsText('en', 'saved'))).toBeTruthy();
  });

  it('renders translated fields and an unread badge in Arabic right-to-left layout', () => {
    renderPage({}, 'ar');
    expect(document.documentElement.dir).toBe('rtl');
    expect(document.documentElement.lang).toBe('ar');
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe(settingsText('ar', 'title'));
    expect(saveButton('ar').textContent).toBe(settingsText('ar', 'save'));
    expect(textbox('delegateLabel', 'ar')).toBeTruthy();
    render(
      <ThemeProvider>
        <I18nProvider initialLocale="ar">
          <UnreadBadge count={unreadCountSeven} />
        </I18nProvider>
      </ThemeProvider>,
    );
    expect(screen.getByRole('status').getAttribute('dir')).not.toBe('rtl');
  });
});

describe('SettingsPage required state views', () => {
  it.each([
    { kind: 'forbidden', expected: stateViewText('en', 'forbidden') },
    { kind: 'session-expired', expected: stateViewText('en', 'sessionExpired') },
    { kind: 'offline', expected: stateViewText('en', 'error') },
    { kind: 'timeout', expected: stateViewText('en', 'error') },
  ] as const)('maps a $kind rejection to its own state view', async ({ kind, expected }) => {
    const onSave = vi.fn<SettingsPageProps['onSave']>().mockRejectedValue(
      new TransportError(kind, {
        correlationId: undefined,
        code: undefined,
        retryAfterSeconds: undefined,
      }),
    );
    renderPage({ onSave });
    submitSettings();
    expect(await screen.findByRole('alert').then((alert) => alert.textContent)).toContain(expected);
  });

  it('wires an injected sign-in action into the expired-session state', async () => {
    const onSignIn = vi.fn<() => void>();
    const onSave = vi.fn<SettingsPageProps['onSave']>().mockRejectedValue(
      new TransportError('session-expired', {
        correlationId: undefined,
        code: undefined,
        retryAfterSeconds: undefined,
      }),
    );
    renderPage({ onSave, onSignIn });
    submitSettings();
    expect(await screen.findByRole('alert')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: stateViewText('en', 'signIn') }));
    expect(onSignIn).toHaveBeenCalledTimes(1);
  });

  it('asks before leaving with unsaved edits and stops asking after a save', async () => {
    renderPage();
    const untouched = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(untouched);
    expect(untouched.defaultPrevented).toBe(false);

    fireEvent.change(textbox('replyLabel'), { target: { value: 'Unsaved reply' } });
    const dirty = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(dirty);
    expect(dirty.defaultPrevented).toBe(true);

    submitSettings();
    expect(await screen.findByText(settingsText('en', 'saved'))).toBeTruthy();
    // The passive effect that removes the listener runs after the status paints.
    await waitFor(() => {
      const saved = new Event('beforeunload', { cancelable: true });
      window.dispatchEvent(saved);
      expect(saved.defaultPrevented).toBe(false);
    });
  });

  it('stops asking before leaving after a conflict reload discards local edits', async () => {
    const onSave = vi.fn<SettingsPageProps['onSave']>().mockRejectedValue(
      new TransportError('conflict', {
        correlationId: undefined,
        code: undefined,
        retryAfterSeconds: undefined,
      }),
    );
    renderPage({ onSave });
    fireEvent.change(textbox('replyLabel'), { target: { value: 'Local edit' } });
    submitSettings();
    expect(await screen.findByRole('alert')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: stateViewText('en', 'reload') }));
    await waitFor(() => {
      const afterReload = new Event('beforeunload', { cancelable: true });
      window.dispatchEvent(afterReload);
      expect(afterReload.defaultPrevented).toBe(false);
    });
  });
});
