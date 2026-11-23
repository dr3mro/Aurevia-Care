import { describe, expect, it } from 'vitest';

import { navigationLabels } from '../src/app/navigation';
import { shellLabels } from '../src/app/shellText';
import { stateViewMessages } from '../src/components/stateViewMessages';
import { adminKeys, adminText } from '../src/features/admin/messages';
import { chartKeys, chartText } from '../src/features/chart/messages';
import { duplicateMessages } from '../src/features/duplicates/messages';
import { labKeys, labText } from '../src/features/labcollection/messages';
import { entryKeys, entryText } from '../src/features/orderentry/entry/messages';
import { patientMessages } from '../src/features/patients/messages';
import { printMessageKeys, printText } from '../src/features/print/components/messages';
import { schedulingKeys, schedulingText } from '../src/features/scheduling/messages';
import { systemMessages } from '../src/features/system/messages';
import { messages } from '../src/i18n/messages';
import type { Locale } from '../src/i18n/messages';

/**
 * `bundle.key` references whose Arabic value intentionally has no Arabic letter.
 * UTC is an ISO time-zone code and a brand-style abbreviation: it is quoted
 * verbatim by clinicians in both locales, so translating it would be unsafe.
 * Every entry must be justified here; an unexplained entry hides a real gap.
 */
const ARABIC_TEXT_ALLOW_LIST: ReadonlySet<string> = new Set([
  // TODO fix in a separate task: review whether the bare UTC code needs a localized gloss.
  'admin.utcLabel',
]);

/** Arabic letters only; Arabic-Indic digits and punctuation do not satisfy UX-01. */
const ARABIC_LETTER =
  /[\u0621-\u063A\u0641-\u064A\u066E-\u066F\u0671-\u06D3\u06D5\u06FA-\u06FC\u06FF]/;

/** Named placeholders use the same syntax in every bundle's fill helper. */
const PLACEHOLDER = /\{\w+\}/g;

/** A uniform view of a bundle, whatever private shape its module uses. */
interface MessageBundle {
  readonly name: string;
  readonly enKeys: readonly string[];
  readonly arKeys: readonly string[];
  readonly en: (key: string) => string | undefined;
  readonly ar: (key: string) => string | undefined;
}

/** Wrap two directly exported locale records as a bundle. */
function fromRecord<KEn extends string, KAr extends string>(
  name: string,
  en: Readonly<Record<KEn, string>>,
  ar: Readonly<Record<KAr, string>>,
): MessageBundle {
  return {
    name,
    enKeys: Object.keys(en),
    arKeys: Object.keys(ar),
    en: (key) => en[key as KEn],
    ar: (key) => ar[key as KAr],
  };
}

/**
 * Wrap a bundle that keeps its records private and exposes only its key list
 * and a locale/key lookup. Both sides come from the same key list, so a key
 * missing from one record is observed as an empty value instead.
 */
function fromKeysAndText<K extends string>(
  name: string,
  keys: readonly K[],
  text: (locale: Locale, key: K) => string,
): MessageBundle {
  return {
    name,
    enKeys: [...keys],
    arKeys: [...keys],
    en: (key) => text('en', key as K),
    ar: (key) => text('ar', key as K),
  };
}

/** Return sorted placeholder names, turning `{name}` into `name`. */
function placeholderNames(value: string): string[] {
  return [...value.matchAll(PLACEHOLDER)].map((match) => match[0].slice(1, -1)).sort();
}

/** Compare two sorted key/name lists by value. */
function sameNames(left: readonly string[], right: readonly string[]): boolean {
  return left.length === right.length && left.every((value, index) => value === right[index]);
}

/**
 * Return one diagnostic per parity violation. Every message starts with
 * `<bundle>.<key>` so a reader knows exactly which string to fix.
 */
