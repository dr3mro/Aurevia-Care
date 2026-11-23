/**
 * UX-01 clause 1 evidence, staff web: English and Arabic, mirrored direction,
 * local name display and isolated mixed-script clinical text.
 *
 * Synthetic fixtures only. This file is `.ts` (not `.tsx`), so React elements
 * are built with `createElement`; the render assertions still exercise the
 * real `I18nProvider`, `format` and `MixedScriptText` boundaries in jsdom.
 */
import { describe, expect, it } from 'vitest';
import { createElement } from 'react';
import type { ReactElement } from 'react';
import { render, screen } from '@testing-library/react';
import { I18nProvider, useI18n } from '../../../src/i18n/I18nProvider';
import {
  annotatedName,
  directionFor,
  displayLocalName,
  message,
  mixedScriptRun,
} from '../../../src/i18n/format';
import { messages } from '../../../src/i18n/messages';
import type { Locale } from '../../../src/i18n/messages';
import { MixedScriptText } from '../../../src/components/MixedScriptText';
import { mixedScriptLabel, syntheticPerson } from '../../../src/fixtures/synthetic';

/** Renders the locale-selected display name and a translated label through the real i18n context. */
function LocalizedName() {
  const { locale, t } = useI18n();
  return createElement(
    'div',
    null,
    createElement(
      'span',
      { 'data-testid': 'local-name' },
      displayLocalName(syntheticPerson, locale),
    ),
    createElement('span', { 'data-testid': 'translated-label' }, t('signIn')),
  );
}

/** Reads the i18n context without a provider so the fail-closed path can be tested. */
function LocaleReaderWithoutProvider() {
  useI18n();
  return createElement('span', null, 'unreachable');
}

/** Mount the localized name under the real provider at an initial locale. */
function localizedNameIn(initialLocale: Locale): ReactElement {
  const props = { initialLocale, children: createElement(LocalizedName) };
  return createElement(I18nProvider, props);
}

describe('UX-01 clause 1 — bilingual, RTL, local names and mixed-script text', () => {
  it('rendersEnglishAndArabicWithMirroredDirectionLocalNameAndIsolatedMixedScript', () => {
    expect(directionFor('en')).toBe('ltr');
    expect(directionFor('ar')).toBe('rtl');

    const english = render(localizedNameIn('en'));
    expect(document.documentElement.lang).toBe('en');
    expect(document.documentElement.dir).toBe('ltr');
    expect(screen.getByTestId('local-name').textContent).toBe(syntheticPerson.latinName);
    const englishLabel = screen.getByTestId('translated-label').textContent;
    expect(englishLabel).toBe(messages.en.signIn);
    expect(message('en', 'signIn')).toBe(messages.en.signIn);
    english.unmount();

    const arabic = render(localizedNameIn('ar'));
    expect(document.documentElement.lang).toBe('ar');
    expect(document.documentElement.dir).toBe('rtl');
    expect(screen.getByTestId('local-name').textContent).toBe(syntheticPerson.localName);
    const arabicLabel = screen.getByTestId('translated-label').textContent;
    expect(arabicLabel).toBe(messages.ar.signIn);
    expect(arabicLabel).not.toBe(englishLabel);
    expect(arabicLabel).toMatch(/[\u0600-\u06FF]/);
    expect(message('ar', 'signIn')).toBe(messages.ar.signIn);

    const run = mixedScriptRun(mixedScriptLabel, 'ar');
    expect(run.dir).toBe('auto');
    expect(run.lang).toBe('ar');
    render(createElement(MixedScriptText, { text: run.text, lang: run.lang }));
    const isolated = screen.getByText(mixedScriptLabel);
    expect(isolated.tagName).toBe('BDI');
    expect(isolated.getAttribute('lang')).toBe('ar');
    expect(isolated.getAttribute('dir')).toBe('auto');
    arabic.unmount();
  });

  it('keepsTheLatinNameInAnEnglishInterfaceEvenWhenALocalFormExists', () => {
    expect(syntheticPerson.localName).toBeDefined();
    expect(annotatedName(syntheticPerson, 'en')).toEqual({
      text: syntheticPerson.latinName,
      lang: 'en',
      dir: 'auto',
    });
    expect(displayLocalName(syntheticPerson, 'en')).toBe(syntheticPerson.latinName);
    expect(displayLocalName(syntheticPerson, 'en')).not.toBe(syntheticPerson.localName);
  });

  it('failsClosedWhenNoLocaleProviderIsMounted', () => {
    expect(() => render(createElement(LocaleReaderWithoutProvider))).toThrow(
      'useI18n must be used inside I18nProvider',
    );
  });

  it('doesNotForceTheInterfaceDirectionOntoAForeignClinicalRun', () => {
    const view = render(localizedNameIn('ar'));
    expect(document.documentElement.dir).toBe('rtl');

    const foreign = mixedScriptRun('MAIN · Layla Haddad', 'en');
    expect(foreign.dir).toBe('auto');
    expect(foreign.lang).toBe('en');
    render(createElement(MixedScriptText, { text: foreign.text, lang: foreign.lang }));
    const isolated = screen.getByText('MAIN · Layla Haddad');
    expect(isolated.tagName).toBe('BDI');
    expect(isolated.getAttribute('lang')).toBe('en');
    expect(isolated.getAttribute('dir')).toBe('auto');
    expect(document.documentElement.dir).toBe('rtl');
    view.unmount();
  });
});
