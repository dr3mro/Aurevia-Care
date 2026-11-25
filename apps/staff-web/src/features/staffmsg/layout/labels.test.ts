import { describe, expect, it } from 'vitest';
import { layoutKeys, layoutMessages, layoutText, PANE_LABEL_KEYS } from './labels';

describe('layout labels', () => {
  it('keeps identical, non-empty English and Arabic key sets', () => {
    expect(Object.keys(layoutMessages.en).sort()).toEqual(Object.keys(layoutMessages.ar).sort());
    expect(Object.keys(layoutMessages.en).sort()).toEqual([...layoutKeys].sort());
    for (const key of layoutKeys) {
      expect(layoutText('en', key).length).toBeGreaterThan(0);
      expect(layoutText('ar', key).length).toBeGreaterThan(0);
    }
  });

  it('names every pane in both locales', () => {
    expect(layoutText('en', PANE_LABEL_KEYS.auxiliary)).toBe('Navigation');
    expect(layoutText('ar', PANE_LABEL_KEYS.list)).toBe('القائمة');
    expect(layoutText('en', PANE_LABEL_KEYS.detail)).toBe('Detail');
    expect(layoutText('ar', 'composerPane')).toBe('كتابة');
  });
});
