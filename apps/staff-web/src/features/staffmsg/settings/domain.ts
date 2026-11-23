/**
 * Presentation model and pure rules for per-staff notification preferences
 * (MSG-06, WEB-09). The values are synthetic and never leave this module: a
 * later, separately authorized story owns the HTTP route and client.
 */

/** A recurring daily quiet window in whole hours, `0` through `23`. */
export interface DoNotDisturbWindow {
  startHour: number;
  endHour: number;
}

/**
 * The settings a staff member can edit. Sound is an explicit opt-in so a saved
 * preference can never make a workstation noisy without a deliberate act.
 */
export interface NotificationSettings {
  muted: boolean;
  doNotDisturb: DoNotDisturbWindow;
  outOfOfficeEnabled: boolean;
  outOfOfficeReply: string;
  delegateSubject: string;
  soundEnabled: boolean;
}

/** The machine-readable reason a settings edit cannot be saved. */
export type SettingsErrorKey = 'delegateRequired';

/** Hours wrap at midnight; the divisor keeps a circular window to one branch. */
const HOURS_PER_DAY = 24;

/** Build the concealed default: available, silent and with no delegation. */
export function defaultNotificationSettings(): NotificationSettings {
  return {
    muted: false,
    doNotDisturb: { startHour: 18, endHour: 8 },
    outOfOfficeEnabled: false,
    outOfOfficeReply: '',
    delegateSubject: '',
    soundEnabled: false,
  };
}

/**
 * Render an unread count as a bounded badge label.
 *
 * @param count number reported by the inbox; negative input is treated as none
 * @returns an empty string when hidden, exact digits below 100, otherwise `99+`
 */
export function formatUnreadCount(count: number): string {
  if (count <= 0) {
    return '';
  }
  if (count >= 100) {
    return '99+';
  }
  return String(count);
}

/**
 * Validate an edit at the trust boundary of the form.
 *
 * A delegate is required only while out of office is on; keeping an old delegate
 * while off is harmless and must not block a save. The rule mirrors the server's
 * out-of-office invariant without assuming a route exists yet.
 *
 * @param settings the currently typed values
 * @returns the error key, or `undefined` when the edit may be submitted
 */
export function validateSettings(settings: NotificationSettings): SettingsErrorKey | undefined {
  if (settings.outOfOfficeEnabled && settings.delegateSubject.trim() === '') {
    return 'delegateRequired';
  }
  return undefined;
}

/**
 * Decide whether an hour is inside a recurring quiet window.
 *
 * A zero-length circular interval covers the whole day: this matches
 * `NotificationContract.DndWindow.contains` on the API, so a UI preview cannot
 * disagree with what the service will suppress. Modular arithmetic handles a
 * window that crosses midnight without a second branch.
 *
 * @param window recurring window, start inclusive and end exclusive
 * @param hour hour of day in the facility's local time
 * @returns `true` when notifications would be quiet at that hour
 */
export function isDoNotDisturbActive(window: DoNotDisturbWindow, hour: number): boolean {
  const duration = (window.endHour - window.startHour + HOURS_PER_DAY) % HOURS_PER_DAY;
  if (duration === 0) {
    return true;
  }
  const offset = (hour - window.startHour + HOURS_PER_DAY) % HOURS_PER_DAY;
  return offset < duration;
}
