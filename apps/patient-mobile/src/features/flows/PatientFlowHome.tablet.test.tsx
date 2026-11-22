/**
 * Tablet, landscape and large-text layout acceptance for the patient flow home
 * (PAT-01, PAT-02, UX-01; decision 0005). Every value below is synthetic; no
 * patient data, clinical detail or credential appears in this file.
 *
 * The window hook is exercised for real: only the native window dimensions and
 * safe-area insets are supplied by deterministic mocks, so a width change
 * recomputes the same layout class the application would use at runtime.
 */
import { jest } from '@jest/globals';
import { fireEvent, render } from '@testing-library/react-native';
import { Dimensions } from 'react-native';

import { PatientFlowHome } from './PatientFlowHome';
import type { FlowPresentation } from './types';

type WindowSize = { width: number; height: number; scale: number; fontScale: number };
/** A rendered text element, read through the testing library's public result. */
type TextNode = {
  props: {
    numberOfLines?: unknown;
    adjustsFontSizeToFit?: unknown;
    maxFontSizeMultiplier?: unknown;
  };
};

/** Expected maximum readable inline size on a large window. */
const MAX_CONTENT_WIDTH = 720;
/** Accessibility floor from the token contract (44 pt / 48 dp). */
const MIN_TOUCH_TARGET = 44;

let mockInsets = { top: 24, right: 32, bottom: 48, left: 16 };
let mockWindow: WindowSize = { width: 390, height: 844, scale: 3, fontScale: 1 };

jest.mock('react-native-safe-area-context', () => ({
  useSafeAreaInsets: () => mockInsets,
}));

function presentation(overrides: Partial<FlowPresentation> = {}): FlowPresentation {
  return {
    organizationId: '11111111-1111-1111-1111-111111111111',
    locale: 'en',
    platform: 'android',
    rtl: false,
    direction: 'ltr',
    settingsLabel: 'Open Android system settings',
    secureStorageLabel: 'Protected by Android Keystore',
    policyVersion: 3,
    issuedAt: '2026-11-19T12:00:00Z',
    timeZoneContext: 'Europe/London',
    accessibilityProfile: 'WCAG-2.2-AA',
    noticeKinds: ['new_message', 'reauth_required'],
    ...overrides,
  };
}

/** A rendered text node must reflow at 200% instead of shrinking or truncating. */
function expectReflowsAtTwoHundredPercent(node: TextNode): void {
  expect(node.props.numberOfLines).toBeUndefined();
  expect(node.props.adjustsFontSizeToFit).toBeUndefined();
  const cap = node.props.maxFontSizeMultiplier as number | undefined;
  expect(cap === undefined || cap >= 2).toBe(true);
}

const FLOWS = ['appointments', 'reports', 'messages', 'enrollment'] as const;
const FLOW_LABELS = ['Appointments', 'Reports', 'Messages', 'Support and identity'] as const;

/** Window configurations: every layout class in both orientations. */
const WINDOW_CASES = [
  { name: 'compact portrait', width: 390, height: 844, large: false },
  { name: 'compact landscape', width: 590, height: 320, large: false },
  { name: 'regular portrait', width: 700, height: 1000, large: false },
  { name: 'regular landscape', width: 700, height: 400, large: false },
  { name: 'large portrait', width: 900, height: 1200, large: true },
  { name: 'large landscape', width: 1024, height: 768, large: true },
] as const;

const FONT_SCALES = [1, 1.5, 2] as const;

