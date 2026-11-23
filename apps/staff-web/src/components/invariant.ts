/**
 * Narrow a value that the surrounding code guarantees is present. Throws a
 * descriptive error instead of letting a missing value surface later as an
 * opaque `undefined` access.
 *
 * @param value - The possibly-missing value.
 * @param what - Short description used in the error message.
 * @returns The same value, typed as present.
 */
export function defined<T>(value: T | null | undefined, what: string): T {
  if (value === null || value === undefined) {
    throw new Error(`Expected ${what} to be present`);
  }
  return value;
}
