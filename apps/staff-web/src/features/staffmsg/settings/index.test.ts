import { readFileSync, readdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import * as settings from './index';

describe('staff messaging settings feature exports', () => {
  it('exports only the public components, domain helpers and message bundle', () => {
    expect(Object.keys(settings).sort()).toEqual(
      [
        'SettingsPage',
        'UnreadBadge',
        'defaultNotificationSettings',
        'formatUnreadCount',
        'isDoNotDisturbActive',
        'settingsKeys',
        'settingsMessages',
        'settingsText',
        'unreadBadgeLabel',
        'validateSettings',
      ].sort(),
    );
  });
});

describe('RTL-safe layout', () => {
  it('uses logical styling properties in every feature source file', () => {
    const featureDir = resolve(dirname(fileURLToPath(import.meta.url)));
    const sourceFiles = readdirSync(featureDir).filter(
      (name) =>
        (name.endsWith('.tsx') || name.endsWith('.ts')) &&
        !name.includes('.test.') &&
        name !== 'fixtures.ts',
    );
    expect(sourceFiles.length).toBeGreaterThan(0);
    for (const name of sourceFiles) {
      const source = readFileSync(`${featureDir}/${name}`, 'utf8');
      expect(source).not.toMatch(
        /margin(Left|Right)|padding(Left|Right)|border(Left|Right)|textAlign:\s*['"](?:left|right)['"]/,
      );
    }
  });
});
