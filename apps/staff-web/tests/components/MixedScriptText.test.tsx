import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MixedScriptText } from '../../src/components/MixedScriptText';

describe('mixed-script display', () => {
  it('marks the language and lets the browser resolve bidirectional runs', () => {
    render(<MixedScriptText text="ليلى Haddad · MAIN" lang="ar" />);
    const text = screen.getByText('ليلى Haddad · MAIN');
    expect(text.tagName).toBe('BDI');
    expect(text.getAttribute('lang')).toBe('ar');
    expect(text.getAttribute('dir')).toBe('auto');
  });
});
