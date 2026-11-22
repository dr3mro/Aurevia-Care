/**
 * PAT-03 clause 3 clause-evidence tests: the patient-app support channel for
 * identity disputes, accessibility and record access requests.
 *
 * Proof-only change. No production file, contract or migration is touched. The
 * three supported purposes, the refusal rules and the generic submission notice
 * already exist in the enrollment feature; the end-to-end case submission does
 * not, so the complete test for it is carried as a documented `it.skip` GAP.
 * All fixtures are synthetic and no message text is ever echoed into a log.
 */
import { createElement } from 'react';
import { fireEvent, render, screen } from '@testing-library/react-native';

import type { Transport } from '../../api/transport';
import {
  buildSubmissionNotice,
  SUBMISSION_NOTICE,
  SUPPORT_CATEGORIES,
  validateSupportRequest,
  type SupportCategory,
  type SupportDraft,
  type SupportInvalidReason,
} from '../../features/enrollment/model';
import {
  SupportRequestScreen,
  type SupportStrings,
} from '../../features/enrollment/screens';

/** Minimal typed spy that records calls without depending on a test-runner global. */
type Spy<Args extends unknown[]> = ((...args: Args) => void) & { calls: Args[] };

function spy<Args extends unknown[]>(): Spy<Args> {
  const calls: Args[] = [];
  const fn = ((...args: Args): void => {
    calls.push(args);
  }) as Spy<Args>;
  fn.calls = calls;
  return fn;
}

/** Synthetic labels matching the installed support screen's English strings. */
const supportStrings: SupportStrings = {
  title: 'Contact support',
  messageLabel: 'Your request',
  submit: 'Send request',
  category: 'Reason',
  invalidCategory: 'Choose a reason',
  empty: 'Write a short request',
  tooLong: 'Request is too long',
  phi: 'Remove phone numbers or email addresses',
};

describe('PAT-03 clause 3 — patient support channel', () => {
  it('offersAllThreeSupportPurposesAndAcceptsAValidGenericRequest', async () => {
    expect(SUPPORT_CATEGORIES).toEqual(['identity_dispute', 'accessibility', 'record_access']);

    const validDraft: SupportDraft = {
      category: 'record_access',
      message: 'Please help me access my record',
    };
    expect(validateSupportRequest(validDraft)).toEqual({ ok: true });
    expect(buildSubmissionNotice()).toBe(SUBMISSION_NOTICE);

    await render(
      createElement(SupportRequestScreen, {
        category: validDraft.category,
        message: validDraft.message,
        noticeVisible: false,
        onCategoryChange: spy<[SupportCategory]>(),
        onMessageChange: spy<[string]>(),
        onSubmit: spy<[]>(),
        strings: supportStrings,
        isRTL: false,
      }),
    );
    for (const category of SUPPORT_CATEGORIES) {
      expect(screen.getByRole('radio', { name: category })).toBeTruthy();
    }
    expect(screen.getByRole('button', { name: supportStrings.submit }).props.accessibilityState).toEqual({
      disabled: false,
    });
  });

  it('refusesRequestsWithNoCategoryEmptyOverlongOrPhiMessage', () => {
    const refusals: readonly (readonly [SupportDraft, SupportInvalidReason])[] = [
      [{ category: null, message: 'Please help me access my record' }, 'category'],
      [{ category: 'accessibility', message: '   ' }, 'empty'],
      [{ category: 'identity_dispute', message: 'x'.repeat(501) }, 'too_long'],
      [{ category: 'record_access', message: 'call me at 1234567' }, 'phi'],
      [{ category: 'accessibility', message: 'email patient@example.org' }, 'phi'],
    ];
    for (const [draft, reason] of refusals) {
      expect(validateSupportRequest(draft)).toEqual({ ok: false, reason });
    }
  });

  it.skip('GAP: installed Account support screen is a placeholder and no patient-app client posts a case to POST /api/v1/patient/access/cases', async () => {
    // The installed Account -> support route (app/RootNavigator.tsx:206-214)
    // passes placeholderAction for onCategoryChange, onMessageChange and
    // onSubmit. It neither retains the draft nor posts a case. This test is the
    // complete end-to-end proof for the channel: a real route must retain the
    // selected purpose and message, then send exactly one case through a client.
    const post = spy<[string, unknown]>();
    const transport: Transport = {
      get: async () => [] as never,
      post: async (path: string, body?: unknown) => {
        post(path, body);
        return undefined as never;
      },
      put: async () => undefined as never,
    };

    let category: SupportCategory | null = null;
    let message = '';
    let noticeVisible = false;
    const submitCase = (): void => {
      const draft: SupportDraft = { category, message };
      const validation = validateSupportRequest(draft);
      if (!validation.ok) {
        return;
      }
      void transport.post('/api/v1/patient/access/cases', {
        category: draft.category,
        message: draft.message,
      });
      noticeVisible = true;
    };

    // A wired route must hold the draft, re-render after each edit and pass real
    // callbacks; the installed route passes placeholderAction and keeps no state.
    const screenProps = () => ({
      category,
      message,
      noticeVisible,
      onCategoryChange: (next: SupportCategory) => {
        category = next;
      },
      onMessageChange: (next: string) => {
        message = next;
      },
      onSubmit: submitCase,
      strings: supportStrings,
      isRTL: false,
    });
    const view = await render(createElement(SupportRequestScreen, screenProps()));

    fireEvent.press(screen.getByRole('radio', { name: 'record_access' }));
    await view.rerender(createElement(SupportRequestScreen, screenProps()));
    fireEvent.changeText(screen.getByLabelText(supportStrings.messageLabel), 'Please help me access my record');
    await view.rerender(createElement(SupportRequestScreen, screenProps()));
    fireEvent.press(screen.getByRole('button', { name: supportStrings.submit }));
    await view.rerender(createElement(SupportRequestScreen, screenProps()));

    expect(category).toBe('record_access');
    expect(post.calls).toEqual([
      [
        '/api/v1/patient/access/cases',
        { category: 'record_access', message: 'Please help me access my record' },
      ],
    ]);
    expect(screen.getByText(SUBMISSION_NOTICE)).toBeTruthy();

    // A phone-number-like message must keep submit disabled. Even if a stale
    // press reaches the handler, validation must refuse it and post nothing.
    fireEvent.changeText(screen.getByLabelText(supportStrings.messageLabel), 'call me at 1234567');
    await view.rerender(createElement(SupportRequestScreen, screenProps()));
    expect(screen.getByRole('button', { name: supportStrings.submit }).props.accessibilityState).toEqual({
      disabled: true,
    });
    fireEvent.press(screen.getByRole('button', { name: supportStrings.submit }));
    expect(post.calls).toHaveLength(1);
  });
});
