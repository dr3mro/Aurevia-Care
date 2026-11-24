/**
 * WEB-09 clause 4 — "Leaving with unsaved changes asks first."
 *
 * Cross-cutting staff-web evidence. The real notification-settings page is
 * rendered in jsdom with synthetic preferences and an injected resolved save
 * callback; a cancelable browser-navigation event proves that the guard asks
 * while edits are unsaved and stops asking once the form is clean or saved.
 * An active gap tripwire records that the requirement says every screen asks
 * while only a handful install a leave guard. The two behavior cases mirror the
 * per-page proof in `src/features/staffmsg/settings/SettingsPage.test.tsx`;
 * this file adds cross-cutting WEB-09 evidence rather than replacing them.
 * No network, database, patient record or credential is involved.
 */
import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createElement } from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { I18nProvider } from '../../../src/i18n/I18nProvider';
import type { Locale } from '../../../src/i18n/messages';
import { ThemeProvider } from '../../../src/theme/ThemeProvider';
import { defaultSettings } from '../../../src/features/staffmsg/settings/fixtures';
import { settingsText } from '../../../src/features/staffmsg/settings/labels';
import { SettingsPage } from '../../../src/features/staffmsg/settings/SettingsPage';
import type { SettingsPageProps } from '../../../src/features/staffmsg/settings/SettingsPage';
import { must } from '../../must';

/** Absolute path to src, anchored to this module so the caller's cwd is irrelevant. */
const srcRoot = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..', 'src');

/** Render the real guard-bearing page with a resolved synthetic save command. */
function renderPage() {
  const onSave = vi.fn<SettingsPageProps['onSave']>().mockResolvedValue(undefined);
  // I18nProvider declares children as a required prop, which createElement's
  // extra arguments alone do not satisfy. Build the props object as a variable,
  // matching the localization spec precedent, so both typecheck and lint pass.
  const initialLocale: Locale = 'en';
  const props = { initialLocale, children: createElement(SettingsPage, { onSave }) };
  const view = render(createElement(ThemeProvider, null, createElement(I18nProvider, props)));
  return { onSave, ...view };
}

/** The automatic-reply textbox whose edit marks the form dirty. */
function replyTextbox(): HTMLTextAreaElement {
  return screen.getByRole<HTMLTextAreaElement>('textbox', {
    name: settingsText('en', 'replyLabel'),
  });
}

/** Submit through the form's native submit path, which the save button triggers. */
function save(): void {
  const button = screen.getByRole<HTMLButtonElement>('button', {
    name: settingsText('en', 'save'),
  });
  fireEvent.submit(must(button.closest('form'), 'settings form'));
}

describe('WEB-09 clause 4 — leaving with unsaved changes asks first', () => {
  it('asks before leaving while edits are unsaved', () => {
    renderPage();
    expect(replyTextbox().value).toBe(defaultSettings.outOfOfficeReply);

    fireEvent.change(replyTextbox(), { target: { value: 'Unsaved synthetic reply' } });
    // Prove the fixture is genuinely dirty before asking the browser to leave.
    expect(replyTextbox().value).toBe('Unsaved synthetic reply');

    const leaving = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(leaving);
    expect(leaving.defaultPrevented).toBe(true);
  });

  it('does not ask before leaving a clean or just-saved form', async () => {
    const { onSave } = renderPage();

    // A pristine form has no listener: the guard's early-return branch.
    const clean = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(clean);
    expect(clean.defaultPrevented).toBe(false);

    // The guard is active once the user types, so the later silence is the
    // listener being removed after a successful save, not a guard that never ran.
    fireEvent.change(replyTextbox(), { target: { value: 'Saved synthetic reply' } });
    const dirty = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(dirty);
    expect(dirty.defaultPrevented).toBe(true);

    save();
    expect(await screen.findByText(settingsText('en', 'saved'))).toBeTruthy();
    expect(onSave).toHaveBeenCalledExactlyOnceWith(
      expect.objectContaining({ outOfOfficeReply: 'Saved synthetic reply' }),
    );
    await waitFor(() => {
      const afterSave = new Event('beforeunload', { cancelable: true });
      window.dispatchEvent(afterSave);
      expect(afterSave.defaultPrevented).toBe(false);
    });
  });

  /**
   * GAP tripwire: WEB-09 says every screen asks before leaving with unsaved
   * work, but only a handful do. This active test pins the exact current set
   * of browser-level guards and the editable forms that install none. It fails
   * the day a new screen gains a guard or a listed form is fixed, at which
   * point this case must be replaced with positive proofs, not kept as a record
   * of absence. It pins literal source tokens; extracting the repeated hook or
   * changing quote style also fails it and requires a deliberate update.
   */
  it('GAP: only some screens ask before leaving with unsaved changes', () => {
    const leaveGuardToken = "addEventListener('beforeunload'";
    const dialogGuardToken = 'discardTitle';

    /** List hand-written Page/Screen sources under src as repo-relative paths. */
    function listScreenSources(directory: string): string[] {
      const sources: string[] = [];
      for (const entry of readdirSync(directory, { withFileTypes: true })) {
        const absolute = join(directory, entry.name);
        if (entry.isDirectory()) {
          sources.push(...listScreenSources(absolute));
        } else if (entry.isFile() && /(Page|Screen)\.tsx$/.test(entry.name)) {
          sources.push(relative(srcRoot, absolute).split(sep).join('/'));
        }
      }
      return sources;
    }

    const guarded = listScreenSources(srcRoot)
      .filter((file) => readFileSync(join(srcRoot, file), 'utf8').includes(leaveGuardToken))
      .sort();
    expect(guarded).toEqual([
      'features/chart/NotePage.tsx',
      'features/pharmacyinbox/PharmacyItemPage.tsx',
      'features/staffmsg/settings/SettingsPage.tsx',
    ]);

    // The inbox guards an unsaved reply with an in-app Stay/Leave dialog instead.
    expect(readFileSync(join(srcRoot, 'features/messages/InboxScreen.tsx'), 'utf8')).toContain(
      dialogGuardToken,
    );

    // Representative editable forms that hold typed work yet ask nothing.
    const unguardedForms = [
      'features/admin/ConfigEditor.tsx',
      'features/labcollection/CollectionForm.tsx',
      'features/labcollection/RejectionForm.tsx',
      'features/patients/RegisterPatientForm.tsx',
      'features/radworklist/ScheduleForm.tsx',
      'features/radworklist/StudyLinkForm.tsx',
      'features/scheduling/BookingForm.tsx',
      'features/scheduling/NoShowReasonForm.tsx',
    ];
    for (const file of unguardedForms) {
      const source = readFileSync(join(srcRoot, file), 'utf8');
      expect(source).not.toContain(leaveGuardToken);
      expect(source).not.toContain(dialogGuardToken);
    }
  });
});
