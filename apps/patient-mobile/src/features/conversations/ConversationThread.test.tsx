import { jest } from '@jest/globals';

import { act, fireEvent, render } from '@testing-library/react-native';

import { ConversationThread } from './ConversationThread';
import type { Conversation, Message } from './domain';

const message: Message = {
  id: 'm1',
  conversationId: 'conv-1',
  senderId: 'unit-a',
  senderRole: 'staff',
  body: 'Please bring your results.',
  sentAt: '2026-11-19T10:00:00Z',
  sequence: 1,
};

function conversation(overrides: Partial<Conversation> = {}): Conversation {
  return {
    id: 'conv-1',
    organizationId: 'org-1',
    unitId: 'lab',
    unitName: 'Synthetic Lab',
    topic: 'follow up',
    state: 'awaiting_staff',
    messagingPermitted: true,
    messages: [message],
    lastReadSequence: 0,
    ...overrides,
  };
}

describe('ConversationThread', () => {
  it('renders messages and sends a typed reply on a writable thread', async () => {
    const onSend = jest.fn();
    const view = await render(<ConversationThread conversation={conversation()} onSend={onSend} />);

    expect(view.getByLabelText('conversation thread')).toBeTruthy();
    expect(view.getByLabelText('unit Synthetic Lab')).toBeTruthy();
    expect(view.getByLabelText('state awaiting_staff')).toBeTruthy();
    expect(view.getByLabelText('unit said: Please bring your results.')).toBeTruthy();

    const input = view.getByLabelText('reply input');
    await act(async () => {
      fireEvent.changeText(input, 'one more question');
    });
    await act(async () => {
      fireEvent.press(view.getByLabelText('send reply'));
    });
    expect(onSend).toHaveBeenCalledTimes(1);
    expect(onSend).toHaveBeenCalledWith('conv-1', 'one more question');
    await view.unmount();
  });

  it('exposes the message body to screen readers', async () => {
    const view = await render(<ConversationThread conversation={conversation()} onSend={jest.fn()} />);
    expect(view.getByLabelText('unit said: Please bring your results.')).toBeTruthy();
    await view.unmount();
  });

  it('does not send a blank or whitespace-only reply', async () => {
    const onSend = jest.fn();
    const view = await render(<ConversationThread conversation={conversation()} onSend={onSend} />);
    const input = view.getByLabelText('reply input');
    await act(async () => {
      fireEvent.changeText(input, '   ');
    });
    await act(async () => {
      fireEvent.press(view.getByLabelText('send reply'));
    });
    expect(onSend).not.toHaveBeenCalled();
    await view.unmount();
  });

  it('renders a closed thread as read-only with no send control', async () => {
    const view = await render(
      <ConversationThread conversation={conversation({ state: 'closed' })} onSend={jest.fn()} />,
    );
    expect(view.getByLabelText('read only')).toBeTruthy();
    expect(view.queryByLabelText('send reply')).toBeNull();
    expect(view.queryByLabelText('reply input')).toBeNull();
    await view.unmount();
  });

  it('renders a revoked connection as read-only', async () => {
    const view = await render(
      <ConversationThread conversation={conversation({ messagingPermitted: false })} onSend={jest.fn()} />,
    );
    expect(view.getByLabelText('read only')).toBeTruthy();
    expect(view.queryByLabelText('send reply')).toBeNull();
    await view.unmount();
  });
});

