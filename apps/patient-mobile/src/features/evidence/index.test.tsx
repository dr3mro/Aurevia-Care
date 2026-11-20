import { render } from '@testing-library/react-native';

import {
  evaluateAccessibility,
  evidenceFailureText,
  evidenceSummaryLabel,
} from './accessibility';
import { AccessibilityEvidenceSummary } from './AccessibilityEvidenceSummary';
import {
  AR_BUTTON,
  CONFORMING_ELEMENTS,
  EN_BUTTON,
  MIXED_SCRIPT_LABEL,
  STATIC_TEXT,
} from './fixtures';
import type { UiElementDescriptor } from './types';

describe('UX-01 accessibility evidence evaluator', () => {
  it('passes an empty descriptor set with zero checked and no failures', () => {
    expect(evaluateAccessibility([])).toEqual({ passed: true, checked: 0, failures: [] });
  });

  it('passes conforming English and Arabic interactive and static descriptors', () => {
    const report = evaluateAccessibility(CONFORMING_ELEMENTS);
    expect(report.passed).toBe(true);
    expect(report.checked).toBe(3);
    expect(report.failures).toEqual([]);
  });

  it('reports MISSING_LABEL for a blank interactive label', () => {
    const element: UiElementDescriptor = { ...EN_BUTTON, accessibilityLabel: '   ' };
    expect(evaluateAccessibility([element]).failures.map((f) => f.code)).toContain('MISSING_LABEL');
  });

  it('reports MISSING_ROLE when an interactive element has no accessibilityRole', () => {
    const element: UiElementDescriptor = { ...EN_BUTTON, accessibilityRole: null };
    expect(evaluateAccessibility([element]).failures.map((f) => f.code)).toContain('MISSING_ROLE');
  });

  it('reports SCREEN_READER_FLOW_MISSING when an interactive element is not in the screen-reader flow', () => {
    const element: UiElementDescriptor = { ...EN_BUTTON, screenReaderFlow: false };
    expect(evaluateAccessibility([element]).failures.map((f) => f.code)).toContain(
      'SCREEN_READER_FLOW_MISSING',
    );
  });

  it('reports RTL_UNSUPPORTED for an Arabic element that does not support RTL', () => {
    const element: UiElementDescriptor = { ...AR_BUTTON, supportsRtl: false };
    expect(evaluateAccessibility([element]).failures.map((f) => f.code)).toContain('RTL_UNSUPPORTED');
  });

  it('reports DIRECTION_MISMATCH for both locales', () => {
    const enWrong: UiElementDescriptor = { ...EN_BUTTON, direction: 'rtl' };
    const arWrong: UiElementDescriptor = { ...AR_BUTTON, direction: 'ltr' };
    const failures = evaluateAccessibility([enWrong, arWrong]).failures;
    expect(failures.filter((f) => f.code === 'DIRECTION_MISMATCH')).toHaveLength(2);
  });

  it('reports DYNAMIC_TYPE_UNSUPPORTED when text scaling is disabled', () => {
    const element: UiElementDescriptor = { ...EN_BUTTON, dynamicType: false };
    expect(evaluateAccessibility([element]).failures.map((f) => f.code)).toContain(
      'DYNAMIC_TYPE_UNSUPPORTED',
    );
  });

  it('reports every applicable failure on one element and lists codes in order', () => {
    const element: UiElementDescriptor = {
      ...EN_BUTTON,
      accessibilityLabel: ' ',
      accessibilityRole: null,
      screenReaderFlow: false,
      supportsRtl: false,
      direction: 'rtl',
      dynamicType: false,
    };
    const report = evaluateAccessibility([element]);
    expect(report.passed).toBe(false);
    expect(report.failures.map((f) => f.code)).toEqual([
      'MISSING_LABEL',
      'MISSING_ROLE',
      'SCREEN_READER_FLOW_MISSING',
      'DIRECTION_MISMATCH',
      'DYNAMIC_TYPE_UNSUPPORTED',
    ]);
    expect(report.failures.every((f) => f.elementId === EN_BUTTON.id)).toBe(true);
    expect(report.failures.every((f) => f.detail.startsWith(EN_BUTTON.id + ': '))).toBe(true);
  });
});

