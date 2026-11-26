import { readFileSync, readdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, expectTypeOf, it } from 'vitest';
import * as layout from './index';
import { MessageLayout } from './MessageLayout';
import { MessagingLayout, useMessagingNavigation } from './MessagingLayout';
import { StaffMessageLayout } from './StaffMessageLayout';
import {
  layoutKeys,
  layoutMessages as sourceMessageLayoutMessages,
  layoutText as sourceMessageLayoutText,
} from './layoutMessages';
import { columnsFor, paneVisibility } from './layoutModel';
import { paneLayout } from './layoutStyles';
import {
  layoutMessages as sourceLayoutMessages,
  layoutText as sourceLayoutText,
} from './layoutText';
import {
  layoutMessages as sourceStaffMessageLayoutMessages,
  layoutText as sourceStaffMessageLayoutText,
} from './labels';
import type * as MessageLayoutTypes from './MessageLayout';
import type * as MessagingLayoutTypes from './MessagingLayout';
import type * as StaffMessageLayoutTypes from './StaffMessageLayout';
import type * as LayoutMessagesTypes from './layoutMessages';
import type * as LayoutLabelsTypes from './labels';
import type * as LayoutModelTypes from './layoutModel';
import type * as LayoutStylesTypes from './layoutStyles';
import type * as LayoutTextTypes from './layoutText';

describe('messaging layout public surface', () => {
  it('exports both wrappers, navigation, labels and pure layout helpers', () => {
    expect(Object.keys(layout).sort()).toEqual(
      [
        'MessageLayout',
        'MessagingLayout',
        'StaffMessageLayout',
        'columnsFor',
        'layoutKeys',
        'layoutMessages',
        'layoutText',
        'messageLayoutMessages',
        'messageLayoutText',
        'paneLayout',
        'paneVisibility',
        'staffMessageLayoutMessages',
        'staffMessageLayoutText',
        'useMessagingNavigation',
      ].sort(),
    );
  });

  it('re-exports each runtime value from its owning module', () => {
    expect(layout.MessageLayout).toBe(MessageLayout);
    expect(layout.MessagingLayout).toBe(MessagingLayout);
    expect(layout.StaffMessageLayout).toBe(StaffMessageLayout);
    expect(layout.useMessagingNavigation).toBe(useMessagingNavigation);
    expect(layout.layoutKeys).toBe(layoutKeys);
    expect(layout.layoutMessages).toBe(sourceLayoutMessages);
    expect(layout.layoutText).toBe(sourceLayoutText);
    expect(layout.messageLayoutMessages).toBe(sourceMessageLayoutMessages);
    expect(layout.messageLayoutText).toBe(sourceMessageLayoutText);
    expect(layout.staffMessageLayoutMessages).toBe(sourceStaffMessageLayoutMessages);
    expect(layout.staffMessageLayoutText).toBe(sourceStaffMessageLayoutText);
    expect(layout.paneLayout).toBe(paneLayout);
    expect(layout.columnsFor).toBe(columnsFor);
    expect(layout.paneVisibility).toBe(paneVisibility);
  });

  it('preserves every public type from its owning module', () => {
    expectTypeOf<layout.LayoutShape>().toEqualTypeOf<LayoutStylesTypes.LayoutShape>();
    expectTypeOf<layout.MessageLayoutMessageKey>().toEqualTypeOf<LayoutMessagesTypes.LayoutMessageKey>();
    expectTypeOf<layout.MessageLayoutProps>().toEqualTypeOf<MessageLayoutTypes.MessageLayoutProps>();
    expectTypeOf<layout.StaffMessageLayoutProps>().toEqualTypeOf<StaffMessageLayoutTypes.StaffMessageLayoutProps>();
    expectTypeOf<layout.StaffMessageLayoutMessageKey>().toEqualTypeOf<
      'back' | 'listPane' | 'detailPane' | 'composerPane'
    >();
    expectTypeOf<layout.StaffMessageLayoutMessageKey>().toEqualTypeOf<LayoutLabelsTypes.LayoutMessageKey>();
    expectTypeOf<layout.LayoutMessageKey>().toEqualTypeOf<LayoutTextTypes.LayoutMessageKey>();
    expectTypeOf<layout.MessagingLayoutProps>().toEqualTypeOf<MessagingLayoutTypes.MessagingLayoutProps>();
    expectTypeOf<layout.MessagingNavigation>().toEqualTypeOf<MessagingLayoutTypes.MessagingNavigation>();
    expectTypeOf<layout.MessagingPane>().toEqualTypeOf<MessagingLayoutTypes.MessagingPane>();
    expectTypeOf<layout.MessagingStage>().toEqualTypeOf<LayoutModelTypes.MessagingStage>();
    expectTypeOf<layout.PaneId>().toEqualTypeOf<LayoutModelTypes.PaneId>();
    expectTypeOf<layout.PaneLayout>().toEqualTypeOf<LayoutStylesTypes.PaneLayout>();
    expectTypeOf<layout.PanePlacement>().toEqualTypeOf<LayoutStylesTypes.PanePlacement>();
    expectTypeOf<layout.PaneVisibility>().toEqualTypeOf<LayoutModelTypes.PaneVisibility>();
  });

  it('preserves the responsive messaging layout behavior', () => {
    expect(typeof layout.MessagingLayout).toBe('function');
    expect(typeof layout.useMessagingNavigation).toBe('function');
    expect(layout.layoutText('en', 'back')).toBe('Back to list');
    expect(layout.layoutText('en', 'listPane')).toBe('List');
    expect(layout.layoutText('en', 'composer')).toBe('Compose');
    expect(layout.columnsFor('medium', true)).toBe('minmax(0, 1fr) minmax(0, 1fr)');
    expect(layout.paneVisibility('expanded', 'list').auxiliary).toBe(true);
  });

  it('preserves the controlled staff-message wrapper and its label bundle', () => {
    expect(typeof layout.StaffMessageLayout).toBe('function');
    expect(layout.layoutKeys).toContain('composerPane');
    expect(layout.staffMessageLayoutMessages.en.listPane).toBe('Conversations and folders');
    expect(layout.staffMessageLayoutText('en', 'composerPane')).toBe('Message composer');
    expect(layout.staffMessageLayoutMessages).not.toBe(layout.layoutMessages);
  });

  it('preserves the message wrapper labels and pane geometry', () => {
    expect(typeof layout.MessageLayout).toBe('function');
    expect(layout.messageLayoutMessages.en.layout).toBe('Messages and mail layout');
    expect(layout.messageLayoutText('en', 'backToList')).toBe('Back to conversations');
    expect(layout.paneLayout('medium').shape).toBe('paired');
  });
});

describe('RTL-safe layout source', () => {
  it('uses logical styling properties in every feature source file', () => {
    const featureDir = resolve(dirname(fileURLToPath(import.meta.url)));
    const sourceFiles = readdirSync(featureDir).filter(
      (name) =>
        (name.endsWith('.tsx') || name.endsWith('.ts')) &&
        !name.includes('.test.') &&
        name !== 'fixtures.ts',
    );
    expect(sourceFiles.length).toBeGreaterThan(0);
    const physical =
      /margin(Left|Right)|padding(Left|Right)|border(Left|Right)|textAlign:\s*['"](?:left|right)['"]|\b(min|max)?Width\s*:|\bbottom\s*:|overflowX\s*:/;
    for (const name of sourceFiles) {
      const source = readFileSync(`${featureDir}/${name}`, 'utf8');
      expect(source).not.toMatch(physical);
    }
  });
});
