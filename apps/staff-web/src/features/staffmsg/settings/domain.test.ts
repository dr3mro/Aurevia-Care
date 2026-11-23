import { describe, expect, it } from 'vitest';
import {
  defaultNotificationSettings,
  formatUnreadCount,
  isDoNotDisturbActive,
  validateSettings,
} from './domain';
import {
  defaultSettings,
  outOfOfficeSettings,
  quietCurrentHour,
  syntheticDelegate,
} from './fixtures';

/** Build a window with explicit hours so every test names its own boundary. */
function windowAt(startHour: number, endHour: number) {
  return { startHour, endHour };
}

describe('default notification settings', () => {
  it('starts with sounds off, no mute and no out-of-office delegation', () => {
    const settings = defaultNotificationSettings();
    expect(settings.soundEnabled).toBe(false);
    expect(settings.muted).toBe(false);
    expect(settings.outOfOfficeEnabled).toBe(false);
    expect(settings.outOfOfficeReply).toBe('');
    expect(settings.delegateSubject).toBe('');
    expect(settings.doNotDisturb.startHour).toBeGreaterThanOrEqual(0);
    expect(settings.doNotDisturb.endHour).toBeLessThan(24);
  });

  it('returns a fresh object on every call so one caller cannot mutate another', () => {
    const first = defaultNotificationSettings();
    first.soundEnabled = true;
    expect(defaultNotificationSettings().soundEnabled).toBe(false);
    expect(defaultSettings.soundEnabled).toBe(false);
  });
});

describe('formatUnreadCount', () => {
  it('hides zero and negative counts rather than showing a zero badge', () => {
    expect(formatUnreadCount(0)).toBe('');
    expect(formatUnreadCount(-3)).toBe('');
  });

  it('shows the exact digit run for one and for a small count', () => {
    expect(formatUnreadCount(1)).toBe('1');
    expect(formatUnreadCount(7)).toBe('7');
  });

  it('caps large counts at the synthetic 99+ label', () => {
    expect(formatUnreadCount(120)).toBe('99+');
    expect(formatUnreadCount(100)).toBe('99+');
  });
});

describe('validateSettings', () => {
  it('requires a delegate when out of office is enabled and the delegate is blank', () => {
    expect(validateSettings({ ...outOfOfficeSettings, delegateSubject: '' })).toBe(
      'delegateRequired',
    );
    expect(validateSettings({ ...outOfOfficeSettings, delegateSubject: '   ' })).toBe(
      'delegateRequired',
    );
  });

  it('accepts a named delegate when out of office is enabled', () => {
    expect(outOfOfficeSettings.delegateSubject).toBe(syntheticDelegate);
    expect(validateSettings(outOfOfficeSettings)).toBeUndefined();
  });

  it('does not require a delegate when out of office is off', () => {
    expect(validateSettings(defaultSettings)).toBeUndefined();
    expect(validateSettings({ ...defaultSettings, delegateSubject: '' })).toBeUndefined();
  });
});

describe('isDoNotDisturbActive', () => {
  it('treats equal start and end hours as a whole-day quiet window', () => {
    // Matches NotificationContract.DndWindow.contains on the API: a zero-length
    // circular interval covers the whole day rather than disabling quiet hours.
    expect(isDoNotDisturbActive(windowAt(quietCurrentHour, quietCurrentHour), 3)).toBe(true);
    expect(isDoNotDisturbActive(windowAt(quietCurrentHour, quietCurrentHour), 15)).toBe(true);
  });

  it('includes the start hour and excludes the end hour of a same-day window', () => {
    const window = windowAt(9, 17);
    expect(isDoNotDisturbActive(window, 9)).toBe(true);
    expect(isDoNotDisturbActive(window, 12)).toBe(true);
    expect(isDoNotDisturbActive(window, 17)).toBe(false);
    expect(isDoNotDisturbActive(window, 8)).toBe(false);
  });

  it('spans midnight when the start hour is greater than the end hour', () => {
    const window = windowAt(22, 6);
    expect(isDoNotDisturbActive(window, 23)).toBe(true);
    expect(isDoNotDisturbActive(window, 3)).toBe(true);
    expect(isDoNotDisturbActive(window, 12)).toBe(false);
  });
});
