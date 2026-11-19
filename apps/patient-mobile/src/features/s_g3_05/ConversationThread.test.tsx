import { jest } from '@jest/globals';

import { act, fireEvent, render } from '@testing-library/react-native';

import { ConversationThread } from './ConversationThread';
import type { Conversation, Message } from './domain';

const message: Message = {
  id: 'm1',
  conversationId: 'conv-1',
  senderId: 'unit-a',
  senderRole: 'unit',
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
    expect(view.getByLabelText('message 1 from unit')).toBeTruthy();

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
