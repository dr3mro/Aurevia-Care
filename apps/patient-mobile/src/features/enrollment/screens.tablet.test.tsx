/**
 * PAT-01, PAT-02, UX-01: responsive, large-text and right-to-left layout for
 * the privacy-safe enrollment screens. Real window dimensions drive the
 * shared `windowClassFor` breakpoints so every class / font-scale combination
 * renders through the real components and proves the presentation contract
 * without changing behaviour.
 */
import { jest } from '@jest/globals';
import { render } from '@testing-library/react-native';
import { Dimensions } from 'react-native';

import type { WindowClass } from '../../app/layout/useWindowClass';
import {
  EnrollmentScreen,
  LockoutScreen,
  RecoveryScreen,
  StepUpPrompt,
  SupportRequestScreen,
  type EnrollmentStrings,
  type LockoutStrings,
  type RecoveryStrings,
  type StepUpStrings,
  type SupportStrings,
} from './screens';
import { SUBMISSION_NOTICE } from './model';

/** Synthetic window size fed to the real `useWindowDimensions` hook. */
type WindowSize = { width: number; height: number; scale: number; fontScale: number };
let mockWindow: WindowSize;

const MAX_CONTENT_WIDTH = 720;
const MIN_TOUCH_TARGET = 44;

const WINDOWS: readonly {
  windowClass: WindowClass;
  width: number;
  height: number;
  isLandscape: boolean;
}[] = [
  { windowClass: 'compact', width: 390, height: 844, isLandscape: false },
  { windowClass: 'regular', width: 700, height: 1000, isLandscape: false },
  { windowClass: 'large', width: 1180, height: 820, isLandscape: true },
];
const FONT_SCALES = [1, 1.5, 2];

const noop = (): void => undefined;

const enrollmentStrings: EnrollmentStrings = {
  codeLabel: 'Invitation code',
  codeHint: 'Enter the code from your clinic',
  submit: 'Enroll',
  expired: 'This code has expired',
  reused: 'This code has already been used',
  locked: 'This account is locked',
  invalid: 'Check the code and try again',
};
const recoveryStrings: RecoveryStrings = {
  codeLabel: 'Recovery code',
  verificationLabel: 'Verification facts',
  submit: 'Restore access',
  expired: 'Recovery code expired',
  reused: 'Recovery code used',
  locked: 'Account locked',
  invalid: 'Verification failed',
};
const lockoutStrings: LockoutStrings = {
  title: 'Temporarily locked',
  support: 'Get help',
  retry: 'Try again',
  timerLabel: 'Time remaining',
};
const stepUpStrings: StepUpStrings = {
  unlink: 'Confirm removing this chart',
  changeContact: 'Confirm contact change',
  confirm: 'Confirm',
  cancel: 'Cancel',
};
const supportStrings: SupportStrings = {
  title: 'Contact support',
  messageLabel: 'Your request',
  submit: 'Send request',
  category: 'Reason',
  invalidCategory: 'Choose a reason',
  empty: 'Write a short request',
  tooLong: 'Request is too long',
  phi: 'Remove phone numbers or email addresses',
};

/** One synthetic render per screen plus the invariants that must survive. */
interface ScreenCase {
  name: string;
  element: React.JSX.Element;
  labels: string[];
  primaryButton: string;
}