describe('PatientFlowHome responsive layout (PAT-01, PAT-02, UX-01)', () => {
  beforeEach(() => {
    mockInsets = { top: 24, right: 32, bottom: 48, left: 16 };
    mockWindow = { width: 390, height: 844, scale: 3, fontScale: 1 };
    jest.spyOn(Dimensions, 'get').mockImplementation(() => mockWindow);
    jest.spyOn(Dimensions, 'addEventListener').mockImplementation(() => ({
      remove: () => undefined,
    }));
  });

  afterEach(() => {
    jest.restoreAllMocks();
  });

  for (const windowCase of WINDOW_CASES) {
    for (const fontScale of FONT_SCALES) {
      it(`keeps structure, labels and touch targets at ${windowCase.name}, font scale ${fontScale}`, async () => {
        mockWindow = {
          width: windowCase.width,
          height: windowCase.height,
          scale: 3,
          fontScale,
        };

        const view = await render(
          <PatientFlowHome
            presentation={presentation()}
            flows={FLOWS}
            previewNotice="new_message"
            onSelectFlow={() => undefined}
            onOpenSettings={() => undefined}
          />,
        );

        const root = view.getByTestId('patient-flow-home');
        expect(root.props.style.flexDirection).toBe('row');
        expect(root.props.style.flexWrap).toBe('wrap');
        expect(root.props.style.maxWidth).toBe(windowCase.large ? MAX_CONTENT_WIDTH : '100%');
        expect(root.props.style.alignSelf).toBe(windowCase.large ? 'center' : 'stretch');
        expect(root.props.style.paddingTop).toBe(mockInsets.top);
        expect(root.props.style.paddingBottom).toBe(mockInsets.bottom);
        expect(root.props.style.paddingStart).toBe(mockInsets.left);
        expect(root.props.style.paddingEnd).toBe(mockInsets.right);

        for (const label of FLOW_LABELS) {
          expect(view.getByLabelText(label)).toBeTruthy();
        }
        expect(view.getByLabelText('Open Android system settings')).toBeTruthy();
        expect(view.getByTestId('notice-preview').children.join('')).toContain('You have a new message.');
        expect(view.getByTestId('secure-storage').children.join('')).toContain('Protected by Android Keystore');
        expect(view.getByTestId('time-zone').children.join('')).toContain('Europe/London');
        expect(view.getByTestId('accessibility-profile').children.join('')).toContain('WCAG-2.2-AA');

        const buttons = view.getAllByRole('button');
        expect(buttons.map((button) => button.props.accessibilityLabel)).toEqual([
          ...FLOW_LABELS,
          'Open Android system settings',
        ]);
        for (const button of buttons) {
          expect(button.props.style.minHeight).toBeGreaterThanOrEqual(MIN_TOUCH_TARGET);
          expect(button.props.style.minWidth).toBeGreaterThanOrEqual(MIN_TOUCH_TARGET);
        }

        // Every rendered text node is checked, including the header and the
        // settings label, so none can hide a truncation/shrinking prop.
        for (const label of [...FLOW_LABELS, 'Aurevia Care', 'Open Android system settings']) {
          expectReflowsAtTwoHundredPercent(view.getByText(label));
        }
        expectReflowsAtTwoHundredPercent(view.getByTestId('notice-preview'));
        expectReflowsAtTwoHundredPercent(view.getByTestId('secure-storage'));
        expectReflowsAtTwoHundredPercent(view.getByTestId('time-zone'));
        expectReflowsAtTwoHundredPercent(view.getByTestId('accessibility-profile'));
      });
    }
  }

  it('keeps the selection and settings actions at a large window and 200% text', async () => {
    mockWindow = { width: 1024, height: 768, scale: 3, fontScale: 2 };
    const selected: string[] = [];
    let settingsOpened = false;
    const view = await render(
      <PatientFlowHome
        presentation={presentation()}
        flows={FLOWS}
        previewNotice="reauth_required"
        onSelectFlow={(flow) => selected.push(flow)}
        onOpenSettings={() => { settingsOpened = true; }}
      />,
    );

    await fireEvent.press(view.getByLabelText('Reports'));
    await fireEvent.press(view.getByLabelText('Open Android system settings'));

    expect(selected).toEqual(['reports']);
    expect(settingsOpened).toBe(true);
  });

  it('mirrors layout and keeps Arabic labels on a large RTL window at 200% text', async () => {
    mockWindow = { width: 1024, height: 768, scale: 3, fontScale: 2 };
    const view = await render(
      <PatientFlowHome
        presentation={presentation({
          locale: 'ar',
          rtl: true,
          direction: 'rtl',
          settingsLabel: 'افتح إعدادات iOS',
          secureStorageLabel: 'محمي بواسطة Keychain',
          noticeKinds: ['new_instruction'],
        })}
        flows={['messages']}
        previewNotice="new_instruction"
        onSelectFlow={() => undefined}
        onOpenSettings={() => undefined}
      />,
    );

    const root = view.getByTestId('patient-flow-home');
    expect(root.props.style.flexDirection).toBe('row-reverse');
    expect(root.props.style.maxWidth).toBe(MAX_CONTENT_WIDTH);
    expect(root.props.style.flexWrap).toBe('wrap');
    // Logical insets mirror with the direction: the start edge is the
    // physical right edge in RTL, so the inset values swap.
    expect(root.props.style.paddingTop).toBe(mockInsets.top);
    expect(root.props.style.paddingBottom).toBe(mockInsets.bottom);
    expect(root.props.style.paddingStart).toBe(mockInsets.right);
    expect(root.props.style.paddingEnd).toBe(mockInsets.left);
    expect(view.getByLabelText('الرسائل')).toBeTruthy();
    expect(view.getByLabelText('افتح إعدادات iOS')).toBeTruthy();
    expect(view.getByTestId('notice-preview').children.join('')).toContain('لديك تعليمات جديدة.');
    const buttons = view.getAllByRole('button');
    expect(buttons.map((button) => button.props.accessibilityLabel)).toEqual([
      'الرسائل',
      'افتح إعدادات iOS',
    ]);
    for (const button of buttons) {
      expect(button.props.style.minHeight).toBeGreaterThanOrEqual(MIN_TOUCH_TARGET);
      expect(button.props.style.minWidth).toBeGreaterThanOrEqual(MIN_TOUCH_TARGET);
    }
  });

  it('keeps the empty-flow message within the centred readable column', async () => {
    mockWindow = { width: 900, height: 1200, scale: 3, fontScale: 2 };
    const view = await render(
      <PatientFlowHome
        presentation={presentation()}
        flows={[]}
        previewNotice="appointment_status"
        onSelectFlow={() => undefined}
        onOpenSettings={() => undefined}
      />,
    );

    const root = view.getByTestId('patient-flow-home');
    expect(root.props.style.alignSelf).toBe('center');
    expect(root.props.style.maxWidth).toBe(MAX_CONTENT_WIDTH);
    expect(view.getByText('No flows available')).toBeTruthy();
    expectReflowsAtTwoHundredPercent(view.getByText('No flows available'));
  });
});
