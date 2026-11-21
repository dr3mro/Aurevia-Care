/**
 * PAT-01, PAT-02, UX-01: responsive presentation of the messaging governance
 * panel. The panel is driven by the shared window hook, so the same component
 * must keep its structure, screen-reader labels and explicit copy action across
 * every layout class, orientation and font scale. Layout is presentation only —
 * no storage, cache, API call or clinical fact is introduced here (PAT-02).
 */
import { jest } from '@jest/globals';
import { fireEvent, render } from '@testing-library/react-native';

import { SCREEN_TOKENS } from '../appointments/patientDesign';
import type { WindowLayout } from '../../app/layout/useWindowClass';
import { MessagingAuditPanel } from './MessagingAuditPanel';
import type { MessagingAuditEvent, RevocationEffect } from './types';

const ORG = '11111111-1111-1111-1111-111111111111';
const CONNECTION = 'dddddddd-dddd-dddd-dddd-dddddddddddd';
const RESOURCE = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
const UNIT = 'ffffffff-ffff-ffff-ffff-ffffffffffff';
const NOW = '2026-11-19T12:00:00Z';

const events: readonly MessagingAuditEvent[] = [
  {
    id: 'event-1', organizationId: ORG, subject: 'patient-user', purpose: 'MSG_GOVERNANCE',
    action: 'download', resourceId: RESOURCE, unitId: UNIT, correlationId: 'corr-1',
    outcome: 'success', occurredAt: NOW,
  },
];
const effect: RevocationEffect = {
  id: 'effect-1', organizationId: ORG, connectionId: CONNECTION, messagingBlocked: true,
  attachmentAccessBlocked: true, priorConversationsReadable: true,
  recordAccessBasis: 'policy-v1', recordedAt: NOW,
};

/** Non-zero insets make it observable that the panel honours the safe area. */
const mockInsets = { top: 12, right: 24, bottom: 36, left: 48 };

let mockLayout: WindowLayout = {
  windowClass: 'compact',
  width: 390,
  height: 844,
  isLandscape: false,
  fontScale: 1,
  insets: mockInsets,
};

// A named-export mock makes the component take the shared-hook path; the
// repository's global safe-area mock publishes a different export shape.
jest.mock('react-native-safe-area-context', () => ({
  useSafeAreaInsets: () => mockInsets,
}));

jest.mock('../../app/layout/useWindowClass', () => ({
  useWindowClass: () => mockLayout,
}));

interface RenderOptions {
  windowClass?: WindowLayout['windowClass'];
  fontScale?: number;
  isLandscape?: boolean;
  isRTL?: boolean;
}

async function renderPanel(options: RenderOptions = {}) {
  mockLayout = {
    windowClass: options.windowClass ?? 'compact',
    width: options.windowClass === 'large' ? 1024 : 800,
    height: options.isLandscape ? 600 : 900,
    isLandscape: options.isLandscape ?? false,
    fontScale: options.fontScale ?? 1,
    insets: mockInsets,
  };
  const onRequestCopy = jest.fn();
  const view = await render(
    <MessagingAuditPanel
      events={events}
      revocationEffect={effect}
      isRTL={options.isRTL ?? false}
      onRequestCopy={onRequestCopy}
    />,
  );
  return { view, onRequestCopy };
}

const windowClasses = ['compact', 'regular', 'large'] as const;
const fontScales = [1, 1.5, 2] as const;
const orientations = [false, true] as const;

describe('MessagingAuditPanel responsive layout', () => {
  it.each(
    windowClasses.flatMap((windowClass) =>
      fontScales.flatMap((fontScale) =>
        orientations.map((isLandscape) => ({ windowClass, fontScale, isLandscape })),
      ),
    ),
  )(
    'renders $windowClass at font scale $fontScale (landscape=$isLandscape) with stable structure, labels and safe touch targets',
    async ({ windowClass, fontScale, isLandscape }) => {
      const { view } = await renderPanel({ windowClass, fontScale, isLandscape });
      const root = view.getByTestId('messaging-audit-panel').props.style;

      expect(root.flexDirection).toBe('column');
      expect(root.paddingTop).toBe(mockInsets.top);
      expect(root.paddingRight).toBe(mockInsets.right);
      expect(root.paddingBottom).toBe(mockInsets.bottom);
      expect(root.paddingLeft).toBe(mockInsets.left);

      if (windowClass === 'large') {
        expect(root.width).toBe('100%');
        expect(root.maxWidth).toBe(720);
        expect(root.alignSelf).toBe('center');
      } else {
        expect(root.maxWidth).toBeUndefined();
        expect(root.alignSelf).toBeUndefined();
      }

      // Structure, facts and accessible labels are identical across classes and scales.
      expect(view.getByTestId('audit-event-1')).toBeTruthy();
      expect(view.getByText('download')).toBeTruthy();
      expect(view.getByText('success')).toBeTruthy();
      expect(view.getByText('New messaging blocked')).toBeTruthy();
      expect(view.getByText('Attachments blocked')).toBeTruthy();
      expect(view.getByText('Prior conversations readable')).toBeTruthy();
      const copy = view.getByLabelText('Request a copy');
      expect(copy.props.accessibilityRole).toBe('button');
      expect(copy.props.style.minHeight).toBe(SCREEN_TOKENS.size.target);
      expect(copy.props.style.minWidth).toBe(SCREEN_TOKENS.size.target);

      // At 200% text is reflowed, never clipped to a fixed line count, height
      // or capped font scale. This assertion runs at every scale including 2.
      const renderedTexts = [
        'download', 'success', NOW, 'New messaging blocked', 'Attachments blocked',
        'Prior conversations readable', 'Request a copy',
      ];
      for (const label of renderedTexts) {
        const node = view.getByText(label);
        expect(node.props.numberOfLines).toBeUndefined();
        expect(node.props.maxFontSizeMultiplier).toBeUndefined();
        expect(node.props.adjustsFontSizeToFit).toBeUndefined();
        expect(node.props.style?.height).toBeUndefined();
        expect(node.props.style?.maxHeight).toBeUndefined();
      }
      // The touch target is a minimum, not a fixed height.
      expect(copy.props.style.height).toBeUndefined();
      expect(copy.props.style.maxHeight).toBeUndefined();
    },
  );

  it('mirrors the panel in RTL on a large window without changing labels or the explicit copy action', async () => {
    const { view, onRequestCopy } = await renderPanel({
      windowClass: 'large', fontScale: 2, isRTL: true,
    });
    const root = view.getByTestId('messaging-audit-panel').props.style;

    expect(root.flexDirection).toBe('column-reverse');
    expect(root.maxWidth).toBe(720);
    expect(root.alignSelf).toBe('center');
    expect(view.getByTestId('audit-event-1')).toBeTruthy();
    expect(view.getByLabelText('Request a copy')).toBeTruthy();
    expect(onRequestCopy).not.toHaveBeenCalled();

    await fireEvent.press(view.getByLabelText('Request a copy'));
    expect(onRequestCopy).toHaveBeenCalledTimes(1);
  });
});
