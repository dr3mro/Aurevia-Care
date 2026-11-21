/**
 * PAT-01, PAT-02, UX-01: a conversation thread is a detail pane. It must keep
 * one accessibility tree and one focus order at every window class, in both
 * orientations and at 100%, 150% and 200% text, while its content column caps
 * its reading width and is centred on a large window (decision 0005).
 * Safe areas come from a native SafeAreaView; nothing is cached (PAT-02).
 */
import { jest } from '@jest/globals';

import { render } from '@testing-library/react-native';
import { Dimensions } from 'react-native';

import { ConversationThread } from './ConversationThread';
import type { Conversation, Message } from './domain';

type WindowSize = { width: number; height: number; scale: number; fontScale: number };

let mockWindow: WindowSize = { width: 390, height: 844, scale: 2, fontScale: 1 };

// Expose the library's cross-platform component as a named export so this
// suite takes the production side of the module-shape resolver. The actual
// native view is used; only the export shape is adjusted for this file.
jest.mock('react-native-safe-area-context', () => {
  const actual = jest.requireActual('react-native-safe-area-context') as {
    SafeAreaView: unknown;
  };
  return { SafeAreaView: actual.SafeAreaView };
});

/** Maximum reading width of the detail column on a large window. */
const READING_WIDTH = 720;
/** Shared screen padding in the design tokens; text room grows beyond it. */
const BASE_PADDING = 16;

const message: Message = {
  id: 'm1',
  conversationId: 'conv-1',
  senderId: 'unit-a',
  senderRole: 'staff',
  body: 'Please bring your results and your insurance card to the laboratory.',
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
    serviceNotice: { serviceHours: 'Mon-Fri 08:00-17:00', responseTarget: 'within 1 business day' },
    instruction: {
      id: 'inst-1',
      title: 'Bring your insurance card.',
      revision: 2,
      acknowledgedRevision: null,
    },
    ...overrides,
  };
}

const WINDOWS = [
  { label: 'compact portrait', width: 390, height: 844, large: false },
  { label: 'compact landscape', width: 560, height: 320, large: false },
  { label: 'regular portrait', width: 700, height: 1000, large: false },
  { label: 'regular landscape', width: 768, height: 500, large: false },
  { label: 'large portrait', width: 840, height: 1000, large: true },
  { label: 'large landscape', width: 1024, height: 768, large: true },
] as const;

const FONT_SCALES = [1, 1.5, 2] as const;

type StyledNode = { props: { style?: unknown } };

function styleOf(node: StyledNode): Record<string, unknown> {
  const flattened: Record<string, unknown> = {};
  const style = node.props.style;
  for (const entry of Array.isArray(style) ? style : [style]) {
    if (entry !== null && typeof entry === 'object') {
      Object.assign(flattened, entry as Record<string, unknown>);
    }
  }
  return flattened;
}

type JsonNode = { type?: unknown; props?: { testID?: unknown }; children?: readonly (JsonNode | string)[] | null };

function nodeWithTestId(node: JsonNode | null, testID: string): JsonNode | undefined {
  if (node === null) {
    return undefined;
  }
  if (node.props?.testID === testID) {
    return node;
  }
  for (const child of node.children ?? []) {
    if (typeof child !== 'string') {
      const found = nodeWithTestId(child, testID);
      if (found !== undefined) {
        return found;
      }
    }
  }
  return undefined;
}

/** Test IDs in tree order: a locale-independent proxy for focus and reading order. */
function testIdOrder(node: JsonNode | null, into: string[] = []): string[] {
  if (node === null) {
    return into;
  }
  if (typeof node.props?.testID === 'string') {
    into.push(node.props.testID);
  }
  for (const child of node.children ?? []) {
    if (typeof child !== 'string') {
      testIdOrder(child, into);
    }
  }
  return into;
}

function setWindow(width: number, height: number, fontScale: number): void {
  mockWindow = { width, height, scale: 2, fontScale };
}

beforeEach(() => {
  jest.spyOn(Dimensions, 'get').mockImplementation(() => mockWindow);
});

afterEach(() => {
  jest.restoreAllMocks();
});

