import type { CSSProperties } from 'react';
import type { ThemeTokens } from './tokens';

/**
 * The shared directional offset style, expressed only with CSS logical
 * properties. Each side is mirrored by the browser when the document direction
 * is rtl, so a component never has to branch on direction or set a physical
 * side (UX-01, RTL section 1.1).
 */
export type LogicalLayoutStyle = Pick<
  CSSProperties,
  | 'marginInlineStart'
  | 'marginInlineEnd'
  | 'paddingInlineStart'
  | 'paddingInlineEnd'
  | 'insetInlineStart'
  | 'insetInlineEnd'
  | 'borderInlineStart'
  | 'textAlign'
>;

/**
 * Build the content-offset style for the active theme. Spacing and the border
 * colour come from the theme tokens; the property names stay logical in both
 * directions and in both modes.
 */
export function logicalContentOffset(tokens: ThemeTokens): LogicalLayoutStyle {
  return {
    marginInlineStart: '0',
    marginInlineEnd: tokens.spacing.md,
    paddingInlineStart: tokens.spacing.md,
    paddingInlineEnd: tokens.spacing.md,
    insetInlineStart: '0',
    insetInlineEnd: 'auto',
    borderInlineStart: `1px solid ${tokens.border}`,
    textAlign: 'start',
  };
}
