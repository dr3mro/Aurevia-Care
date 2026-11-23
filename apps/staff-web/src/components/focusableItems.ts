/**
 * List the native controls inside a modal container in DOM order. A detached
 * container yields no items, so a focus-trap handler can never dereference a
 * ref that was cleared while the dialog was open.
 *
 * @param container - The modal element, or null while it is not mounted.
 * @param selector - Selector matching the native controls that are tab stops.
 * @returns The focusable controls, in document order.
 */
export function focusableItems(container: HTMLElement | null, selector: string): HTMLElement[] {
  return container === null ? [] : Array.from(container.querySelectorAll<HTMLElement>(selector));
}
