import { describe, expect, it } from 'vitest';
import { formatMoney, minorUnitDigits, parseMoney } from './money';

describe('finance money', () => {
  it('reports the minor-unit digits declared by each currency', () => {
    expect(minorUnitDigits('USD')).toBe(2);
    expect(minorUnitDigits('JPY')).toBe(0);
    expect(minorUnitDigits('KWD')).toBe(3);
  });

  it('formats minor units in English with Latin digits', () => {
    expect(formatMoney(1234, 'USD', 'en')).toBe('$12.34');
    expect(formatMoney(1234, 'JPY', 'en')).toBe('¥1,234');
    expect(formatMoney(5, 'USD', 'en')).toBe('$0.05');
  });

  it('formats minor units in Arabic while keeping Latin digits', () => {
    const formatted = formatMoney(1234, 'USD', 'ar');
    expect(formatted).toContain('12.34');
    expect(formatted).toContain('US$');
  });

  it('formats a negative refund amount without losing the sign', () => {
    expect(formatMoney(-50, 'USD', 'en')).toBe('-$0.50');
  });

  it('parses whole and fractional text into integer minor units', () => {
    expect(parseMoney('12.34', 'USD')).toEqual({ ok: true, minor: 1234 });
    expect(parseMoney('12', 'USD')).toEqual({ ok: true, minor: 1200 });
    expect(parseMoney('12.', 'USD')).toEqual({ ok: true, minor: 1200 });
    expect(parseMoney('1 234.5', 'USD')).toEqual({ ok: false, error: 'invalid' });
  });

  it('pads a zero-decimal currency without a fractional part', () => {
    expect(parseMoney('1234', 'JPY')).toEqual({ ok: true, minor: 1234 });
    expect(parseMoney('1234.5', 'JPY')).toEqual({ ok: false, error: 'too-many-decimals' });
  });

  it('respects a three-decimal currency', () => {
    expect(parseMoney('1.234', 'KWD')).toEqual({ ok: true, minor: 1234 });
    expect(parseMoney('1.2345', 'KWD')).toEqual({ ok: false, error: 'too-many-decimals' });
  });

  it.each(['', '   ', '.', '.5', '1.2.3', '12a', '1.a', '-5', '+5', '1,5'])(
    'rejects invalid money text %j',
    (text) => {
      const result = parseMoney(text, 'USD');
      expect(result.ok).toBe(false);
    },
  );

  it('reports the empty and too-many-decimals reasons explicitly', () => {
    expect(parseMoney('', 'USD')).toEqual({ ok: false, error: 'empty' });
    expect(parseMoney('1.234', 'USD')).toEqual({ ok: false, error: 'too-many-decimals' });
  });

  it('rejects an amount outside the safe integer range', () => {
    expect(parseMoney('99999999999999999999.99', 'USD')).toEqual({
      ok: false,
      error: 'invalid',
    });
  });
});
