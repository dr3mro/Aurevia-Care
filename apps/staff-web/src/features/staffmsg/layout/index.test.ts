import { describe, expect, it } from 'vitest';
import * as layout from './index';

describe('messaging layout public surface', () => {
  it('exports the wrapper, navigation hook, labels and pure layout helpers', () => {
    expect(typeof layout.MessagingLayout).toBe('function');
    expect(typeof layout.useMessagingNavigation).toBe('function');
    expect(layout.layoutText('en', 'back')).toBe('Back to list');
    expect(layout.columnsFor('medium', true)).toBe('minmax(0, 1fr) minmax(0, 1fr)');
    expect(layout.paneVisibility('expanded', 'list').auxiliary).toBe(true);
  });
});
