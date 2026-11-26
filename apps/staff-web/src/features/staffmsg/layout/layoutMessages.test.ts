import { describe, expect, it } from 'vitest';
import { layoutKeys, layoutMessages, layoutText } from './layoutMessages';

describe('staff message layout labels', () => {
  it('keeps the same keys in both locale bundles', () => {
    expect([...layoutKeys].sort()).toEqual(Object.keys(layoutMessages.en).sort());
    expect(Object.keys(layoutMessages.ar).sort()).toEqual(Object.keys(layoutMessages.en).sort());
  });

  it('returns the English layout labels', () => {
    expect(layoutText('en', 'layout')).toBe('Messages and mail layout');
    expect(layoutText('en', 'backToList')).toBe('Back to conversations');
    expect(layoutText('en', 'listPane')).toBe('Conversations and folders');
    expect(layoutText('en', 'detailPane')).toBe('Selected conversation');
    expect(layoutText('en', 'composerPane')).toBe('Message composer');
  });

  it('returns the Arabic layout labels', () => {
    expect(layoutText('ar', 'layout')).toBe('تخطيط الرسائل والبريد');
    expect(layoutText('ar', 'backToList')).toBe('العودة إلى المحادثات');
    expect(layoutText('ar', 'listPane')).toBe('المحادثات والمجلدات');
    expect(layoutText('ar', 'detailPane')).toBe('المحادثة المحددة');
    expect(layoutText('ar', 'composerPane')).toBe('محرر الرسالة');
  });
});
