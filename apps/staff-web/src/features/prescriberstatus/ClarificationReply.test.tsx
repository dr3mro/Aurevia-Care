import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import type { ReactElement } from 'react';
import type { RequestDescriptor } from '../../api/client';
import { TransportError, send } from '../../api/transport';
import type * as TransportModule from '../../api/transport';
import { stateViewText } from '../../components/stateViewMessages';
import type { Locale } from '../../i18n/messages';
import { I18nProvider } from '../../i18n/I18nProvider';
import { ThemeProvider } from '../../theme/ThemeProvider';
import { answerClarification } from './api';
import {
  prescriberOrganizationId,
  syntheticAnsweredThreadView,
  syntheticOpenThreadView,
  syntheticOverdueThreadView,
  syntheticRevisionOnlyThreadView,
  syntheticTextOnlyThreadView,
} from './fixtures';
import { prescriberText } from './labels';
import { ClarificationReply } from './ClarificationReply';
import type { AnswerDraft, ThreadViewResponse } from './types';

vi.mock('../../api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);
const timeZone = 'UTC';

function descriptorAt(call: number): RequestDescriptor {
  return required(sendMock.mock.calls[call])[0];
}

function wrap(
  thread: ThreadViewResponse,
  locale: Locale = 'en',
  error?: TransportError,
): ReactElement {
  const onSend = (draft: AnswerDraft): void => {
    void answerClarification(
      thread.thread.id,
      thread.thread.version,
      draft,
      prescriberOrganizationId,
    );
  };
  return (
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <ClarificationReply
          thread={thread}
          locale={locale}
          timeZone={timeZone}
          {...(error ? { error } : {})}
          onSend={onSend}
        />
      </I18nProvider>
    </ThemeProvider>
  );
}

beforeEach(() => {
  sendMock.mockReset();
});

describe('ClarificationReply open thread', () => {
  it('shows the pharmacy question and its author, with no answered summary', () => {
    sendMock.mockResolvedValue({});
    render(wrap(syntheticOpenThreadView));

    expect(
      screen.getByRole('heading', { name: prescriberText('en', 'clarificationTitle') }),
    ).toBeTruthy();
    expect(screen.getByText('Which strength should be dispensed?')).toBeTruthy();
    expect(screen.getByText(prescriberText('en', 'authorPharmacy'))).toBeTruthy();
    expect(screen.queryByTestId('amended-revision-link')).toBeNull();
    expect(screen.getByLabelText(prescriberText('en', 'answerLabel'))).toBeTruthy();
    expect(screen.getByLabelText(prescriberText('en', 'revisionLabel'))).toBeTruthy();
  });

  it('shows the overdue state in words when the request is overdue', () => {
    sendMock.mockResolvedValue({});
    render(wrap(syntheticOverdueThreadView));

    expect(screen.getByText(prescriberText('en', 'overdue'))).toBeTruthy();
  });

  it('requires text or an amended revision before sending', () => {
    sendMock.mockResolvedValue({});
    render(wrap(syntheticOpenThreadView));

    fireEvent.click(screen.getByRole('button', { name: prescriberText('en', 'sendAnswer') }));

    expect(screen.getByText(prescriberText('en', 'answerRequired'))).toBeTruthy();
    expect(sendMock).not.toHaveBeenCalled();
  });

  it('sends a text answer with the thread version and a fresh retry key', () => {
    sendMock.mockResolvedValue(syntheticAnsweredThreadView);
    render(wrap(syntheticOpenThreadView));

    fireEvent.change(screen.getByLabelText(prescriberText('en', 'answerLabel')), {
      target: { value: 'Dispense the 500 mg tablet.' },
    });
    fireEvent.click(screen.getByRole('button', { name: prescriberText('en', 'sendAnswer') }));

    expect(descriptorAt(0).url).toBe('/api/v1/pharmacy/clarifications/thread-synthetic-001/answer');
    expect(descriptorAt(0).body).toEqual({
      answerBody: 'Dispense the 500 mg tablet.',
      amendedRevisionId: '',
    });
    expect(descriptorAt(0).headers['If-Match']).toBe('1');
    expect(descriptorAt(0).headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('sends an amended revision on its own when no answer text is typed', () => {
    sendMock.mockResolvedValue(syntheticAnsweredThreadView);
    render(wrap(syntheticOpenThreadView));

    fireEvent.change(screen.getByLabelText(prescriberText('en', 'revisionLabel')), {
      target: { value: 'revision-synthetic-002' },
    });
    fireEvent.click(screen.getByRole('button', { name: prescriberText('en', 'sendAnswer') }));

    expect(descriptorAt(0).body).toEqual({
      answerBody: '',
      amendedRevisionId: 'revision-synthetic-002',
    });
  });

  it('keeps typed work and shows the conflict state on a stale version', () => {
    sendMock.mockResolvedValue({});
    const { rerender } = render(wrap(syntheticOpenThreadView));
    fireEvent.change(screen.getByLabelText(prescriberText('en', 'answerLabel')), {
      target: { value: 'my unsaved answer' },
    });

    rerender(
      wrap(
        syntheticOpenThreadView,
        'en',
        new TransportError('conflict', {
          correlationId: 'corr-synthetic-conflict',
          code: undefined,
          retryAfterSeconds: undefined,
        }),
      ),
    );

    expect(screen.getByText(stateViewText('en', 'conflict'))).toBeTruthy();
    expect(screen.getByLabelText<HTMLInputElement>(prescriberText('en', 'answerLabel')).value).toBe(
      'my unsaved answer',
    );
  });
});

describe('ClarificationReply answered thread', () => {
  it('shows the answer and links the amended revision instead of a form', () => {
    sendMock.mockResolvedValue({});
    render(wrap(syntheticAnsweredThreadView));

    expect(screen.getByText(prescriberText('en', 'answered'))).toBeTruthy();
    expect(screen.getByTestId('answer-body').textContent).toContain('Dispense the 500 mg tablet.');
    expect(screen.getByTestId('amended-revision-link')).toBeTruthy();
    expect(screen.getByTestId('amended-revision-link').getAttribute('href')).toBe(
      '/pharmacy/rx-synthetic-001',
    );
    expect(screen.queryByRole('button', { name: prescriberText('en', 'sendAnswer') })).toBeNull();
  });

  it('omits the revision link when the answer is text only', () => {
    sendMock.mockResolvedValue({});
    render(wrap(syntheticTextOnlyThreadView));

    expect(screen.queryByTestId('amended-revision-link')).toBeNull();
    expect(screen.getByTestId('answer-body').textContent).toContain('Dispense the 500 mg tablet.');
  });

  it('omits the answer run when the entry carries only a revision', () => {
    sendMock.mockResolvedValue({});
    render(wrap(syntheticRevisionOnlyThreadView));

    expect(screen.queryByTestId('answer-body')).toBeNull();
    expect(screen.getByTestId('amended-revision-link')).toBeTruthy();
  });

  it('renders Arabic labels, isolated runs and a right-to-left document', () => {
    sendMock.mockResolvedValue({});
    render(wrap(syntheticAnsweredThreadView, 'ar'));

    expect(document.documentElement.dir).toBe('rtl');
    expect(
      screen.getByRole('heading', { name: prescriberText('ar', 'clarificationTitle') }),
    ).toBeTruthy();
    const body = screen.getByTestId('answer-body');
    expect(body.tagName).toBe('BDI');
    expect(body.getAttribute('dir')).toBe('auto');
    expect(screen.getByTestId('amended-revision-link').textContent).toContain(
      'revision-synthetic-002',
    );
  });
});

function required<T>(value: T | null | undefined): T {
  if (value === null || value === undefined) {
    throw new Error('Expected value to be present');
  }
  return value;
}
