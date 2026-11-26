/**
 * Cross-feature parity for the four recently added message bundles.
 *
 * An output comparison alone cannot detect a copied regex, so the first test
 * intercepts the shared filler and asserts that every bundle calls it with its
 * own template and values. The second test guards the observable substitution
 * in both locales against the real helper.
 */
import { describe, expect, it, vi } from 'vitest';

import type * as PlaceholdersModule from '../duplicates/messagePlaceholders';
import { fillMessagePlaceholders } from '../duplicates/messagePlaceholders';
import { labBoardMessage, labBoardText } from './labBoardMessages';
import { labMessage, labText } from '../labresults/labResultMessages';
import { partnerMessage, partnerText } from '../partnerboard/partnerMessages';
import { releaseMessage, releaseText } from '../labrelease/labReleaseMessages';

const SENTINEL = 'filled-by-the-shared-helper';
const VALUES: Readonly<Record<string, string>> = {
  count: '3',
  subject: 'synthetic.reviewer',
  reason: 'typo',
};

vi.mock('../duplicates/messagePlaceholders', async (importOriginal) => {
  const actual = await importOriginal<typeof PlaceholdersModule>();
  return { ...actual, fillMessagePlaceholders: vi.fn(actual.fillMessagePlaceholders) };
});

describe('shared placeholder filler delegation', () => {
  it('routes every migrated bundle through the shared filler', async () => {
    const real = await vi.importActual<typeof PlaceholdersModule>(
      '../duplicates/messagePlaceholders',
    );
    const filler = vi.mocked(fillMessagePlaceholders);
    filler.mockImplementation(() => SENTINEL);
    try {
      expect(labMessage('en', 'summaryCount', VALUES)).toBe(SENTINEL);
      expect(filler).toHaveBeenNthCalledWith(1, labText('en', 'summaryCount'), VALUES);

      expect(labBoardMessage('en', 'orderCount', VALUES)).toBe(SENTINEL);
      expect(filler).toHaveBeenNthCalledWith(2, labBoardText('en', 'orderCount'), VALUES);

      expect(partnerMessage('en', 'waitingCount', VALUES)).toBe(SENTINEL);
      expect(filler).toHaveBeenNthCalledWith(3, partnerText('en', 'waitingCount'), VALUES);

      expect(releaseMessage('en', 'correctedStatus', VALUES)).toBe(SENTINEL);
      expect(filler).toHaveBeenNthCalledWith(4, releaseText('en', 'correctedStatus'), VALUES);

      expect(filler).toHaveBeenCalledTimes(4);
    } finally {
      filler.mockImplementation(real.fillMessagePlaceholders);
    }
  });

  it('substitutes identically to the shared filler in both locales', async () => {
    const real = await vi.importActual<typeof PlaceholdersModule>(
      '../duplicates/messagePlaceholders',
    );
    vi.mocked(fillMessagePlaceholders).mockImplementation(real.fillMessagePlaceholders);

    for (const locale of ['en', 'ar'] as const) {
      expect(labMessage(locale, 'summaryCount', VALUES)).toBe(
        real.fillMessagePlaceholders(labText(locale, 'summaryCount'), VALUES),
      );
      expect(labBoardMessage(locale, 'orderCount', VALUES)).toBe(
        real.fillMessagePlaceholders(labBoardText(locale, 'orderCount'), VALUES),
      );
      expect(partnerMessage(locale, 'waitingCount', VALUES)).toBe(
        real.fillMessagePlaceholders(partnerText(locale, 'waitingCount'), VALUES),
      );
      expect(releaseMessage(locale, 'correctedStatus', VALUES)).toBe(
        real.fillMessagePlaceholders(releaseText(locale, 'correctedStatus'), VALUES),
      );
    }
  });
});
