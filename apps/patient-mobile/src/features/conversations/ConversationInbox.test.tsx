import { jest } from '@jest/globals';

import { act, fireEvent, render } from '@testing-library/react-native';

import { ConversationInbox } from './ConversationInbox';
import type { Conversation, Message } from './domain';

function message(id: string, sequence: number): Message {
  return {
    id,
    conversationId: 'conv-1',
    senderId: 'unit-a',
    senderRole: 'staff',
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
    const unreadRow = view.getByLabelText('Synthetic Lab: awaiting_staff, 2 unread');
    expect(unreadRow.props.accessibilityRole).toBe('button');
    expect(view.getByLabelText('Synthetic Clinic: awaiting_staff')).toBeTruthy();

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

  it('prefers the authoritative server unread count when supplied', async () => {
    const view = await render(
      <ConversationInbox
        conversations={[conversation('conv-1', 'Synthetic Lab', false)]}
        unreadByConversation={{ 'conv-1': 5 }}
        onOpen={jest.fn()}
      />,
    );
    expect(view.getByLabelText('Synthetic Lab: awaiting_staff, 5 unread')).toBeTruthy();
    await view.unmount();
  });

  it('falls back to the local count when the server map has no entry', async () => {
    const view = await render(
      <ConversationInbox
        conversations={[conversation('conv-1', 'Synthetic Lab', true)]}
        unreadByConversation={{}}
        onOpen={jest.fn()}
      />,
    );
    expect(view.getByLabelText('Synthetic Lab: awaiting_staff, 2 unread')).toBeTruthy();
    await view.unmount();
  });

  it('renders the Arabic RTL inbox and mirrored standalone tab bar', async () => {
    const onTab = jest.fn<(tab: string) => void>();
    const view = await render(
      <ConversationInbox
        conversations={[conversation('conv-1', 'Synthetic Lab', true)]}
        onOpen={jest.fn()}
        locale="ar"
        showTabBar
        activeTab="messages"
        onTab={onTab}
      />,
    );
    expect(view.getByTestId('conversation-inbox-title')).toHaveTextContent('الرسائل');
    expect(view.getByTestId('conversation-inbox').props.style).toEqual(
      expect.arrayContaining([expect.objectContaining({ direction: 'rtl' })]),
    );
    expect(view.getByLabelText('الرسائل tab')).toBeTruthy();
    await act(async () => {
      fireEvent.press(view.getByTestId('tab-button-appointments'));
    });
    expect(onTab).toHaveBeenCalledWith('appointments');
    await view.unmount();
  });
});
