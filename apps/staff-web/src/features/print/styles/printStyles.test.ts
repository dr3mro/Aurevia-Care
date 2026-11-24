import { describe, expect, it } from 'vitest';
import { printStyleId, printStylesheet } from './printStyles';

describe('browser print stylesheet', () => {
  it('has a stable element id for the injected stylesheet', () => {
    expect(printStyleId).toBe('orevia-print-preview-styles');
  });

  it('uses A4 paper with 15 mm margins for the browser print path', () => {
    expect(printStylesheet).toContain('@media print');
    expect(printStylesheet).toContain('@page');
    expect(printStylesheet).toContain('size: A4');
    expect(printStylesheet).toContain('margin: 15mm');
  });

  it('hides application chrome and keeps only the handout', () => {
    expect(printStylesheet).toContain('header, nav');
    expect(printStylesheet).toContain('[data-app-chrome]');
    expect(printStylesheet).toContain('[data-print-controls]');
    expect(printStylesheet).toContain('display: none');
    expect(printStylesheet).toContain('[data-print-handout]');
  });
});