describe('ConversationThread responsive detail pane (PAT-01, PAT-02, UX-01)', () => {
  it.each(WINDOWS.flatMap((w) => FONT_SCALES.map((fontScale) => ({ ...w, fontScale }))))(
    '$label at font scale $fontScale keeps one labelled tree, a capped centred column and safe-area padding',
    async ({ width, height, fontScale, large }) => {
      setWindow(width, height, fontScale);
      const view = await render(
        <ConversationThread conversation={conversation()} onSend={jest.fn()} onAcknowledge={jest.fn()} />,
      );

      // The screen-reader labels are identical at every class, orientation and scale.
      expect(view.getByLabelText('conversation thread')).toBeTruthy();
      expect(view.getByLabelText('unit Synthetic Lab')).toBeTruthy();
      expect(view.getByLabelText('state awaiting_staff')).toBeTruthy();
      expect(view.getByLabelText('unit said: Please bring your results and your insurance card to the laboratory.')).toBeTruthy();
      expect(view.getByLabelText('instruction-revision')).toHaveTextContent('Revision 2');
      expect(view.getByLabelText('Acknowledge receipt of instruction revision 2')).toBeTruthy();
      expect(view.getByLabelText('reply input')).toBeTruthy();
      expect(view.getByLabelText('send reply')).toBeTruthy();
      expect(view.getByLabelText('unsent-note')).toBeTruthy();

      // The cross-platform native safe-area view and the scroll wrapper are
      // present at every class and scale (a plain View would fail this).
      expect(nodeWithTestId(view.toJSON() as unknown as JsonNode | null, 'conversation-thread-safe-area')?.type)
        .toBe('RNCSafeAreaView');
      expect(view.getByTestId('conversation-thread-scroll')).toBeTruthy();

      const content = styleOf(view.getByTestId('conversation-thread-content'));
      // Only large windows cap the reading width and centre the detail column.
      if (large) {
        expect(content.maxWidth).toBe(READING_WIDTH);
        expect(content.alignSelf).toBe('center');
      } else {
        expect(content.maxWidth).toBeUndefined();
        expect(content.alignSelf).toBeUndefined();
      }
      expect(content.width).toBe('100%');
      // Larger system text reflows into extra room rather than shrinking text.
      expect(content.paddingBottom).toBe(Math.max(0, fontScale - 1) * BASE_PADDING);
      expect(content.height).toBeUndefined();

      // The send control keeps a 44x44-point floor at every class and scale.
      const send = styleOf(view.getByLabelText('send reply'));
      expect(send.minHeight).toBeGreaterThanOrEqual(44);
      expect(send.minWidth).toBeGreaterThanOrEqual(44);

      await view.unmount();
    },
  );

  it('mirrors the layout under RTL without changing structure or focus order', async () => {
    setWindow(1024, 768, 2);
    const ltr = await render(
      <ConversationThread
        conversation={conversation()}
        onSend={jest.fn()}
        onAcknowledge={jest.fn()}
        showTabBar
        activeTab="messages"
        onTab={jest.fn()}
      />,
    );
    const ltrOrder = testIdOrder(ltr.toJSON() as unknown as JsonNode | null);
    await ltr.unmount();

    const rtl = await render(
      <ConversationThread
        conversation={conversation()}
        onSend={jest.fn()}
        onAcknowledge={jest.fn()}
        locale="ar"
        direction="rtl"
        showTabBar
        activeTab="messages"
        onTab={jest.fn()}
      />,
    );
    const root = styleOf(rtl.getByTestId('conversation-thread'));
    expect(root.direction).toBe('rtl');
    expect(styleOf(rtl.getByTestId('conversation-thread-content')).maxWidth).toBe(READING_WIDTH);
    expect(rtl.getByTestId('conversation-thread-safe-area')).toBeTruthy();

    expect(rtl.getByText('إرسال')).toBeTruthy();
    expect(rtl.getByLabelText('الرسائل tab')).toBeTruthy();
    expect(rtl.getByLabelText(/ساعات العمل/)).toBeTruthy();
    expect(testIdOrder(rtl.toJSON() as unknown as JsonNode | null)).toEqual(ltrOrder);

    await rtl.unmount();
  });

  it('never sets a fixed height, line count or auto-shrink on clinical text at 200% text', async () => {
    setWindow(390, 844, 2);
    const view = await render(
      <ConversationThread conversation={conversation()} onSend={jest.fn()} />,
    );

    for (const label of [
      'unit Synthetic Lab',
      'unit said: Please bring your results and your insurance card to the laboratory.',
      'instruction-title',
      'instruction-revision',
      'to',
      'unsent-note',
    ]) {
      const node = view.getByLabelText(label);
      expect(node.props.numberOfLines).toBeUndefined();
      expect(node.props.adjustsFontSizeToFit).toBeUndefined();
      expect(node.props.maxFontSizeMultiplier).toBeUndefined();
    }
    expect(styleOf(view.getByTestId('conversation-thread-content')).height).toBeUndefined();

    await view.unmount();
  });
});
