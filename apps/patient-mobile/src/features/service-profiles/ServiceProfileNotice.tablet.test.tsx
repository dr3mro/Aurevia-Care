/**
 * PAT-01, PAT-02, UX-01: responsive presentation of the MSG-06 service notice.
 *
 * The notice is a read-only, authenticated presentation of a server-computed
 * profile (PAT-02: no local cache, no new API call). This matrix renders the
 * real component through its normal entry point at every responsive window
 * class, at 1x / 1.5x / 2x text, in both orientations and in English and
 * Arabic RTL, and asserts that structure, safe-area padding, touch targets and
 * accessibility labels satisfy the tablet contract in docs/decisions/0005.
 */
import { jest } from '@jest/globals';
import { fireEvent, render } from '@testing-library/react-native';
import { Dimensions } from 'react-native';
import { SafeAreaInsetsContext } from 'react-native-safe-area-context';

import { ServiceProfileNotice } from './ServiceProfileNotice';
import type { ServiceAvailabilityView, ServiceProfileView } from './types';

type WindowSize = { width: number; height: number; scale: number; fontScale: number };
type NoticeView = Awaited<ReturnType<typeof render>>;

const UNIT = 'cccccccc-cccc-cccc-cccc-cccccccccccc';
const ORG = '11111111-1111-1111-1111-111111111111';
const MAX_CONTENT_WIDTH = 960;
const MIN_TOUCH_TARGET = 44;

let mockInsets = { top: 12, right: 24, bottom: 36, left: 48 };

jest.mock('react-native-safe-area-context', () => {
  const ReactModule = jest.requireActual('react') as typeof import('react');
  return { SafeAreaInsetsContext: ReactModule.createContext(null) };
});

function profile(overrides: Partial<ServiceProfileView> = {}): ServiceProfileView {
  return {
    unitId: UNIT,
    organizationId: ORG,
    unitName: 'Synthetic Laboratory',
    version: 1,
    serviceHours: {
      timeZone: 'America/New_York',
      windows: [
        { weekday: 'FRIDAY', start: '09:00', end: '17:00', wraps: false },
        { weekday: 'SUNDAY', start: '22:00', end: '02:00', wraps: true },
      ],
    },
    responseTargetMinutes: 30,
    topics: ['collection', 'results'],
    urgentInstructions: [
      { language: 'en', value: 'For urgent care call the unit' },
      { language: 'ar', value: 'للرعاية العاجلة اتصل بالوحدة' },
    ],
    urgentChannels: [
      { key: 'phone', label: 'Urgent phone', value: '+1-555-0100' },
      { key: 'link', label: 'Downtime guidance', value: 'https://example.test/downtime' },
      { key: 'email', label: 'Unit mailbox', value: 'unit@example.test' },
    ],
    queueOwner: 'nurse-lead',
    backupCoverage: ['nurse-backup'],
    escalationChannel: 'escalation-queue',
    effectiveFrom: '2026-11-19T12:00:00Z',
    ...overrides,
  };
}

function closed(): ServiceAvailabilityView {
  return { open: false, nextHandlingAt: '2026-11-20T14:00:00Z', timeZone: 'America/New_York' };
}

function setWindow(width: number, height: number, fontScale: number): void {
  const size: WindowSize = { width, height, scale: 2, fontScale };
  jest.spyOn(Dimensions, 'get').mockImplementation(() => size);
}

async function renderNotice(options: {
  isRTL?: boolean;
  availability?: ServiceAvailabilityView;
  profile?: ServiceProfileView;
  onAcknowledge?: () => void;
  withoutInsetsProvider?: boolean;
} = {}): Promise<NoticeView> {
  const isRTL = options.isRTL === true;
  const notice = (
    <ServiceProfileNotice
      profile={options.profile ?? profile()}
      availability={options.availability ?? closed()}
      language={isRTL ? 'ar' : 'en'}
      isRTL={isRTL}
      onAcknowledge={options.onAcknowledge ?? (() => undefined)}
    />
  );
  if (options.withoutInsetsProvider === true) {
    return await render(notice);
  }
  return await render(
    <SafeAreaInsetsContext.Provider value={mockInsets}>
      {notice}
    </SafeAreaInsetsContext.Provider>,
  );
}