function checkBundle(bundle: MessageBundle): string[] {
  const problems: string[] = [];
  const report = (key: string, detail: string): void => {
    problems.push(`${bundle.name}.${key}: ${detail}`);
  };

  const enKeys = [...bundle.enKeys].sort();
  const arKeys = [...bundle.arKeys].sort();
  if (!sameNames(enKeys, arKeys)) {
    const missingInAr = enKeys.filter((key) => !bundle.arKeys.includes(key));
    const missingInEn = arKeys.filter((key) => !bundle.enKeys.includes(key));
    report(
      '(keys)',
      `key sets differ (missing in Arabic: [${missingInAr.join(', ')}]; ` +
        `missing in English: [${missingInEn.join(', ')}])`,
    );
  }

  for (const key of bundle.enKeys) {
    const en = bundle.en(key);
    const ar = bundle.ar(key);
    if (en === undefined || en.trim().length === 0) {
      report(key, 'empty or missing English value');
    }
    if (ar === undefined || ar.trim().length === 0) {
      report(key, 'empty or missing Arabic value');
      continue;
    }

    if (en !== undefined && en.trim().length > 0) {
      const enPlaceholders = placeholderNames(en);
      const arPlaceholders = placeholderNames(ar);
      if (!sameNames(enPlaceholders, arPlaceholders)) {
        report(
          key,
          `placeholder mismatch (English: [${enPlaceholders.join(', ')}]; ` +
            `Arabic: [${arPlaceholders.join(', ')}])`,
        );
      }
    }

    const reference = `${bundle.name}.${key}`;
    if (!ARABIC_LETTER.test(ar) && !ARABIC_TEXT_ALLOW_LIST.has(reference)) {
      report(key, `Arabic value "${ar}" has no Arabic letters and is not allow-listed`);
    }
  }
  return problems;
}

/** Source paths that the registry below must cover, relative to `src/`. */
const BUNDLE_SOURCES: readonly string[] = [
  'i18n/messages.ts',
  'components/stateViewMessages.ts',
  'features/patients/messages.ts',
  'features/print/components/messages.ts',
  'features/admin/messages.ts',
  'features/system/messages.ts',
  'features/duplicates/messages.ts',
  'features/orderentry/entry/messages.ts',
  'features/chart/messages.ts',
  'features/labcollection/messages.ts',
  'features/scheduling/messages.ts',
  'app/navigation.ts',
  'app/shellText.ts',
];

/**
 * Filesystem discovery: a newly added bundle file makes the registry test fail
 * until it is registered below. Only bundle modules are loaded, all of which
 * the registry already imports statically.
 */
const discoveredSources: readonly string[] = [
  ...Object.keys(import.meta.glob('../src/**/messages.ts', { eager: true })),
  ...Object.keys(import.meta.glob('../src/**/stateViewMessages.ts', { eager: true })),
  ...Object.keys(import.meta.glob('../src/app/navigation.ts', { eager: true })),
  ...Object.keys(import.meta.glob('../src/app/shellText.ts', { eager: true })),
]
  .map((path) => path.replace('../src/', ''))
  .sort();

/** Every bilingual bundle under src, including the app shell and navigation rail. */
const bundles: readonly MessageBundle[] = [
  fromRecord('i18n', messages.en, messages.ar),
  fromRecord('stateView', stateViewMessages.en, stateViewMessages.ar),
  fromRecord('patients', patientMessages.en, patientMessages.ar),
  fromKeysAndText('admin', adminKeys, adminText),
  fromRecord('system', systemMessages.en, systemMessages.ar),
  fromRecord('duplicates', duplicateMessages.en, duplicateMessages.ar),
  fromKeysAndText('chart', chartKeys, chartText),
  fromKeysAndText('labcollection', labKeys, labText),
  fromKeysAndText('orderEntry', entryKeys, entryText),
  fromKeysAndText('scheduling', schedulingKeys, schedulingText),
  fromKeysAndText('print', printMessageKeys, printText),
  fromRecord('navigation', navigationLabels.en, navigationLabels.ar),
  fromRecord('shell', shellLabels.en, shellLabels.ar),
];

