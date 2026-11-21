import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import { isSupportedBrowser } from './features/system/browserSupport';
import { UnsupportedBrowserPage } from './features/system/UnsupportedBrowserPage';
import { I18nProvider } from './i18n/I18nProvider';
import { ThemeProvider } from './theme/ThemeProvider';
import './theme/responsive.css';

const container = document.getElementById('root');
if (!container) {
  throw new Error('Root container #root is required');
}

const root = createRoot(container);

/**
 * Choose the entry tree for the current client. An unsupported client receives
 * only the translated plain message, never application data (WEB-07).
 */
const renderEntry = (): void => {
  const content = isSupportedBrowser(navigator.userAgent, window.innerWidth) ? (
    <App />
  ) : (
    <ThemeProvider>
      <I18nProvider>
        <UnsupportedBrowserPage />
      </I18nProvider>
    </ThemeProvider>
  );
  root.render(<StrictMode>{content}</StrictMode>);
};

// Check before the first mount and again whenever the viewport can change. The
// 320 px support floor means a tablet rotating through intermediate widths stays
// supported, so a transient width never swaps the app for the unsupported page.
renderEntry();
window.addEventListener('resize', renderEntry);
window.addEventListener('orientationchange', renderEntry);
