/**
 * RESP-APP-RELEASED-REPORTS-SCREEN (PAT-01, PAT-02, UX-01).
 *
 * Responsive presentation contract for the released reports screen. Layout is
 * driven by the window (decision 0005), never by the device, so the same
 * structure is asserted at every window class, font scale and orientation.
 * The window hook is replaced with a controllable value; the hook itself has
 * its own tests. All data is synthetic.
 */
import { jest } from '@jest/globals';
import type { ReactElement } from 'react';
import { Dimensions } from 'react-native';
import { SafeAreaInsetsContext } from 'react-native-safe-area-context';
import type { EdgeInsets } from 'react-native-safe-area-context';
import { render } from '@testing-library/react-native';

import type { WindowClass } from '../../app/layout/useWindowClass';

import { ReleasedReportsScreen } from './ReleasedReportsScreen';
import type { ReleasedReport } from './types';

/** Synthetic report with a result so both panes exist when the screen uses two. */
const report: ReleasedReport = {
  reportId: 'report-42',
  revision: 3,
  issuingOrganization: 'Synthetic Clinic',
  effectiveDate: '2026-06-10',
  language: 'en',
  correctionReason: 'corrected synthetic value',
  supersedesRevision: 2,
  title: 'Synthetic panel',
  result: { label: 'Synthetic marker', value: '6.9 %', referenceRange: '4.0 to 5.6' },
};

const insets: EdgeInsets = { top: 24, right: 32, bottom: 40, left: 48 };

type WindowSize = { width: number; height: number; scale: number; fontScale: number };

let mockWindow: WindowSize = { width: 390, height: 844, scale: 2, fontScale: 1 };
let mockInsets: EdgeInsets = insets;

jest.mock('react-native-safe-area-context', () => {
  const react = jest.requireActual('react') as typeof import('react');
  return { SafeAreaInsetsContext: react.createContext<EdgeInsets | null>(null) };
});

/** Rendered view returned by the module's async testing-library `render`. */
type RenderedView = Awaited<ReturnType<typeof render>>;

/** Render with a stable synthetic safe-area context; omit it to cover the fallback. */
async function renderScreen(screen: ReactElement, withInsets = true): Promise<RenderedView> {
  return await render(
    withInsets ? (
      <SafeAreaInsetsContext.Provider value={mockInsets}>{screen}</SafeAreaInsetsContext.Provider>
    ) : (
      screen
    ),
  );
}

/** A rendered node in the React test tree. */
interface RenderedNode {
  readonly props?: Readonly<Record<string, unknown>>;
  readonly children?: readonly (RenderedNode | string | null)[] | null;
}

/** All rendered nodes in DOM order, from the screen reader's point of view. */
function renderedNodes(view: RenderedView): RenderedNode[] {
  const nodes: RenderedNode[] = [];
  const walk = (node: unknown): void => {
    if (node === null || typeof node !== 'object') {
      return;
    }
    if (Array.isArray(node)) {
      node.forEach(walk);
      return;
    }
    const current = node as RenderedNode;
    nodes.push(current);
    for (const child of current.children ?? []) {
      walk(child);
    }
  };
  walk(view.toJSON());
  return nodes;
}

/** Every accessibility label in DOM order across views and text; focus order is DOM order (UX-01). */
function a11yLabels(view: RenderedView): string[] {
  return renderedNodes(view)
    .map((node) => node.props?.accessibilityLabel)
    .filter((label): label is string => typeof label === 'string');
}

/** Assert that text is never shrunk, truncated or capped below 200% (UX-01). */
function expectReflowAtTwoHundredPercent(view: RenderedView): void {
  for (const node of renderedNodes(view)) {
    expect(node.props?.numberOfLines).toBeUndefined();
    expect(node.props?.adjustsFontSizeToFit).not.toBe(true);
    if (node.props?.maxFontSizeMultiplier !== undefined) {
      expect(node.props.maxFontSizeMultiplier).toBeGreaterThanOrEqual(2);
    }
  }
  expect(view.getByTestId('pills-report-42').props.style).toEqual(
    expect.objectContaining({ flexWrap: 'wrap' }),
  );
}

