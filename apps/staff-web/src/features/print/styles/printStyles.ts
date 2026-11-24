/**
 * Stylesheet applied when the browser prints the page itself, as opposed to
 * downloading the server-rendered handout PDF. It is rendered as a `<style>`
 * element by the preview dialog: CSS in a style element applies to the whole
 * document, so the rules hold even when only part of the page is printed.
 *
 * Paper geometry and chrome removal are presentation only. The handout bytes,
 * language, direction and provenance remain owned by the API (CLN-07).
 */
export const printStylesheet = `@media print {
  @page {
    size: A4;
    margin: 15mm;
  }

  html, body {
    background: #ffffff;
    color: #000000;
  }

  /* Application chrome is never wanted on a patient handout. */
  header, nav, [role="navigation"], [data-app-chrome], [data-print-controls] {
    display: none !important;
  }

  /* The embedded handout is the only visible content. */
  [data-print-handout] {
    display: block !important;
    width: 100% !important;
    height: auto !important;
    border: 0 !important;
  }
}
`;

/** Stable id so the injected stylesheet can be located and asserted. */
export const printStyleId = 'aurevia-print-preview-styles';
