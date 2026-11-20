/**
 * READY-33 acceptance test for the Expo application configuration.
 *
 * `app.json` is a deployment manifest, not a secret store: no key, token,
 * secret or password may be committed, and the identity values must come from
 * `project.json` (UX-01 branding, no credentials).
 */
import appConfig from '../../app.json';

/** Marker terms that must not appear as a key or a value in the manifest. */
const credentialMarkers = ['apiKey', 'token', 'secret', 'password'] as const;

/** Collect every key and string value from a JSON value, lower-cased. */
function collectStrings(value: unknown, into: string[]): string[] {
  if (typeof value === 'string') {
    into.push(value.toLowerCase());
  } else if (Array.isArray(value)) {
    value.forEach((item) => {
      collectStrings(item, into);
    });
  } else if (value !== null && typeof value === 'object') {
    for (const [key, item] of Object.entries(value)) {
      into.push(key.toLowerCase());
      collectStrings(item, into);
    }
  }
  return into;
}

describe('app.json', () => {
  it('contains no credential keys or values', () => {
    const strings = collectStrings(appConfig, []);
    const found = strings.filter((text) =>
      credentialMarkers.some((marker) => text.includes(marker.toLowerCase())),
    );
    expect(found).toEqual([]);
  });

  it('uses the approved identity and placeholder application identifiers', () => {
    expect(appConfig.expo.name).toBe('Aurevia Care');
    expect(appConfig.expo.slug).toBe('aurevia-care');
    expect(appConfig.expo.ios.bundleIdentifier).toBe('com.example.aureviacare');
    expect(appConfig.expo.android.package).toBe('com.example.aureviacare');
  });
});
