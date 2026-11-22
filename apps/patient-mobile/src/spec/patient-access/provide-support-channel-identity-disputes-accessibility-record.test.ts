/**
 * PAT-03 clause 3 clause-evidence tests: the patient-app support channel for
 * identity disputes, accessibility and record access requests.
 *
 * Proof-only change. No production file, contract or migration is touched. The
 * three supported purposes, the refusal rules and the generic submission notice
 * already exist in the enrollment feature. This file also proves the screen's
 * case-submission integration with a synthetic Transport and real stateful
 * callbacks; the installed Account route still passes placeholder callbacks, a
 * gap recorded in the clause document. All fixtures are synthetic and no message
 * text is ever echoed into a log.
 */
import { createElement, useState } from 'react';
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

/**
 * Test-only wired route: owns the draft in React state and submits one case
 * through the injected Transport. This stands in for the installed Account
 * route, which currently passes placeholderAction and keeps no state.
 */
function WiredSupportScreen({ transport }: { transport: Transport }) {
  const [category, setCategory] = useState<SupportCategory | null>(null);
  const [message, setMessage] = useState('');
  const [noticeVisible, setNoticeVisible] = useState(false);
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
    setNoticeVisible(true);
  };
  return createElement(SupportRequestScreen, {
    category,
    message,
    noticeVisible,
    onCategoryChange: setCategory,
    onMessageChange: setMessage,
    onSubmit: submitCase,
    strings: supportStrings,
    isRTL: false,
  });
}

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

  it('submitsOneCaseThroughATransportAndRefusesPhiMessages', async () => {
    // The installed Account -> support route (app/RootNavigator.tsx:206-214)
    // passes placeholderAction for onCategoryChange, onMessageChange and
    // onSubmit, so it neither retains the draft nor posts a case. The wired
    // stand-in below owns real state and a synthetic Transport to prove the
    // screen's side of that path: one correctly shaped case per valid submit,
    // the generic notice, and no case for a privacy-unsafe message.
    const post = spy<[string, unknown]>();
    const transport: Transport = {
      get: async () => [] as never,
      post: async (path: string, body?: unknown) => {
        post(path, body);
        return undefined as never;
      },
      put: async () => undefined as never,
    };
    await render(createElement(WiredSupportScreen, { transport }));

    // State lives in the component, so each awaited fireEvent flushes a real
    // re-render before the next interaction or assertion.
    await fireEvent.press(screen.getByRole('radio', { name: 'record_access' }));
    await fireEvent.changeText(screen.getByLabelText(supportStrings.messageLabel), 'Please help me access my record');
    await fireEvent.press(screen.getByRole('button', { name: supportStrings.submit }));

    expect(post.calls).toEqual([
      [
        '/api/v1/patient/access/cases',
        { category: 'record_access', message: 'Please help me access my record' },
      ],
    ]);
    expect(screen.getByText(SUBMISSION_NOTICE)).toBeTruthy();

    // A phone-number-like message must keep submit disabled. Even if a stale
    // press reaches the handler, validation must refuse it and post nothing.
    await fireEvent.changeText(screen.getByLabelText(supportStrings.messageLabel), 'call me at 1234567');
    expect(screen.getByRole('button', { name: supportStrings.submit }).props.accessibilityState).toEqual({
      disabled: true,
    });
    await fireEvent.press(screen.getByRole('button', { name: supportStrings.submit }));
    expect(post.calls).toHaveLength(1);
  });
});
