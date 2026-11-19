/** Available presentation themes. */
export type ThemeMode = 'light' | 'dark';

/** Visible design tokens shared by the staff-web primitives. */
export interface ThemeTokens {
  background: string;
  foreground: string;
  border: string;
  accent: string;
  focusRing: string;
  spacing: { sm: string; md: string; lg: string };
}

const light: ThemeTokens = {
  background: '#ffffff',
  foreground: '#152033',
  border: '#6b7280',
  accent: '#0b5cab',
  focusRing: '0 0 0 3px #ffbf47',
  spacing: { sm: '4px', md: '8px', lg: '16px' },
};

const dark: ThemeTokens = {
  background: '#111827',
  foreground: '#f3f6fb',
  border: '#7c8ca3',
  accent: '#7db3ff',
  focusRing: '0 0 0 3px #ffbf47',
  spacing: { sm: '4px', md: '8px', lg: '16px' },
};

/** Resolve a consistent token set for the requested theme mode. */
export function themeTokens(mode: ThemeMode): ThemeTokens {
  return mode === 'light' ? light : dark;
}
