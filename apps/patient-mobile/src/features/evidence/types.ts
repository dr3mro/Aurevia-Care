/** Type-only contracts for the synthetic UX-01 accessibility evidence slice. */

/** Supported patient-app locales (UX-01). */
export type UiLocale = 'en' | 'ar';

/** Layout direction for the current locale. */
export type UiDirection = 'ltr' | 'rtl';

/** Accessibility roles used by the patient app's interactive elements. */
export type PatientAccessibilityRole =
  | 'button'
  | 'link'
  | 'header'
  | 'text'
  | 'image'
  | 'adjustable'
  | 'checkbox'
  | 'radio'
  | 'switch'
  | 'search'
  | 'alert';

/** Stable machine-readable failure codes produced by the evaluator. */
export type AccessibilityFailureCode =
  | 'MISSING_LABEL'
  | 'MISSING_ROLE'
  | 'SCREEN_READER_FLOW_MISSING'
  | 'RTL_UNSUPPORTED'
  | 'DIRECTION_MISMATCH'
  | 'DYNAMIC_TYPE_UNSUPPORTED';

/** One typed UI element descriptor evaluated for UX-01 conformance. */
export interface UiElementDescriptor {
  /** Opaque flow-named identifier, never a patient identifier. */
  readonly id: string;
  /** Localized accessible name; blank is legal only for non-interactive text. */
  readonly accessibilityLabel: string;
  /** Explicit accessibility role; null is legal only for non-interactive text. */
  readonly accessibilityRole: PatientAccessibilityRole | null;
  /** Whether the element accepts user interaction. */
  readonly interactive: boolean;
  /** Whether the element supports right-to-left layout. */
  readonly supportsRtl: boolean;
  /** Current layout direction. */
  readonly direction: UiDirection;
  /** Whether the element is exposed in the native screen-reader flow. */
  readonly screenReaderFlow: boolean;
  /** Whether text scaling (dynamic type) is enabled. */
  readonly dynamicType: boolean;
  /** Locale of the element's rendered content. */
  readonly locale: UiLocale;
}

/** One UX-01 conformance failure on a single element. */
export interface AccessibilityFailure {
  /** Identifier of the failing element. */
  readonly elementId: string;
  /** Stable failure code. */
  readonly code: AccessibilityFailureCode;
  /** Deterministic human-readable detail. */
  readonly detail: string;
}

/** Deterministic UX-01 evidence report over a set of elements. */
export interface AccessibilityEvidenceReport {
  /** True only when no failures were found. */
  readonly passed: boolean;
  /** Number of elements evaluated. */
  readonly checked: number;
  /** Failures in descriptor order, then rule order. */
  readonly failures: readonly AccessibilityFailure[];
}
