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
  'appointments', // s_g3_01 - PAT-01
  'reports', // s_g3_02 - PAT-02
  'enrollment', // s_g3_03 - PAT-03
  'units', // s_g3_04 - MSG-01
  'conversations', // s_g3_05 - MSG-02
  'attachments', // s_g3_06 - MSG-03
  'instructions', // s_g3_07 - MSG-04
  'acknowledgments', // s_g3_08 - MSG-05
  'service-profiles', // s_g3_09 - MSG-06
  'governance', // s_g3_10 - MSG-07
  'flows', // s_g3_11 - PAT-01..03 / MSG-01..07 / UX-01
  'evidence', // s_g3_12 - G3
  'notifications', // PAT-01, MSG-05 - push notification domain
  'prescriptions', // RX-02, PAT-02 - prescription status domain
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