describe('ReleasedReportsScreen responsive layout (RESP-APP-RELEASED-REPORTS-SCREEN)', () => {
  const cases: readonly { windowClass: WindowClass; fontScale: number; isLandscape: boolean }[] = [
    { windowClass: 'compact', fontScale: 1, isLandscape: false },
    { windowClass: 'compact', fontScale: 1.5, isLandscape: true },
    { windowClass: 'compact', fontScale: 2, isLandscape: false },
    { windowClass: 'regular', fontScale: 1, isLandscape: true },
    { windowClass: 'regular', fontScale: 1.5, isLandscape: false },
    { windowClass: 'regular', fontScale: 2, isLandscape: true },
    { windowClass: 'large', fontScale: 1, isLandscape: true },
    { windowClass: 'large', fontScale: 1.5, isLandscape: false },
    { windowClass: 'large', fontScale: 2, isLandscape: true },
  ];

  beforeEach(() => {
    jest.spyOn(Dimensions, 'get').mockImplementation(() => mockWindow);
    jest.spyOn(Dimensions, 'addEventListener').mockImplementation(() => ({ remove: () => undefined }));
  });

  afterEach(() => {
    jest.restoreAllMocks();
  });

  it.each(cases)(
    'renders all data, labels and accessible structure at $windowClass / $fontScale / landscape=$isLandscape',
    async ({ windowClass, fontScale, isLandscape }) => {
      const width = windowClass === 'compact' ? 390 : windowClass === 'regular' ? 700 : 1024;
      mockWindow = {
        width,
        height: isLandscape ? Math.round(width * 0.6) : Math.round(width * 1.4),
        scale: 2,
        fontScale,
      };
      mockInsets = insets;

      const view = await renderScreen(
        <ReleasedReportsScreen reports={[report]} error={null} onDownload={() => undefined} locale="en" />,
      );

      // Screen chrome and every clinical label survive every layout.
      expect(view.getByTestId('released-reports')).toBeTruthy();
      expect(view.getByTestId('released-reports-title')).toHaveTextContent('Reports');
      expect(view.getByLabelText('screen-subtitle')).toBeTruthy();
      expect(view.getByLabelText('report-report-42')).toBeTruthy();
      expect(view.getByLabelText('report-title')).toHaveTextContent('Synthetic panel');
      expect(view.getByLabelText('issuer')).toHaveTextContent('Synthetic Clinic');
      expect(view.getByLabelText('effective-date')).toHaveTextContent('2026-06-10');
      expect(view.getByLabelText('revision')).toHaveTextContent('Revision 3');
      expect(view.getByLabelText('correction')).toHaveTextContent(/Corrected revision 2/);
      expect(view.getByLabelText('result-value')).toHaveTextContent('6.9 % reference 4.0 to 5.6');
      expect(view.getByLabelText('language')).toHaveTextContent('LTR');
      expect(view.getByTestId('download-report-42').props.accessibilityLabel).toBe('Download PDF');

      // List plus detail: one summary pane and one result pane, in DOM order.
      const panes = view.getByTestId('report-panes-report-42');
      expect(panes.props.style.flexDirection).toBe(windowClass === 'large' ? 'row' : 'column');
      expect(view.getByTestId('report-summary-report-42')).toBeTruthy();
      expect(view.getByTestId('report-detail-report-42')).toBeTruthy();
      expect(view.getByTestId('report-summary-report-42').props.style.minWidth).toBeGreaterThanOrEqual(44);
      expect(view.getByTestId('report-detail-report-42').props.style.minWidth).toBeGreaterThanOrEqual(44);
      expect(view.getByTestId('download-report-42').props.style).toEqual(
        expect.arrayContaining([expect.objectContaining({ minHeight: 48 })]),
      );

      // Content is capped and centred only on large windows; gutter grows with text.
      const content = view.getByTestId('reports-content');
      expect(content.props.style.alignSelf).toBe('center');
      expect(content.props.style.maxWidth).toBe(windowClass === 'large' ? 960 : undefined);
      expect(panes.props.style.columnGap).toBe(Math.round(16 * fontScale));

      // Safe-area insets are passed through; ScreenFrame supplies the base gutter.
      expect(content.props.style.paddingTop).toBe(insets.top);
      expect(content.props.style.paddingBottom).toBe(insets.bottom);
      expect(content.props.style.paddingStart).toBe(insets.left);
      expect(content.props.style.paddingEnd).toBe(insets.right);

      // Focus order and labels are unchanged across classes, scales and orientations.
      expect(a11yLabels(view)).toEqual([
        'released-reports',
        'screen-subtitle',
        'report-report-42',
        'report-title',
        'issuer',
        'effective-date',
        'revision',
        'Corrected',
        'correction',
        'Download PDF',
        'download-check',
        'result-label',
        'result-value',
        'language',
      ]);
      expectReflowAtTwoHundredPercent(view);
    },
  );

  it('mirrors the panes, safe-area insets and row direction under Arabic RTL', async () => {
    mockWindow = { width: 1024, height: 768, scale: 2, fontScale: 1.5 };
    mockInsets = insets;

    const view = await renderScreen(
      <ReleasedReportsScreen
        reports={[{ ...report, language: 'ar' }]}
        error={null}
        onDownload={() => undefined}
        locale="ar"
        showTabBar
        activeTab="reports"
      />,
    );

    const content = view.getByTestId('reports-content');
    expect(content.props.style.paddingStart).toBe(insets.right);
    expect(content.props.style.paddingEnd).toBe(insets.left);
    expect(view.getByLabelText('report-report-42').props.style.direction).toBe('rtl');
    expect(view.getByTestId('report-panes-report-42').props.style.flexDirection).toBe('row');
    expect(view.getByTestId('released-reports-title')).toHaveTextContent('التقارير');
    expect(view.getByLabelText('download-check')).toBeTruthy();
    expect(view.getByLabelText('التقارير tab')).toBeTruthy();
    expect(a11yLabels(view)).toContain('report-report-42');
  });

  it('keeps a single summary pane when a report has no result detail and tolerates no provider', async () => {
    mockWindow = { width: 1024, height: 1400, scale: 2, fontScale: 1 };
    // Omit the key rather than assigning undefined: exactOptionalPropertyTypes
    // forbids writing undefined to an optional property, and the screen treats an
    // absent result exactly like an explicitly undefined one.
    const { result: _discardedResult, ...withoutResult } = report;

    const view = await renderScreen(
      <ReleasedReportsScreen reports={[withoutResult]} error={null} onDownload={() => undefined} locale="en" />,
      false,
    );

    expect(view.getByTestId('report-summary-report-42')).toBeTruthy();
    expect(view.queryByTestId('report-detail-report-42')).toBeNull();
    expect(view.getByTestId('report-panes-report-42').props.style.flexDirection).toBe('row');
  });
});