describe('UX-01 synthetic fixtures', () => {
  it('uses synthetic bilingual mixed-script labels only', () => {
    expect(MIXED_SCRIPT_LABEL).toContain('تحديد');
    expect(AR_BUTTON.accessibilityLabel).toBe(MIXED_SCRIPT_LABEL);
    expect(EN_BUTTON.locale).toBe('en');
    expect(STATIC_TEXT.interactive).toBe(false);
  });
});

describe('UX-01 accessible evidence strings', () => {
  it('formats a passing summary label', () => {
    expect(evidenceSummaryLabel(evaluateAccessibility(CONFORMING_ELEMENTS))).toBe(
      'Accessibility evidence PASS: 3 elements checked, 0 failures',
    );
  });

  it('formats a failing summary label', () => {
    expect(evidenceSummaryLabel(evaluateAccessibility([]))).toBe(
      'Accessibility evidence PASS: 0 elements checked, 0 failures',
    );
    const report = evaluateAccessibility([{ ...EN_BUTTON, dynamicType: false }]);
    expect(evidenceSummaryLabel(report)).toBe(
      'Accessibility evidence FAIL: 1 elements checked, 1 failures',
    );
  });

  it('formats failure details as a stable semicolon-separated list', () => {
    const report = evaluateAccessibility([{ ...EN_BUTTON, dynamicType: false },
      { ...AR_BUTTON, screenReaderFlow: false }]);
    expect(evidenceFailureText(report)).toBe(
      'pat01-appointments-open DYNAMIC_TYPE_UNSUPPORTED; msg03-send-file SCREEN_READER_FLOW_MISSING',
    );
  });

  it('formats an empty failure detail as "No failures"', () => {
    expect(evidenceFailureText(evaluateAccessibility(CONFORMING_ELEMENTS))).toBe('No failures');
  });
});

describe('AccessibilityEvidenceSummary component', () => {
  it('renders a labelled, role-tagged, accessible passing summary with testIDs', async () => {
    const report = evaluateAccessibility(CONFORMING_ELEMENTS);
    const view = await render(<AccessibilityEvidenceSummary report={report} isRTL={false} />);
    expect(view.getByTestId('a11y-evidence-summary')).toBeTruthy();
    expect(view.getByTestId('a11y-evidence-status').props.accessibilityRole).toBe('text');
    expect(view.getByTestId('a11y-evidence-status').props.accessibilityLabel).toBe(
      evidenceSummaryLabel(report),
    );
    expect(view.getByLabelText(evidenceSummaryLabel(report))).toBeTruthy();
    expect(view.getByTestId('a11y-evidence-title').props.accessibilityRole).toBe('header');
    expect(view.getByTestId('a11y-evidence-detail').props.accessibilityLabel).toBe('No failures');
  });

  it('renders failure codes and details for a failing report', async () => {
    const report = evaluateAccessibility([{ ...EN_BUTTON, dynamicType: false }]);
    const view = await render(<AccessibilityEvidenceSummary report={report} isRTL={false} />);
    expect(view.getByTestId('a11y-evidence-status').props.accessibilityLabel).toContain('FAIL');
    expect(view.getByTestId('a11y-evidence-detail').props.accessibilityLabel).toContain(
      'DYNAMIC_TYPE_UNSUPPORTED',
    );
  });

  it('uses an LTR row layout for English', async () => {
    const view = await render(
      <AccessibilityEvidenceSummary report={evaluateAccessibility([])} isRTL={false} />,
    );
    expect(view.getByTestId('a11y-evidence-summary').props.style.flexDirection).toBe('row');
  });

  it('mirrors the row layout for RTL/Arabic', async () => {
    const view = await render(
      <AccessibilityEvidenceSummary report={evaluateAccessibility([])} isRTL={true} />,
    );
    expect(view.getByTestId('a11y-evidence-summary').props.style.flexDirection).toBe('row-reverse');
  });
});
