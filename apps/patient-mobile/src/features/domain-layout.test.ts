/**
 * Structural invariant for the patient-mobile feature layout.
 *
 * CODING_STYLE.md requires business-domain package names and forbids naming a
 * package after a story or task ID. All 12 feature folders previously used
 * `s_g3_XX` story IDs; this test records the owner-approved domain mapping and
 * fails if a story-ID folder is reintroduced. It is a test file, so it is
 * excluded from the module's all-source coverage scope.
 */
import * as fs from 'node:fs';
import * as path from 'node:path';

/** Owner-approved old-to-new mapping for AUD-20261119-04, ordered by story number. */
const DOMAIN_FOLDERS = [
  'appointments',
  'reports',
  'enrollment',
  'units',
  'conversations',
  'attachments',
  'instructions',
  'acknowledgments',
  'service-profiles',
  'governance',
  'flows',
  'evidence',
  'notifications',
  'prescriptions',
  'tracker',
] as const;

/** A story/task ID directory name such as `s_g3_02`. */
const STORY_ID_DIRECTORY = /^s_g\d+_\d+$/;

function featureDirectories(): string[] {
  return fs
    .readdirSync(__dirname, { withFileTypes: true })
    .filter((entry) => entry.isDirectory())
    .map((entry) => entry.name);
}

describe('patient-mobile feature layout', () => {
  it('names every feature directory after its business domain', () => {
    expect([...featureDirectories()].sort()).toEqual([...DOMAIN_FOLDERS].sort());
  });

  it('contains no story-ID-named feature directory', () => {
    expect(featureDirectories().filter((name) => STORY_ID_DIRECTORY.test(name))).toEqual([]);
  });

  it('exposes the entry point of each renamed domain', () => {
    for (const domain of DOMAIN_FOLDERS) {
      const files = fs.readdirSync(path.join(__dirname, domain));
      expect(files.length).toBeGreaterThan(0);
    }
  });
});