function screenCases(isRTL: boolean): ScreenCase[] {
  return [
    {
      name: 'EnrollmentScreen',
      element: (
        <EnrollmentScreen
          status="idle"
          code="ABCD1234"
          onCodeChange={noop}
          onSubmit={noop}
          strings={enrollmentStrings}
          isRTL={isRTL}
        />
      ),
      labels: ['Invitation code', 'Enroll'],
      primaryButton: 'Enroll',
    },
    {
      name: 'RecoveryScreen',
      element: (
        <RecoveryScreen
          status="idle"
          code="ABCD1234"
          verification="site-facts"
          onCodeChange={noop}
          onVerificationChange={noop}
          onSubmit={noop}
          strings={recoveryStrings}
          isRTL={isRTL}
        />
      ),
      labels: ['Recovery code', 'Verification facts', 'Restore access'],
      primaryButton: 'Restore access',
    },
    {
      name: 'LockoutScreen',
      element: (
        <LockoutScreen
          remainingSeconds={125}
          onSupport={noop}
          onRetry={noop}
          strings={lockoutStrings}
          isRTL={isRTL}
        />
      ),
      labels: ['Time remaining', 'Get help', 'Try again'],
      primaryButton: 'Get help',
    },
    {
      name: 'StepUpPrompt',
      element: (
        <StepUpPrompt
          reason="unlink_chart"
          onConfirm={noop}
          onCancel={noop}
          strings={stepUpStrings}
          isRTL={isRTL}
        />
      ),
      labels: ['Confirm', 'Cancel'],
      primaryButton: 'Confirm',
    },
    {
      name: 'SupportRequestScreen',
      element: (
        <SupportRequestScreen
          category="accessibility"
          message="Please help me access my record"
          noticeVisible={false}
          onCategoryChange={noop}
          onMessageChange={noop}
          onSubmit={noop}
          strings={supportStrings}
          isRTL={isRTL}
        />
      ),
      labels: ['Reason', 'identity_dispute', 'accessibility', 'record_access', 'Your request', 'Send request'],
      primaryButton: 'Send request',
    },
  ];
}

/** Minimal shape of the renderer's JSON tree used for style inspection. */
interface TestNode {
  type: string;
  props: Record<string, unknown>;
  children: (TestNode | string)[] | null;
}

/** Resolve a style that may be a single object or an array of objects. */
function flattenStyle(style: unknown): Record<string, unknown> {
  if (Array.isArray(style)) {
    return Object.assign({}, ...style.filter(Boolean).map((part) => flattenStyle(part)));
  }
  return style !== null && typeof style === 'object' ? (style as Record<string, unknown>) : {};
}

/** Depth-first list of every host node in a rendered tree. */
function nodesOf(node: TestNode | string | null): TestNode[] {
  if (node === null || typeof node === 'string') {
    return [];
  }
  return [node, ...(node.children ?? []).flatMap(nodesOf)];
}

beforeEach(() => {
  mockWindow = { width: 390, height: 844, scale: 2, fontScale: 1 };
  jest.spyOn(Dimensions, 'get').mockImplementation(() => mockWindow);
});

afterEach(() => {
  jest.restoreAllMocks();
});

/** One render per case keeps React 19's act scopes independent. */
interface RenderCase {
  window: (typeof WINDOWS)[number];
  fontScale: number;
  testCase: ScreenCase;
}

function casesFor(screens: ScreenCase[], scales: number[]): RenderCase[] {
  return WINDOWS.flatMap((window) =>
    scales.flatMap((fontScale) => screens.map((testCase) => ({ window, fontScale, testCase }))),
  );
}

function applyWindow(renderCase: RenderCase): void {
  mockWindow = {
    width: renderCase.window.width,
    height: renderCase.window.height,
    scale: 2,
    fontScale: renderCase.fontScale,
  };
}

