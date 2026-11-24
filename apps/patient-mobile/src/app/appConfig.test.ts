/**
 * READY-33 acceptance test for the Expo application configuration.
 *
 * `app.json` is a deployment manifest, not a secret store: no key, token,
 * secret, password or URL may be committed, and the identity values must come
 * from `project.json` (UX-01 branding, no credentials).
 */
import appConfig from '../../app.json';

/** Marker terms that must not appear as a key or a value in the manifest. */
const credentialMarkers = ['apiKey', 'token', 'secret', 'password'] as const;

/** Collect every key and string value from a JSON value. */
function collectStrings(value: unknown, into: string[]): string[] {
  if (typeof value === 'string') {
    into.push(value);
  } else if (Array.isArray(value)) {
    value.forEach((item) => {
      collectStrings(item, into);
    });
  } else if (value !== null && typeof value === 'object') {
    for (const [key, item] of Object.entries(value)) {
      into.push(key);
      collectStrings(item, into);
    }
  }
  return into;
}

/** Strip separators so `api_key` and `api-key` cannot hide a marker. */
function normalize(text: string): string {
  return text.toLowerCase().replace(/[^a-z0-9]/g, '');
}

describe('app.json', () => {
  it('contains no credential keys or values', () => {
    const found = collectStrings(appConfig, []).filter((text) =>
      credentialMarkers.some((marker) => normalize(text).includes(normalize(marker))),
    );
    expect(found).toEqual([]);
  });

  it('contains no embedded URLs', () => {
    const found = collectStrings(appConfig, []).filter((text) => /[a-z]+:\/\//i.test(text));
    expect(found).toEqual([]);
  });

  it('uses the canonical Aurevia application identifiers', () => {
    expect(appConfig.expo.name).toBe('Aurevia Care');
    expect(appConfig.expo.slug).toBe('aurevia-care');
    expect(appConfig.expo.ios.bundleIdentifier).toBe('com.aurevia.patient');
    expect(appConfig.expo.android.package).toBe('com.aurevia.patient');
  });
});
