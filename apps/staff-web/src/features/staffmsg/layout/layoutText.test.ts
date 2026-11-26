import { describe, expect, it } from 'vitest';
import { layoutMessages, layoutText } from './layoutText';

describe('layout labels', () => {
  it('resolves an English and an Arabic back action', () => {
    expect(layoutText('en', 'back')).toBe('Back to list');
    expect(layoutText('ar', 'back')).toBe('العودة إلى القائمة');
  });

  it('resolves the pane and composer region names in both locales', () => {
    expect(layoutText('en', 'listPane')).toBe('List');
    expect(layoutText('en', 'detailPane')).toBe('Detail');
    expect(layoutText('en', 'auxiliaryPane')).toBe('Folders');
    expect(layoutText('en', 'composer')).toBe('Compose');
    expect(layoutText('en', 'panes')).toBe('Message panes');
    expect(layoutText('ar', 'listPane')).toBe('القائمة');
    expect(layoutText('ar', 'detailPane')).toBe('التفاصيل');
    expect(layoutText('ar', 'auxiliaryPane')).toBe('المجلدات');
    expect(layoutText('ar', 'composer')).toBe('الإنشاء');
    expect(layoutText('ar', 'panes')).toBe('لوحات الرسائل');
  });

  it('keeps identical non-empty key sets in both locales', () => {
    const english = Object.keys(layoutMessages.en).sort();
    const arabic = Object.keys(layoutMessages.ar).sort();
    expect(english).toEqual(arabic);
    for (const key of english) {
      expect(layoutMessages.en[key as keyof typeof layoutMessages.en].length).toBeGreaterThan(0);
      expect(layoutMessages.ar[key as keyof typeof layoutMessages.ar].length).toBeGreaterThan(0);
    }
  });
});
