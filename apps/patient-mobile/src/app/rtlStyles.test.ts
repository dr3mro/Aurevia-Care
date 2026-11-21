/**
 * UX-01 structural guard for the patient app shell (RTL and localization,
 * section 9.2). Physical margin, padding, border, inset and text-align values
 * mirror inconsistently between iOS and Android, so a build must fail when one
 * is introduced into a production style. Logical (`*Start` / `*End`) and flex
 * values are the supported equivalents.
 *
 * Test-only: this file reads source text and adds no production branch.
 */
import * as fs from 'node:fs';
import * as path from 'node:path';

const PHYSICAL_SPACING =
  /\b(?:marginLeft|marginRight|paddingLeft|paddingRight|borderLeftWidth|borderRightWidth|borderLeftColor|borderRightColor)\s*:/;
const PHYSICAL_INSET = /\b(?:left|right)\s*:\s*(?:-?\d|['"])/;
const PHYSICAL_TEXT_ALIGN = /textAlign\s*:\s*['"](?:left|right)['"]/;
const SHRINKING_TEXT =
  /maxFontSizeMultiplier\s*:\s*(?:[01](?:\.\d+)?)\b|adjustsFontSizeToFit\s*:\s*true/;

/** Production source files in the patient app shell, excluding their tests. */
function productionSources(): readonly { readonly name: string; readonly text: string }[] {
  return fs
    .readdirSync(__dirname)
    .filter((name) => /\.tsx?$/.test(name) && !name.includes('.test.'))
    .map((name) => ({ name, text: fs.readFileSync(path.join(__dirname, name), 'utf8') }));
}

describe('patient app shell RTL and large-text style guard', () => {
  const sources = productionSources();

  it('finds production sources to guard', () => {
    expect(sources.length).toBeGreaterThan(0);
  });

  it.each(sources)('uses no physical, non-mirrored style property in $name', ({ text }) => {
    expect(PHYSICAL_SPACING.test(text)).toBe(false);
    expect(PHYSICAL_INSET.test(text)).toBe(false);
    expect(PHYSICAL_TEXT_ALIGN.test(text)).toBe(false);
  });

  it.each(sources)('never shrinks clinical text to fit in $name', ({ text }) => {
    expect(SHRINKING_TEXT.test(text)).toBe(false);
  });
});