/** Every published value and accessibility label is preserved in every layout. */
function assertContentAndLabels(view: NoticeView): void {
  for (const id of [
    'unit-name',
    'service-hours',
    'response-target',
    'topics',
    'urgent-instructions',
    'urgent-channel-0',
    'urgent-channel-1',
    'urgent-channel-2',
    'queue-owner',
    'backup-coverage',
    'escalation-channel',
    'next-handling',
    'acknowledge-guidance',
  ]) {
    expect(view.getByTestId(id)).toBeTruthy();
  }
  expect(view.getByText(/FRIDAY 09:00-17:00/)).toBeTruthy();
  expect(view.getByTestId('unit-name').props.accessibilityRole).toBe('header');
  expect(view.getByTestId('urgent-instructions').props.accessibilityRole).toBe('text');
  expect(view.getByTestId('urgent-channel-0').props.accessibilityRole).toBe('link');
  expect(view.getByTestId('urgent-channel-1').props.accessibilityRole).toBe('link');
  expect(view.getByTestId('urgent-channel-2').props.accessibilityRole).toBe('text');
  expect(view.getByTestId('next-handling').props.accessibilityRole).toBe('alert');
  expect(view.getByTestId('next-handling').props.accessibilityLiveRegion).toBe('polite');
  expect(view.getByTestId('acknowledge-guidance').props.accessibilityRole).toBe('button');
  expect(view.getByTestId('acknowledge-guidance').props.accessibilityLabel)
    .toBe('I understand the service guidance');
  expect(view.getByTestId('acknowledge-guidance').props.style.minHeight).toBe(MIN_TOUCH_TARGET);
  expect(view.getByTestId('acknowledge-guidance').props.style.minWidth).toBe(MIN_TOUCH_TARGET);
}

