/** Placeholder values keyed by the name used inside a `{name}` token. */
export type MessageValues = Readonly<Record<string, string>>;

/**
 * Fill named `{placeholder}` tokens in a message template with matching values.
 *
 * The single implementation shared by every feature message module, so a change
 * to the placeholder syntax or escaping is made once and cannot drift between
 * locales or features. A token whose name is absent from `values` is left
 * intact in the output: a missing translation value is visible rather than
 * silently blank. Values are inserted verbatim, so callers pass text that is
 * already localized and safe for display.
 *
 * @param text - message template that may contain `{name}` tokens
 * @param values - substitution values keyed by placeholder name; none by default
 * @returns the template with every known placeholder substituted
 */
export function fillMessagePlaceholders(text: string, values: MessageValues = {}): string {
  return text.replace(/\{(\w+)\}/g, (whole, name: string) => values[name] ?? whole);
}
