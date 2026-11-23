import { Children, isValidElement } from 'react';
import type { CSSProperties, ReactNode, SubmitEvent } from 'react';
import { Button } from './Button';
import { FormField } from './FormField';
import { useBreakpoint } from '../theme/useBreakpoint';

/** Props for a labelled form with an explicit submit action. */
export interface FormProps {
  label: string;
  submitLabel: string;
  onSubmit: () => void;
  children: ReactNode;
}

/** Native controls that are data-entry fields even when no FormField wraps them. */
const FIELD_TAGS = new Set(['input', 'select', 'textarea']);

/**
 * Count real data-entry fields, not top-level children. Callers wrap fields in
 * layout elements and place summaries or headings beside them, so a flat child
 * count would both miss wrapped fields and count prose as a field. Wrappers are
 * traversed; summaries, headings and layout text are never counted.
 */
function countFields(children: ReactNode): number {
  let count = 0;
  Children.forEach(children, (child) => {
    if (!isValidElement(child)) {
      return;
    }
    if (
      child.type === FormField ||
      (typeof child.type === 'string' && FIELD_TAGS.has(child.type))
    ) {
      count += 1;
      return;
    }
    count += countFields((child.props as { children?: ReactNode }).children);
  });
  return count;
}

/**
 * A labelled form that never navigates; the caller owns the command.
 *
 * The layout is presentation only and never hides a field. Fields stack in a
 * single column on compact viewports and use the shared two-column grid from
 * the medium breakpoint when the form holds more than six real fields. On
 * phones the submit bar is a sticky footer with a logical scroll-space
 * reservation so it cannot cover the focused field. Side-aware styles are
 * logical properties only, so the same DOM and focus order mirror under RTL
 * (WEB-07, UX-01).
 */
export function Form({ label, submitLabel, onSubmit, children }: FormProps) {
  const breakpoint = useBreakpoint();
  const twoColumns = breakpoint !== 'compact' && countFields(children) > 6;

  // The stylesheet has no form-specific scroll class; reserve room for the
  // sticky footer with a logical property rather than adding a stylesheet rule.
  const formStyle: CSSProperties = {
    scrollPaddingBlockEnd: 'var(--sc-space-8, 32px)',
  };

  // Grid lets the single submit button stretch to the .touch wrapper's target
  // floor (44px for coarse pointers) without changing the Button component.
  const actionsStyle: CSSProperties =
    breakpoint === 'compact'
      ? {
          display: 'grid',
          position: 'sticky',
          insetBlockEnd: 0,
          paddingBlock: 'var(--sc-space-2, 8px)',
          paddingInline: 'var(--sc-space-4, 16px)',
          background: 'var(--sc-color-surface-default, #ffffff)',
        }
      : { display: 'grid' };

  return (
    <form
      aria-label={label}
      className="page"
      data-breakpoint={breakpoint}
      style={formStyle}
      onSubmit={(event: SubmitEvent<HTMLFormElement>) => {
        event.preventDefault();
        onSubmit();
      }}
    >
      <div
        className={twoColumns ? 'grid-2' : 'stack'}
        data-part="fields"
        data-layout={twoColumns ? 'two-column' : 'single-column'}
      >
        {children}
      </div>
      <div className="touch" data-part="actions" style={actionsStyle}>
        <Button type="submit">{submitLabel}</Button>
      </div>
    </form>
  );
}
