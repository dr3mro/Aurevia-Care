import type {
  AccessibilityEvidenceReport,
  AccessibilityFailure,
  AccessibilityFailureCode,
  UiElementDescriptor,
} from './types';

const FAILURE_DETAIL: Record<AccessibilityFailureCode, string> = {
  MISSING_LABEL: 'interactive element has no accessible name',
  MISSING_ROLE: 'interactive element has no accessibility role',
  SCREEN_READER_FLOW_MISSING: 'element is not exposed to the screen reader',
  RTL_UNSUPPORTED: 'Arabic element does not support RTL layout',
  DIRECTION_MISMATCH: 'layout direction does not match the locale',
  DYNAMIC_TYPE_UNSUPPORTED: 'text scaling is disabled',
};

function failure(elementId: string, code: AccessibilityFailureCode): AccessibilityFailure {
  return { elementId, code, detail: `${elementId}: ${FAILURE_DETAIL[code]}` };
}

function evaluateElement(element: UiElementDescriptor): readonly AccessibilityFailure[] {
  const failures: AccessibilityFailure[] = [];
  if (element.interactive && element.accessibilityLabel.trim() === '') {
    failures.push(failure(element.id, 'MISSING_LABEL'));
  }
  if (element.interactive && element.accessibilityRole === null) {
    failures.push(failure(element.id, 'MISSING_ROLE'));
  }
  if (element.interactive && !element.screenReaderFlow) {
    failures.push(failure(element.id, 'SCREEN_READER_FLOW_MISSING'));
  }
  if (element.locale === 'ar' && !element.supportsRtl) {
    failures.push(failure(element.id, 'RTL_UNSUPPORTED'));
  }
  const expectedDirection = element.locale === 'ar' ? 'rtl' : 'ltr';
  if (element.direction !== expectedDirection) {
    failures.push(failure(element.id, 'DIRECTION_MISMATCH'));
  }
  if (!element.dynamicType) {
    failures.push(failure(element.id, 'DYNAMIC_TYPE_UNSUPPORTED'));
  }
  return failures;
}

/**
 * Evaluate UX-01 native accessibility checks over typed UI descriptors.
 *
 * @param elements descriptors in display order
 * @returns a deterministic report with failures in descriptor then rule order
 */
export function evaluateAccessibility(
  elements: readonly UiElementDescriptor[],
): AccessibilityEvidenceReport {
  const failures = elements.flatMap(evaluateElement);
  return { passed: failures.length === 0, checked: elements.length, failures };
}

/**
 * Build the accessible status label for an evidence report.
 *
 * @param report evidence report to describe
 * @returns a generic, PHI-free status string
 */
export function evidenceSummaryLabel(report: AccessibilityEvidenceReport): string {
  const status = report.passed ? 'PASS' : 'FAIL';
  return `Accessibility evidence ${status}: ${report.checked} elements checked, ${report.failures.length} failures`;
}

/**
 * Build a stable, semicolon-separated failure detail string.
 *
 * @param report evidence report to describe
 * @returns 'No failures' or '<id> <CODE>; ...' in report order
 */
export function evidenceFailureText(report: AccessibilityEvidenceReport): string {
  if (report.failures.length === 0) {
    return 'No failures';
  }
  return report.failures.map((item) => `${item.elementId} ${item.code}`).join('; ');
}