describe('enrollment screens across window classes and font scales', () => {
  it.each(casesFor(screenCases(false), FONT_SCALES))(
    'lays out $testCase.name with a readable width in a safe area on $window.windowClass at scale $fontScale',
    async (renderCase) => {
      applyWindow(renderCase);
      const view = await render(renderCase.testCase.element);
      const root = view.toJSON() as TestNode;
      const rootStyle = flattenStyle(root.props.style);

      // The shared base style is preserved; the responsive style refines it.
      expect(rootStyle.flex).toBe(1);
      expect(rootStyle.gap).toBe(8);
      expect(rootStyle.width).toBe('100%');
      expect(rootStyle.padding).toBe(16);
      if (renderCase.window.windowClass === 'large') {
        expect(rootStyle.maxWidth).toBe(MAX_CONTENT_WIDTH);
        expect(rootStyle.alignSelf).toBe('center');
      } else {
        expect(rootStyle.maxWidth).toBeUndefined();
        expect(rootStyle.alignSelf).toBe('stretch');
      }

      // Structure and screen-reader labels are unchanged at every width/scale.
      for (const label of renderCase.testCase.labels) {
        expect(view.getByLabelText(label)).toBeTruthy();
      }
      expect(view.getByRole('button', { name: renderCase.testCase.primaryButton })).toBeTruthy();
    },
  );

  it.each(casesFor(screenCases(false), [2]))(
    'keeps touch targets and text unclipped at 200% for $testCase.name on $window.windowClass',
    async (renderCase) => {
      applyWindow(renderCase);
      const view = await render(renderCase.testCase.element);
      const nodes = nodesOf(view.toJSON() as TestNode);

      const interactive = nodes.filter((node) => {
        const role = node.props.accessibilityRole;
        return role === 'button' || role === 'radio';
      });
      expect(interactive.length).toBeGreaterThan(0);
      for (const node of interactive) {
        const style = flattenStyle(node.props.style);
        expect(style.minHeight as number).toBeGreaterThanOrEqual(MIN_TOUCH_TARGET);
        expect(style.minWidth as number).toBeGreaterThanOrEqual(MIN_TOUCH_TARGET);
      }

      // No text is shrunk or truncated: it wraps instead of clipping at 200%.
      for (const node of nodes) {
        expect(node.props.numberOfLines).toBeUndefined();
        expect(node.props.adjustsFontSizeToFit).toBeUndefined();
        expect(node.props.maxFontSizeMultiplier).toBeUndefined();
        expect(flattenStyle(node.props.style).lineHeight).toBeUndefined();
      }

      // A two-button row wraps rather than forcing a narrow window to clip.
      if (renderCase.testCase.name === 'StepUpPrompt') {
        const row = nodes.find((node) => flattenStyle(node.props.style).flexWrap === 'wrap');
        expect(row).toBeDefined();
      }
    },
  );

  it('keeps every support-screen message and action in the tree at 200% text on a compact window', async () => {
    mockWindow = { width: 390, height: 844, scale: 2, fontScale: 2 };
    const view = await render(
      <SupportRequestScreen
        category={null}
        message=""
        noticeVisible
        onCategoryChange={noop}
        onMessageChange={noop}
        onSubmit={noop}
        strings={supportStrings}
        isRTL={false}
      />,
    );

    expect(view.getByText('Choose a reason')).toBeTruthy();
    expect(view.getByText(SUBMISSION_NOTICE)).toBeTruthy();
    expect(view.getByLabelText('Reason')).toBeTruthy();
    expect(view.getByRole('button', { name: 'Send request' })).toBeTruthy();
    for (const label of ['identity_dispute', 'accessibility', 'record_access']) {
      expect(view.getByRole('radio', { name: label })).toBeTruthy();
    }
  });

  it.each(casesFor(screenCases(true), FONT_SCALES))(
    'mirrors $testCase.name under right-to-left on $window.windowClass at scale $fontScale',
    async (renderCase) => {
      applyWindow(renderCase);
      const view = await render(renderCase.testCase.element);
      const nodes = nodesOf(view.toJSON() as TestNode);

      const aligned = nodes.filter((node) => flattenStyle(node.props.style).textAlign !== undefined);
      expect(aligned.length).toBeGreaterThan(0);
      for (const node of aligned) {
        expect(flattenStyle(node.props.style).textAlign).toBe('right');
      }
      for (const label of renderCase.testCase.labels) {
        expect(view.getByLabelText(label)).toBeTruthy();
      }

      if (renderCase.testCase.name === 'StepUpPrompt') {
        const row = nodes.find((node) => flattenStyle(node.props.style).flexDirection === 'row-reverse');
        expect(row).toBeDefined();
        expect(flattenStyle(row?.props.style).flexWrap).toBe('wrap');
      }
    },
  );
});