describe('ServiceProfileNotice responsive tablet layout (PAT-01, PAT-02, UX-01)', () => {
  beforeEach(() => {
    mockInsets = { top: 12, right: 24, bottom: 36, left: 48 };
  });

  afterEach(() => {
    jest.restoreAllMocks();
  });

  it.each([
    ['large portrait at 100% text', 900, 1200, 1],
    ['large landscape at 100% text', 1024, 768, 1],
    ['large portrait at 150% text', 900, 1200, 1.5],
    ['large landscape at 150% text', 1024, 768, 1.5],
  ] as const)('uses a centred two-pane layout for %s', async (_label, width, height, fontScale) => {
    setWindow(width, height, fontScale);

    const view = await renderNotice();
    const root = view.getByTestId('service-profile-notice');

    expect(root.props.style.flexDirection).toBe('row');
    expect(root.props.style.justifyContent).toBe('center');
    expect(root.props.style.alignSelf).toBe('center');
    expect(root.props.style.maxWidth).toBe(MAX_CONTENT_WIDTH);
    expect(view.getByTestId('notice-panes')).toBeTruthy();
    expect(view.getByTestId('notice-overview')).toBeTruthy();
    expect(view.getByTestId('notice-guidance')).toBeTruthy();
    expect(view.queryByTestId('notice-stack')).toBeNull();
    expect(view.getByTestId('notice-panes').props.style.flexDirection).toBe('row');
    assertContentAndLabels(view);
  });

  it.each([
    ['compact portrait at 100% text', 390, 844, 1],
    ['compact landscape at 200% text', 500, 300, 2],
    ['regular portrait at 150% text', 700, 900, 1.5],
    ['regular landscape at 200% text', 820, 600, 2],
    ['compact portrait at 200% text', 390, 844, 2],
    ['regular portrait at 100% text', 700, 900, 1],
  ] as const)('uses a single reflowing column for %s', async (_label, width, height, fontScale) => {
    setWindow(width, height, fontScale);

    const view = await renderNotice();
    const root = view.getByTestId('service-profile-notice');

    expect(root.props.style.flexDirection).toBe('row');
    expect(root.props.style.justifyContent).toBe('flex-start');
    expect(root.props.style.maxWidth).toBeUndefined();
    expect(view.getByTestId('notice-stack')).toBeTruthy();
    expect(view.queryByTestId('notice-panes')).toBeNull();
    assertContentAndLabels(view);
  });

  it('reflows a large window to one column at 200% text so nothing is clipped', async () => {
    setWindow(1024, 768, 2);

    const view = await renderNotice();

    expect(view.getByTestId('service-profile-notice').props.style.maxWidth).toBe(MAX_CONTENT_WIDTH);
    expect(view.getByTestId('notice-stack')).toBeTruthy();
    expect(view.queryByTestId('notice-panes')).toBeNull();
    assertContentAndLabels(view);
  });

  it('mirrors the two panes and swaps the safe-area insets for Arabic RTL', async () => {
    setWindow(1024, 768, 1);

    const view = await renderNotice({ isRTL: true });
    const root = view.getByTestId('service-profile-notice');

    expect(root.props.style.flexDirection).toBe('row-reverse');
    expect(root.props.style.paddingStart).toBe(mockInsets.right);
    expect(root.props.style.paddingEnd).toBe(mockInsets.left);
    expect(root.props.style.paddingTop).toBe(mockInsets.top);
    expect(root.props.style.paddingBottom).toBe(mockInsets.bottom);
    expect(view.getByTestId('notice-panes').props.style.flexDirection).toBe('row-reverse');
    assertContentAndLabels(view);
  });

  it('mirrors a compact column and respects safe-area insets for Arabic RTL', async () => {
    setWindow(390, 844, 2);

    const view = await renderNotice({ isRTL: true });
    const root = view.getByTestId('service-profile-notice');

    expect(root.props.style.flexDirection).toBe('row-reverse');
    expect(root.props.style.paddingStart).toBe(mockInsets.right);
    expect(root.props.style.paddingEnd).toBe(mockInsets.left);
    expect(view.getByTestId('notice-stack')).toBeTruthy();
    assertContentAndLabels(view);
  });

  it('respects the safe-area insets in an LTR layout', async () => {
    setWindow(700, 900, 1);

    const root = (await renderNotice()).getByTestId('service-profile-notice');

    expect(root.props.style.paddingStart).toBe(mockInsets.left);
    expect(root.props.style.paddingEnd).toBe(mockInsets.right);
    expect(root.props.style.paddingTop).toBe(mockInsets.top);
    expect(root.props.style.paddingBottom).toBe(mockInsets.bottom);
  });

  it('falls back to zero safe-area insets when no provider is mounted', async () => {
    setWindow(390, 844, 1);

    const root = (await renderNotice({ withoutInsetsProvider: true }))
      .getByTestId('service-profile-notice');

    expect(root.props.style.paddingTop).toBe(0);
    expect(root.props.style.paddingBottom).toBe(0);
    expect(root.props.style.paddingStart).toBe(0);
    expect(root.props.style.paddingEnd).toBe(0);
  });

  it('omits the urgent block when no urgent guidance is approved', async () => {
    setWindow(390, 844, 1);

    const view = await renderNotice({
      profile: profile({ urgentInstructions: [], urgentChannels: [] }),
    });

    expect(view.queryByTestId('urgent-instructions')).toBeNull();
    expect(view.queryByTestId('urgent-channel-0')).toBeNull();
    expect(view.getByTestId('notice-stack')).toBeTruthy();
  });

  it('omits the after-hours banner when the unit is open or no instant is published', async () => {
    setWindow(900, 1200, 1);

    const open = await renderNotice({ availability: { open: true, timeZone: 'UTC' } });
    expect(open.queryByTestId('next-handling')).toBeNull();

    const noInstant = await renderNotice({
      availability: { open: false, timeZone: 'UTC' },
    });
    expect(noInstant.queryByTestId('next-handling')).toBeNull();
  });

  it('keeps the acknowledgement action working in the mirrored two-pane layout', async () => {
    setWindow(1024, 768, 1);
    let acknowledged = false;

    const view = await renderNotice({
      isRTL: true,
      onAcknowledge: () => { acknowledged = true; },
    });

    await fireEvent.press(view.getByTestId('acknowledge-guidance'));
    expect(acknowledged).toBe(true);
  });
});
