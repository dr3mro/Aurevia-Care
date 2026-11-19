import { jest } from '@jest/globals';

import { act, fireEvent, render } from '@testing-library/react-native';

import { ConversationInbox } from './ConversationInbox';
import type { Conversation, Message } from './domain';

function message(id: string, sequence: number): Message {
  return {
    id,
    conversationId: 'conv-1',
    senderId: 'unit-a',
    senderRole: 'unit',
    body: `message ${sequence}`,
    sentAt: '2026-11-19T10:00:00Z',
    sequence,
  };
}

function conversation(id: string, unitName: string, unread: boolean): Conversation {
  const messages = unread ? [message('m1', 1), message('m2', 2)] : [message('m1', 1)];
  return {
    id,
    organizationId: 'org-1',
    unitId: 'lab',
    unitName,
    topic: 'follow up',
    state: 'awaiting_staff',
    messagingPermitted: true,
    messages,
    lastReadSequence: unread ? 0 : 2,
  };
}

describe('ConversationInbox', () => {
  it('renders per-unit unread badges and opens the selected conversation', async () => {
    const onOpen = jest.fn();
    const view = await render(
      <ConversationInbox
        conversations={[conversation('conv-1', 'Synthetic Lab', true), conversation('conv-2', 'Synthetic Clinic', false)]}
        onOpen={onOpen}
      />,
    );

    expect(view.getByLabelText('conversation inbox')).toBeTruthy();
    expect(view.getByLabelText('Synthetic Lab: awaiting_staff')).toBeTruthy();
    expect(view.getAllByLabelText('state awaiting_staff')).toHaveLength(2);
    expect(view.getByLabelText('unread 2')).toBeTruthy();
    expect(view.queryByLabelText('unread 0')).toBeNull();

    await act(async () => {
      fireEvent.press(view.getByLabelText('Synthetic Clinic: awaiting_staff'));
    });
    expect(onOpen).toHaveBeenCalledTimes(1);
    expect(onOpen).toHaveBeenCalledWith('conv-2');
    await view.unmount();
  });

  it('renders an empty labelled inbox', async () => {
    const view = await render(<ConversationInbox conversations={[]} onOpen={jest.fn()} />);
    expect(view.getByLabelText('conversation inbox')).toBeTruthy();
    expect(view.queryByLabelText('Synthetic Lab: awaiting_staff')).toBeNull();
    await view.unmount();
  });
});
