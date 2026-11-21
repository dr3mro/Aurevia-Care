/**
 * PAT-01, PAT-02, UX-01: tablet, landscape and large-text acceptance for the
 * patient conversation inbox (decision 0005). All fixtures are synthetic.
 *
 * Evidence scope: rendered style and accessibility-tree assertions under
 * jest-expo, including a 200% font-scale window. Painted output and real
 * VoiceOver/TalkBack passes remain a G5 device gate. Safe-area insets are
 * owned by the app shell, so this list pane adds no second padding layer.
 */
import { jest } from '@jest/globals';
import { act, fireEvent, render, within } from '@testing-library/react-native';
import { Dimensions, StyleSheet } from 'react-native';

import { ConversationInbox } from './ConversationInbox';
import type { Conversation } from './domain';

type WindowSize = { width: number; height: number; scale: number; fontScale: number };
type ChangeListener = (payload: { window: WindowSize }) => void;
type StyleObject = Record<string, unknown>;
type TestNode = { readonly props: Record<string, unknown> };

let mockWindow: WindowSize = { width: 390, height: 844, scale: 3, fontScale: 1 };
let changeListener: ChangeListener | undefined;

function setWindow(width: number, height: number, fontScale: number): void {
  mockWindow = { width, height, scale: 3, fontScale };
}

function syntheticConversation(id: string, unitName: string, unread: boolean): Conversation {
  const messages = unread
    ? [
        { id: 'm1', conversationId: id, senderId: 'unit-a', senderRole: 'staff' as const, body: 'first', sentAt: '2026-11-21T10:00:00Z', sequence: 1 },
        { id: 'm2', conversationId: id, senderId: 'unit-a', senderRole: 'staff' as const, body: 'second', sentAt: '2026-11-21T11:00:00Z', sequence: 2 },
      ]
    : [
        { id: 'm1', conversationId: id, senderId: 'unit-a', senderRole: 'staff' as const, body: 'first', sentAt: '2026-11-21T10:00:00Z', sequence: 1 },
      ];
  return {
    id,
    organizationId: 'org-synthetic',
    unitId: 'unit-labc',
    unitName,
    topic: 'follow up',
    state: 'awaiting_staff',
    messagingPermitted: true,
    messages,
    lastReadSequence: unread ? 0 : 2,
  };
}

function flattenedStyle(node: TestNode): StyleObject {
  const style = node.props.style as Parameters<typeof StyleSheet.flatten>[0];
  return (StyleSheet.flatten(style) ?? {}) as StyleObject;
}

function listPaneButtons(view: {
  getByTestId: (id: string) => Parameters<typeof within>[0];
}): TestNode[] {
  return within(view.getByTestId('conversation-inbox-list-pane')).getAllByRole('button') as TestNode[];
}

function buttonLabels(nodes: readonly TestNode[]): string[] {
  return nodes
    .map(node => node.props.accessibilityLabel)
    .filter((label): label is string => typeof label === 'string');
}

