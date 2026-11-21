/**
 * English/Arabic message parity for the patient app (UX-01, PAT-01).
 *
 * The patient app keeps its bilingual strings in small per-surface bundles.
 * Each bundle exposes a key list plus a lookup function, so this check
 * materialises both locales through the public API and verifies the
 * localization contract from docs/design/rtl-and-localization.md sections 8
 * and 9.9 (identical key sets and named placeholders), plus two safety rules
 * defined here: no empty values and an Arabic translation for every value
 * that is not an untranslated code.
 *
 * Every failure names the bundle and the key so a reader knows what to fix.
 * This file is a test only: no message bundle is changed here.
 */
import { appMessageKeys, appText } from './messages';
import { attachmentKeys, attachmentText } from '../features/attachments/messages';

type Locale = 'en' | 'ar';
type ValueMap = Readonly<Record<string, string>>;

interface Bundle {
  readonly name: string;
  readonly en: ValueMap;
  readonly ar: ValueMap;
}

/**
 * Exact Arabic-side values that intentionally contain no Arabic letters
 * because they are codes or numbers shared with the English interface.
 * Keep this list short and always say why the value is exempt.
 */
const ALLOWED_UNTRANSLATED_VALUES: ReadonlySet<string> = new Set([
  'SHA-256', // checksum algorithm name: a code, not a translatable word
]);

// Arabic letters only: exclude Arabic-Indic digits (U+0660-U+0669) and
// Arabic punctuation, so a value of only digits or marks is not "translated".
const ARABIC_LETTER = /[\u0621-\u064A\u066E-\u066F\u0671-\u06D3\u06D5\u06FA-\u06FC\u06FF]/;
const PLACEHOLDER = /\{([^{}]+)\}/g;

/** Return the sorted names of every `{name}` placeholder in a value. */
function placeholderNames(value: string): string[] {
  const names: string[] = [];
  for (const token of value.match(PLACEHOLDER) ?? []) {
    names.push(token.slice(1, -1));
  }
  return names.sort();
}

/** Materialise one locale of a bundle through its exported lookup function. */
function mapFor<K extends string>(
  keys: readonly K[],
  text: (locale: Locale, key: K) => string,
  locale: Locale,
): Record<string, string> {
  const values: Record<string, string> = {};
  for (const key of keys) {
    values[key] = text(locale, key);
  }
  return values;
}

/** Collect every parity violation for one bundle. An empty array means parity. */
function checkBundle(bundle: Bundle): string[] {
  const problems: string[] = [];
  const enKeys = Object.keys(bundle.en).sort();
  const arKeys = Object.keys(bundle.ar).sort();

  // Both locales must define exactly the same keys.
  for (const key of enKeys) {
    if (!Object.prototype.hasOwnProperty.call(bundle.ar, key)) {
      problems.push(`${bundle.name}: key "${key}" is missing from Arabic`);
    }
  }
  for (const key of arKeys) {
    if (!Object.prototype.hasOwnProperty.call(bundle.en, key)) {
      problems.push(`${bundle.name}: key "${key}" is missing from English`);
    }
  }

  // No locale may carry an empty or whitespace-only value.
  for (const locale of ['en', 'ar'] as const) {
    const values: ValueMap = locale === 'en' ? bundle.en : bundle.ar;
    for (const key of Object.keys(values)) {
      const value = values[key];
      if (value === undefined || value.trim() === '') {
        problems.push(`${bundle.name}: ${locale} value for "${key}" is empty`);
      }
    }
  }

  // A translation may reorder placeholders but must not add, drop or rename them.
  for (const key of enKeys) {
    const enValue = bundle.en[key];
    const arValue = bundle.ar[key];
    if (enValue === undefined || arValue === undefined) continue;
    const enNames = placeholderNames(enValue);
    const arNames = placeholderNames(arValue);
    if (enNames.join(',') !== arNames.join(',')) {
      problems.push(
        `${bundle.name}: placeholders differ for "${key}" (en: ${enNames.join(', ') || 'none'}, ar: ${arNames.join(', ') || 'none'})`,
      );
    }
  }

  // An Arabic value must carry Arabic letters unless it is an allow-listed code.
  for (const key of arKeys) {
    const value = bundle.ar[key];
    if (value === undefined || value.trim() === '') continue;
    if (ALLOWED_UNTRANSLATED_VALUES.has(value)) continue;
    if (ARABIC_LETTER.test(value)) continue;
    problems.push(`${bundle.name}: Arabic value for "${key}" has no Arabic letters ("${value}")`);
  }

  return problems;
}

/** Every message bundle in apps/patient-mobile/src, read through its public API. */
const REAL_BUNDLES: readonly Bundle[] = [
  {
    name: 'app/messages.ts',
    en: mapFor(appMessageKeys, appText, 'en'),
    ar: mapFor(appMessageKeys, appText, 'ar'),
  },
  {
    name: 'features/attachments/messages.ts',
    en: mapFor(attachmentKeys, attachmentText, 'en'),
    ar: mapFor(attachmentKeys, attachmentText, 'ar'),
  },
];

/** Synthetic, deliberately broken bundle used to prove every rule is enforced. */
const BROKEN_BUNDLE: Bundle = {
  name: 'broken/sample.ts',
  en: {
    greeting: 'Hello {name}',
    onlyEnglish: 'English only',
    code: 'CODE-1',
  },
  ar: {
    greeting: 'مرحبا {other}',
    empty: '',
    code: 'CODE-1',
  },
};

describe('English/Arabic message parity (UX-01, PAT-01)', () => {
  it('passes every parity rule for each real message bundle', () => {
    // Guard: adding a new bundle must add it above, or this test fails.
    expect(REAL_BUNDLES).toHaveLength(2);
    for (const bundle of REAL_BUNDLES) {
      expect(checkBundle(bundle)).toEqual([]);
    }
  });

  it('reports each violated rule with the bundle and key name', () => {
    expect(checkBundle(BROKEN_BUNDLE)).toEqual([
      'broken/sample.ts: key "onlyEnglish" is missing from Arabic',
      'broken/sample.ts: key "empty" is missing from English',
      'broken/sample.ts: ar value for "empty" is empty',
      'broken/sample.ts: placeholders differ for "greeting" (en: name, ar: other)',
      'broken/sample.ts: Arabic value for "code" has no Arabic letters ("CODE-1")',
    ]);
  });
});
