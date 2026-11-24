import { afterEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { defined } from '../../../components/invariant';
import { I18nProvider } from '../../../i18n/I18nProvider';
import { ThemeProvider } from '../../../theme/ThemeProvider';
import {
  alphaActor,
  alphaDirectory,
  alphaMessage,
  alphaOverview,
  contentDraft,
  emptyDraft,
  personRecipient,
  stagedFiles,
} from './fixtures';
import { mailText } from './labels';
import { MailScreen } from './MailScreen';
import type { MailConfirmation, MailScreenProps } from './MailScreen';

/** Render the screen under the shared providers at a chosen locale. */
function wrap(overrides: Partial<MailScreenProps> = {}, locale: 'en' | 'ar' = 'en') {
  const props: MailScreenProps = {
    view: alphaOverview,
    directory: alphaDirectory,
    actor: alphaActor,
    crossFacilityEnabled: false,
    selfValue: 'synthetic-staff-self',
    draft: emptyDraft,
    stagedFiles,
    confirmation: undefined,
    onDraftChange: vi.fn(),
    onAddRecipient: vi.fn(),
    onRemoveRecipient: vi.fn(),
    onAttach: vi.fn(),
    onStartCompose: vi.fn(),
    onSend: vi.fn(),
    onConfirm: vi.fn(),
    onCancelConfirm: vi.fn(),
    ...overrides,
  };
  const view = render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <MailScreen {...props} />
      </I18nProvider>
    </ThemeProvider>,
  );
  return { ...view, props };
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('MailScreen folders and messages', () => {
  it('renders every folder with its derived count and marks the current one', () => {
    wrap();
    const inbox = screen.getByRole('button', { name: /Inbox \(1\)/ });
    expect(inbox.getAttribute('aria-current')).toBe('true');
    const sent = screen.getByRole('button', { name: /Sent \(0\)/ });
    expect(sent.getAttribute('aria-current')).toBeNull();
    fireEvent.click(sent);
    expect(screen.getByRole('button', { name: /Sent \(0\)/ }).getAttribute('aria-current')).toBe(
      'true',
    );
  });

  it('shows the empty folder state and the no-selection reading pane', () => {
    wrap();
    fireEvent.click(screen.getByRole('button', { name: /Trash \(0\)/ }));
    expect(screen.getByText(mailText('en', 'emptyFolder'))).toBeTruthy();
    expect(screen.getByText(mailText('en', 'noSelection'))).toBeTruthy();
  });

  it('marks the selected message and renders its reading pane with attachments', () => {
    wrap();
    const row = screen.getByRole('button', { name: /Synthetic appointment change/ });
    expect(row.getAttribute('aria-current')).toBe('true');
    const pane = screen.getByRole('region', { name: mailText('en', 'readingPaneLabel') });
    expect(within(pane).getByText(alphaMessage.body)).toBeTruthy();
    expect(within(pane).getByText('visit-summary.pdf')).toBeTruthy();
    expect(within(pane).getByText('America/New_York')).toBeTruthy();
  });

  it('moves the current marker and reading pane when another message is selected', () => {
    const second = { ...alphaMessage, id: 'synthetic-message-002', subject: 'Second synthetic subject' };
    wrap({ view: { ...alphaOverview, messages: [alphaMessage, second] } });
    const firstRow = screen.getByRole('button', { name: /Synthetic appointment change/ });
    expect(firstRow.getAttribute('aria-current')).toBe('true');
    fireEvent.click(screen.getByRole('button', { name: /Second synthetic subject/ }));
    expect(firstRow.getAttribute('aria-current')).toBeNull();
    expect(screen.getByRole('button', { name: /Second synthetic subject/ }).getAttribute('aria-current')).toBe(
      'true',
    );
    const pane = screen.getByRole('region', { name: mailText('en', 'readingPaneLabel') });
    expect(within(pane).getByText('Second synthetic subject')).toBeTruthy();
  });

  it('uses a single-column layout at the compact breakpoint', () => {
    vi.stubGlobal('matchMedia', () => ({
      matches: false,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    }));
    const { container } = wrap();
    const layout = screen.getByRole('region', { name: mailText('en', 'foldersLabel') }).parentElement;
    expect(defined(layout, 'mail layout').getAttribute('style')).not.toContain('220px');
    expect(defined(container.querySelector('section[data-breakpoint]'), 'mail root').getAttribute('data-breakpoint')).toBe('compact');
  });

  it('starts compose actions for reply, reply all, forward and new mail', () => {
    const { props } = wrap();
    fireEvent.click(screen.getByRole('button', { name: mailText('en', 'reply') }));
    fireEvent.click(screen.getByRole('button', { name: mailText('en', 'replyAll') }));
    fireEvent.click(screen.getByRole('button', { name: mailText('en', 'forward') }));
    fireEvent.click(screen.getByRole('button', { name: mailText('en', 'newMail') }));
    expect(props.onStartCompose).toHaveBeenNthCalledWith(1, 'reply', alphaMessage);
    expect(props.onStartCompose).toHaveBeenNthCalledWith(2, 'reply-all', alphaMessage);
    expect(props.onStartCompose).toHaveBeenNthCalledWith(3, 'forward', alphaMessage);
    expect(props.onStartCompose).toHaveBeenNthCalledWith(4, 'new');
  });
});

describe('MailScreen compose controls', () => {
  it('adds a person to To and a role to Cc without offering blocked recipients', () => {
    const { props } = wrap();
    fireEvent.change(screen.getByLabelText(mailText('en', 'toPickerLabel')), {
      target: { value: personRecipient.value },
    });
    fireEvent.click(screen.getByRole('button', { name: mailText('en', 'addTo') }));
    expect(props.onAddRecipient).toHaveBeenCalledWith('to', defined(alphaDirectory[0], 'person entry'));
    fireEvent.change(screen.getByLabelText(mailText('en', 'ccPickerLabel')), {
      target: { value: 'synthetic-role-triage' },
    });
    fireEvent.click(screen.getByRole('button', { name: mailText('en', 'addCc') }));
    expect(props.onAddRecipient).toHaveBeenCalledWith('cc', defined(alphaDirectory[1], 'role entry'));
    fireEvent.click(screen.getByRole('button', { name: mailText('en', 'addTo') }));
    expect(props.onAddRecipient).toHaveBeenCalledTimes(2);
  });

  it('excludes a wrong-facility role and a wrong-tenant person until crossing is approved', () => {
    const { unmount } = wrap();
    const toOptions = () =>
      within(screen.getByLabelText(mailText('en', 'toPickerLabel'))).getAllByRole('option');
    expect(toOptions().map((option) => option.getAttribute('value'))).not.toContain(
      'synthetic-role-north-triage',
    );
    expect(toOptions().map((option) => option.getAttribute('value'))).not.toContain(
      'synthetic-person-beta',
    );
    unmount();
    wrap({ crossFacilityEnabled: true });
    const northOption = within(screen.getByLabelText(mailText('en', 'toPickerLabel')))
      .getAllByRole('option')
      .find((option) => option.getAttribute('value') === 'synthetic-role-north-triage');
    expect(northOption).toBeDefined();
    expect(defined(northOption, 'north role option').textContent).toContain(
      mailText('en', 'otherFacility'),
    );
  });

  it('removes a recipient from either field by value', () => {
    const { props } = wrap({ draft: contentDraft });
    fireEvent.click(
      screen.getByRole('button', { name: `${mailText('en', 'removeRecipient')}: ${personRecipient.value}` }),
    );
    fireEvent.click(
      screen.getByRole('button', { name: `${mailText('en', 'removeRecipient')}: synthetic-role-triage` }),
    );
    expect(props.onRemoveRecipient).toHaveBeenNthCalledWith(1, 'to', personRecipient.value);
    expect(props.onRemoveRecipient).toHaveBeenNthCalledWith(2, 'cc', 'synthetic-role-triage');
  });

  it('stages an attachment only when a file is chosen', () => {
    const { props } = wrap();
    const attachButton = () =>
      defined(screen.getAllByRole('button', { name: mailText('en', 'attachLabel') })[0], 'attach button');
    attachButton().click();
    expect(props.onAttach).not.toHaveBeenCalled();
    fireEvent.change(screen.getByLabelText(mailText('en', 'attachLabel')), {
      target: { value: defined(stagedFiles[0], 'staged file').documentId },
    });
    fireEvent.click(attachButton());
    expect(props.onAttach).toHaveBeenCalledWith(defined(stagedFiles[0], 'staged file'));
  });

  it('reports subject and body edits and the send action', () => {
    const { props } = wrap();
    fireEvent.change(screen.getByLabelText(mailText('en', 'subjectLabel')), {
      target: { value: 'Synthetic subject' },
    });
    fireEvent.change(screen.getByLabelText(mailText('en', 'bodyLabel')), {
      target: { value: 'Synthetic body' },
    });
    fireEvent.click(screen.getByRole('button', { name: mailText('en', 'send') }));
    expect(props.onDraftChange).toHaveBeenCalledWith({ subject: 'Synthetic subject' });
    expect(props.onDraftChange).toHaveBeenCalledWith({ body: 'Synthetic body' });
    expect(props.onSend).toHaveBeenCalledTimes(1);
  });
});

describe('MailScreen confirmation and localization', () => {
  const confirmation: MailConfirmation = {
    recipients: ['synthetic-staff-nora', 'synthetic-staff-omar'],
    files: ['visit-summary.pdf'],
  };

  it('lists resolved recipients and files, and confirms or cancels', () => {
    const { props } = wrap({ confirmation });
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByTestId('mail-confirmation-recipients').textContent).toContain(
      'synthetic-staff-nora',
    );
    expect(within(dialog).getByTestId('mail-confirmation-files').textContent).toContain(
      'visit-summary.pdf',
    );
    fireEvent.click(within(dialog).getByRole('button', { name: mailText('en', 'confirm') }));
    const cancels = within(dialog).getAllByRole('button', { name: mailText('en', 'cancel') });
    fireEvent.click(defined(cancels[0], 'confirmation cancel'));
    expect(props.onConfirm).toHaveBeenCalledTimes(1);
    expect(props.onCancelConfirm).toHaveBeenCalledTimes(1);
  });

  it('renders no dialog when no confirmation is pending', () => {
    wrap();
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('renders Arabic labels in a right-to-left document', () => {
    wrap({}, 'ar');
    expect(document.documentElement.dir).toBe('rtl');
    expect(screen.getByRole('heading', { name: mailText('ar', 'title') })).toBeTruthy();
    expect(screen.getByRole('button', { name: new RegExp(mailText('ar', 'folderInbox')) })).toBeTruthy();
  });
});
