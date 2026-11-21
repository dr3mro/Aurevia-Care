import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import { isSupportedBrowser } from './features/system/browserSupport';
import { UnsupportedBrowserPage } from './features/system/UnsupportedBrowserPage';
import { I18nProvider } from './i18n/I18nProvider';
import { ThemeProvider } from './theme/ThemeProvider';

const container = document.getElementById('root');
if (!container) {
  throw new Error('Root container #root is required');
}

// Check before the application mounts: an unsupported client never receives
// application data, only a translated plain message (WEB-07).
const content = isSupportedBrowser(navigator.userAgent, window.innerWidth) ? (
  <App />
) : (
  <ThemeProvider>
    <I18nProvider>
      <UnsupportedBrowserPage />
    </I18nProvider>
  </ThemeProvider>
);

createRoot(container).render(<StrictMode>{content}</StrictMode>);
