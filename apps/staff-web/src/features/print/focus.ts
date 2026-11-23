/** Native controls only; sheets, iframes and static text are not tab stops. */
const focusableSelector = 'button:not([disabled]), select:not([disabled]), input:not([disabled])';

/**
 * List the focusable controls of a modal container in DOM order.
 *
 * A dialog ref is null before mount and after unmount; Tab can still be
 * dispatched to the document during that window, so the empty list keeps focus
 * containment a no-op instead of throwing.
 *
 * @param container - The rendered dialog element, or null when it is not mounted.
 * @returns Focusable descendants in DOM order, or an empty list before mount.
 */
export function focusableItems(container: HTMLElement | null): HTMLElement[] {
  return Array.from(container?.querySelectorAll<HTMLElement>(focusableSelector) ?? []);
}
