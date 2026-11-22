import { useBreakpoint } from '../theme/useBreakpoint';

/** Props for a bidirectional clinical label. */
export interface MixedScriptTextProps {
  text: string;
  lang: string;
}

/**
 * Isolate a mixed-script run so surrounding direction cannot reorder it.
 *
 * The `<bdi>` keeps its `dir="auto"` and `lang` at every breakpoint: width is a
 * layout input only and never changes the clinical text or its isolation
 * (WEB-07, UX-01). Long English and Arabic values wrap instead of clipping or
 * forcing a page-level horizontal scroll, and text scales with the reader's
 * preference because no fixed block size or font size is set. `responsive.css`
 * has no dedicated clinical-value class, so wrapping uses logical inline
 * styles; the active band is exposed as `data-breakpoint` for layout checks.
 */
export function MixedScriptText({ text, lang }: MixedScriptTextProps) {
  const breakpoint = useBreakpoint();
  return (
    <bdi
      lang={lang}
      dir="auto"
      data-breakpoint={breakpoint}
      style={{
        minInlineSize: 0,
        overflowWrap: 'anywhere',
        whiteSpace: 'normal',
      }}
    >
      {text}
    </bdi>
  );
}