describe('UX-01 staff-web message bundle registry', () => {
  it('discovers every bilingual bundle file under src', () => {
    expect(discoveredSources).toEqual([...BUNDLE_SOURCES].sort());
    expect(bundles).toHaveLength(BUNDLE_SOURCES.length);
  });

  it('registers a distinct bundle name for each source file', () => {
    expect(new Set(bundles.map((bundle) => bundle.name)).size).toBe(bundles.length);
  });

  it('finds no English/Arabic parity violation in the real bundles', () => {
    expect(bundles.flatMap(checkBundle)).toEqual([]);
  });
});

describe('UX-01 message parity checker rejects broken sample bundles', () => {
  it('names the bundle and key when an Arabic key is missing', () => {
    const broken = fromRecord(
      'sample',
      { greeting: 'Hello', farewell: 'Goodbye' },
      { farewell: 'مع السلامة' },
    );
    const problems = checkBundle(broken);
    expect(
      problems.some((problem) => problem.includes('sample.(keys)') && problem.includes('greeting')),
    ).toBe(true);
    expect(problems).toContain('sample.greeting: empty or missing Arabic value');
  });

  it('names the bundle and key when an English key is missing', () => {
    const broken = fromRecord(
      'sample',
      { greeting: 'Hello' },
      { greeting: 'مرحباً', farewell: 'مع السلامة' },
    );
    const problems = checkBundle(broken);
    expect(
      problems.some((problem) => problem.includes('sample.(keys)') && problem.includes('farewell')),
    ).toBe(true);
  });

  it('reports an empty value in either locale', () => {
    const broken = fromRecord(
      'sample',
      { greeting: '', farewell: 'Goodbye' },
      { greeting: 'مرحباً', farewell: '   ' },
    );
    expect(checkBundle(broken)).toEqual([
      'sample.greeting: empty or missing English value',
      'sample.farewell: empty or missing Arabic value',
    ]);
  });

  it('reports a placeholder missing from the Arabic text', () => {
    const broken = fromRecord(
      'sample',
      { registered: 'Registered {name}' },
      { registered: 'تم التسجيل' },
    );
    expect(checkBundle(broken)).toEqual([
      'sample.registered: placeholder mismatch (English: [name]; Arabic: [])',
    ]);
  });

  it('reports a placeholder missing from the English text', () => {
    const broken = fromRecord(
      'sample',
      { registered: 'Registered' },
      { registered: 'تم تسجيل {name}' },
    );
    expect(checkBundle(broken)).toEqual([
      'sample.registered: placeholder mismatch (English: []; Arabic: [name])',
    ]);
  });

  it('accepts reordered placeholders with the same names', () => {
    const reordered = fromRecord(
      'sample',
      { announced: 'Version {version} of {key}' },
      { announced: 'الإصدار {key} من {version}' },
    );
    expect(checkBundle(reordered)).toEqual([]);
  });

  it('reports an Arabic value with no Arabic letters unless allow-listed', () => {
    const broken = fromRecord('sample', { codeLabel: 'UTC label' }, { codeLabel: 'UTC label' });
    expect(checkBundle(broken)).toEqual([
      'sample.codeLabel: Arabic value "UTC label" has no Arabic letters and is not allow-listed',
    ]);
  });

  it('rejects a value made only of Arabic-Indic digits', () => {
    const broken = fromRecord('sample', { year: 'Year' }, { year: '٢٠٢٦' });
    expect(checkBundle(broken)).toEqual([
      'sample.year: Arabic value "٢٠٢٦" has no Arabic letters and is not allow-listed',
    ]);
  });

  it('does not report an allow-listed code-only Arabic value', () => {
    const withAllowedCode = fromRecord('admin', { utcLabel: 'UTC' }, { utcLabel: 'UTC' });
    expect(checkBundle(withAllowedCode)).toEqual([]);
  });
});