describe('ConversationThread approved design (BUILD-71, MSG-06, UX-01)', () => {
  const designed = (): Conversation => conversation({
    serviceNotice: { serviceHours: 'Mon-Fri 08:00-17:00', responseTarget: 'within 1 business day' },
    messages: [
      message,
      { ...message, id: 'm2', senderId: 'patient-1', senderRole: 'patient', body: 'Can I change my visit?', sentAt: '2026-11-20T08:14:00Z', sequence: 2 },
    ],
    instruction: {
      id: 'inst-1',
      title: 'Bring your insurance card.',
      revision: 2,
      acknowledgedRevision: null,
    },
  });

  it('shows the service notice, sender bubbles and a revision-bound acknowledgment', async () => {
    const onAcknowledge = jest.fn<(id: string, revision: number) => void>();
    const view = await render(
      <ConversationThread conversation={designed()} onSend={jest.fn()} onAcknowledge={onAcknowledge} />,
    );
    expect(view.getByLabelText('Service hours: Mon-Fri 08:00-17:00')).toBeTruthy();
    expect(view.getByLabelText('Reply target: within 1 business day')).toBeTruthy();
    expect(view.getByLabelText('patient said: Can I change my visit?')).toBeTruthy();
    expect(view.getByTestId('message-m2').props.style).toEqual(
      expect.arrayContaining([expect.objectContaining({ alignSelf: 'flex-start' })]),
    );
    expect(view.getByLabelText('instruction-revision')).toHaveTextContent('Revision 2');
    const ack = view.getByLabelText('Acknowledge receipt of instruction revision 2');
    await act(async () => {
      fireEvent.press(ack);
    });
    expect(onAcknowledge).toHaveBeenCalledWith('conv-1', 2);
    expect(view.getByLabelText('to')).toHaveTextContent('To: Synthetic Lab');
    expect(view.getByLabelText('unsent-note')).toBeTruthy();
    await view.unmount();
  });

  it('hides the acknowledgment once its revision has been acknowledged', async () => {
    const view = await render(
      <ConversationThread
        conversation={conversation({
          instruction: { id: 'inst-1', title: 'Bring your card.', revision: 2, acknowledgedRevision: 2 },
        })}
        onSend={jest.fn()}
        onAcknowledge={jest.fn()}
      />,
    );
    expect(view.getByLabelText('instruction-revision')).toHaveTextContent('Revision 2');
    expect(view.queryByLabelText('Acknowledge receipt of instruction revision 2')).toBeNull();
    await view.unmount();
  });

  it('re-enables acknowledgment for a newer revision and without a callback renders no button', async () => {
    const older = await render(
      <ConversationThread
        conversation={conversation({
          instruction: { id: 'inst-1', title: 'Bring your card.', revision: 3, acknowledgedRevision: 2 },
        })}
        onSend={jest.fn()}
        onAcknowledge={jest.fn()}
      />,
    );
    expect(older.getByLabelText('Acknowledge receipt of instruction revision 3')).toBeTruthy();
    await older.unmount();

    const noCallback = await render(
      <ConversationThread
        conversation={conversation({
          instruction: { id: 'inst-1', title: 'Bring your card.', revision: 2, acknowledgedRevision: null },
        })}
        onSend={jest.fn()}
      />,
    );
    expect(noCallback.queryByLabelText('Acknowledge receipt of instruction revision 2')).toBeNull();
    await noCallback.unmount();
  });

  it('renders Arabic RTL copy, mirrored tabs and إرسال as the send label', async () => {
    const tabs = jest.fn<(tab: string) => void>();
    const view = await render(
      <ConversationThread
        conversation={designed()}
        onSend={jest.fn()}
        locale="ar"
        direction="rtl"
        showTabBar
        activeTab="messages"
        onTab={tabs}
      />,
    );
    expect(view.getByTestId('conversation-thread-title')).toHaveTextContent('الرسائل');
    expect(view.getByTestId('conversation-thread').props.style).toEqual(
      expect.arrayContaining([expect.objectContaining({ direction: 'rtl' })]),
    );
    expect(view.getByText('إرسال')).toBeTruthy();
    expect(view.getByLabelText(/ساعات العمل/)).toBeTruthy();
    expect(view.getByLabelText('الرسائل tab')).toBeTruthy();
    await act(async () => {
      fireEvent.press(view.getByTestId('tab-button-account'));
    });
    expect(tabs).toHaveBeenCalledWith('account');
    await view.unmount();
  });
});