describe('ConversationInbox tablet, landscape and large-text layout', () => {
  beforeEach(() => {
    setWindow(390, 844, 1);
    changeListener = undefined;
    jest.spyOn(Dimensions, 'get').mockImplementation(() => mockWindow);
    jest.spyOn(Dimensions, 'addEventListener').mockImplementation((_type, handler) => {
      changeListener = handler as ChangeListener;
      return { remove: () => undefined };
    });
  });

  afterEach(() => {
    jest.restoreAllMocks();
  });

  it.each([
    ['compact portrait', 390, 844, 1],
    ['compact portrait at 200% text', 390, 844, 2],
    ['regular portrait', 700, 1000, 1.5],
    ['regular landscape at 200% text', 800, 600, 2],
    ['large portrait', 900, 1200, 1],
    ['large portrait at 150% text', 900, 1200, 1.5],
    ['large landscape at 200% text', 1024, 768, 2],
  ] as const)(
    'keeps the labelled, unclipped list at %s',
    async (_name, width, height, fontScale) => {
      setWindow(width, height, fontScale);
      const onOpen = jest.fn();
      const view = await render(
        <ConversationInbox
          conversations={[
            syntheticConversation('conv-1', 'Synthetic Lab', true),
            syntheticConversation('conv-2', 'Synthetic Clinic', false),
          ]}
          onOpen={onOpen}
        />,
      );

      // Screen-reader labels and roles are unchanged by the window class.
      expect(view.getByLabelText('conversation inbox')).toBeTruthy();
      const unreadRow = view.getByLabelText('Synthetic Lab: awaiting_staff, 2 unread');
      const readRow = view.getByLabelText('Synthetic Clinic: awaiting_staff');
      expect(unreadRow.props.accessibilityRole).toBe('button');
      expect(readRow.props.accessibilityRole).toBe('button');

      // Touch targets meet the 48 dp platform floor in both axes; no fixed
      // height or truncation can clip text at 200%.
      const rowStyle = flattenedStyle(unreadRow);
      expect(rowStyle.minHeight).toBe(48);
      expect(rowStyle.minWidth).toBe(48);
      expect(rowStyle.height).toBeUndefined();
      expect(rowStyle.justifyContent).toBe('center');
      for (const label of ['Synthetic Lab', 'awaiting_staff', '2']) {
        const text = within(unreadRow).getByText(label);
        const textStyle = flattenedStyle(text);
        expect(text.props.numberOfLines).toBeUndefined();
        expect(text.props.adjustsFontSizeToFit).toBeUndefined();
        expect(text.props.maxFontSizeMultiplier).toBeUndefined();
        expect(text.props.allowFontScaling).not.toBe(false);
        expect(textStyle.height).toBeUndefined();
      }

      await act(async () => {
        fireEvent.press(readRow);
      });
      expect(onOpen).toHaveBeenCalledWith('conv-2');
      await view.unmount();
    },
  );

  it.each([
    [390, 844, false],
    [700, 1000, false],
    [900, 1200, true],
    [1024, 768, true],
  ] as const)('caps and centres the content only on large windows (%i x %i)', async (width, height, isLarge) => {
    setWindow(width, height, 1.5);
    const view = await render(
      <ConversationInbox conversations={[syntheticConversation('conv-1', 'Synthetic Lab', false)]} onOpen={jest.fn()} />,
    );

    const contentStyle = flattenedStyle(view.getByTestId('conversation-inbox-content'));
    const listPaneStyle = flattenedStyle(view.getByTestId('conversation-inbox-list-pane'));
    if (isLarge) {
      expect(contentStyle.maxWidth).toBe(1120);
      expect(contentStyle.alignSelf).toBe('center');
      // The capped list pane leaves the remaining width for the thread pane
      // composed by the host screen (RESP-APP-PATIENT-PAGES).
      expect(listPaneStyle.maxWidth).toBe(560);
    } else {
      expect(contentStyle.maxWidth).toBeUndefined();
      expect(contentStyle.alignSelf).toBeUndefined();
      expect(listPaneStyle.maxWidth).toBeUndefined();
    }
    expect(listPaneStyle.width).toBe('100%');
    await view.unmount();
  });

  it('mirrors with the locale and keeps focus order and labels in Arabic RTL', async () => {
    setWindow(1024, 768, 2);
    const ltrView = await render(
      <ConversationInbox conversations={[syntheticConversation('conv-1', 'Synthetic Lab', true)]} onOpen={jest.fn()} />,
    );
    const ltrLabels = buttonLabels(listPaneButtons(ltrView));
    await ltrView.unmount();

    const onTab = jest.fn();
    const rtlView = await render(
      <ConversationInbox
        conversations={[syntheticConversation('conv-1', 'Synthetic Lab', true)]}
        onOpen={jest.fn()}
        locale="ar"
        showTabBar
        activeTab="messages"
        onTab={onTab}
      />,
    );

    expect(rtlView.getByTestId('conversation-inbox-title')).toHaveTextContent('الرسائل');
    expect(rtlView.getByTestId('conversation-inbox').props.style).toEqual(
      expect.arrayContaining([expect.objectContaining({ direction: 'rtl' })]),
    );
    // The layout uses direction and flexbox to mirror; no physical padding
    // edge is introduced by the responsive wrapper.
    const contentStyle = flattenedStyle(rtlView.getByTestId('conversation-inbox-content'));
    expect(contentStyle.paddingLeft).toBeUndefined();
    expect(contentStyle.paddingRight).toBeUndefined();
    expect(buttonLabels(listPaneButtons(rtlView))).toEqual(ltrLabels);

    await act(async () => {
      fireEvent.press(rtlView.getByTestId('tab-button-appointments'));
    });
    expect(onTab).toHaveBeenCalledWith('appointments');
    await rtlView.unmount();
  });

  it('recomputes the pane layout when the window is resized from compact to large', async () => {
    const view = await render(
      <ConversationInbox conversations={[syntheticConversation('conv-1', 'Synthetic Lab', false)]} onOpen={jest.fn()} />,
    );
    expect(flattenedStyle(view.getByTestId('conversation-inbox-list-pane')).maxWidth).toBeUndefined();

    setWindow(1024, 768, 2);
    await act(async () => {
      changeListener?.({ window: mockWindow });
    });

    expect(flattenedStyle(view.getByTestId('conversation-inbox-list-pane')).maxWidth).toBe(560);
    expect(view.getByLabelText('Synthetic Lab: awaiting_staff')).toBeTruthy();
    await view.unmount();
  });
});
