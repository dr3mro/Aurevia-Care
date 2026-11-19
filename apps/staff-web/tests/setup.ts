import { afterEach } from 'vitest';
import { cleanup } from '@testing-library/react';

afterEach(() => {
  cleanup();
  document.documentElement.removeAttribute('dir');
  document.documentElement.removeAttribute('lang');
  delete document.documentElement.dataset.theme;
});
