import { createContext, useContext, useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { directionFor, message } from './format';
import type { ScriptDirection } from './format';
import type { Locale, MessageKey } from './messages';

/** Locale state and translation helpers exposed to the staff shell. */
export interface I18nContextValue {
  locale: Locale;
  direction: ScriptDirection;
  setLocale: (locale: Locale) => void;
  toggleLocale: () => void;
  t: (key: MessageKey) => string;
}

const I18nContext = createContext<I18nContextValue | null>(null);

/** Owns the active locale and keeps the document language/direction in sync. */
export function I18nProvider({
  children,
  initialLocale = 'en',
}: {
  children: ReactNode;
  initialLocale?: Locale;
}) {
  const [locale, setLocale] = useState<Locale>(initialLocale);

  useEffect(() => {
    document.documentElement.lang = locale;
    document.documentElement.dir = directionFor(locale);
  }, [locale]);

  const value = useMemo<I18nContextValue>(
    () => ({
      locale,
      direction: directionFor(locale),
      setLocale,
      toggleLocale: () => setLocale((current) => (current === 'en' ? 'ar' : 'en')),
      t: (key) => message(locale, key),
    }),
    [locale],
  );

  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}

/** Read locale state; fails closed when no provider is mounted. */
export function useI18n(): I18nContextValue {
  const context = useContext(I18nContext);
  if (!context) {
    throw new Error('useI18n must be used inside I18nProvider');
  }
  return context;
}
