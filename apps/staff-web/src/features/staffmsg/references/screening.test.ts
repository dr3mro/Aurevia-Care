import { describe, expect, it } from 'vitest';
import { screenSubject } from './index';

describe('screenSubject', () => {
  it('treats empty and whitespace-only subjects as unsuspicious', () => {
    for (const subject of ['', '   \t\n']) {
      expect(screenSubject(subject)).toEqual({ suspicious: false, reason: 'NONE' });
    }
  });

  it('treats an ordinary subject as unsuspicious without echoing it', () => {
    const result = screenSubject('Care plan review');
    expect(result).toEqual({ suspicious: false, reason: 'NONE' });
    expect(JSON.stringify(result)).not.toContain('Care plan review');
  });

  it('flags UUID-like text without echoing it', () => {
    const subject = 'Patient 123e4567-e89b-12d3-a456-426614174000';
    const result = screenSubject(subject);
    expect(result).toEqual({ suspicious: true, reason: 'UUID_LIKE' });
    expect(JSON.stringify(result)).not.toContain(subject);
    expect(JSON.stringify(result)).not.toContain('123e4567');
  });

  it('flags a long digit run without echoing it', () => {
    const subject = 'Call 1234567 today';
    const result = screenSubject(subject);
    expect(result).toEqual({ suspicious: true, reason: 'DIGIT_RUN' });
    expect(JSON.stringify(result)).not.toContain('1234567');
    expect(JSON.stringify(result)).not.toContain(subject);
  });

  it('flags an email-like token without echoing it', () => {
    const subject = 'Contact staff@synthetic.invalid';
    const result = screenSubject(subject);
    expect(result).toEqual({ suspicious: true, reason: 'EMAIL_LIKE' });
    expect(JSON.stringify(result)).not.toContain('staff@synthetic.invalid');
    expect(JSON.stringify(result)).not.toContain(subject);
  });

  it.each(['mrn: 12345', 'PATIENT #88', 'order-77'] as const)(
    'flags MRN-like token %s case-insensitively without echoing it',
    (subject) => {
      const result = screenSubject(subject);
      expect(result).toEqual({ suspicious: true, reason: 'MRN_LIKE' });
      expect(JSON.stringify(result)).not.toContain(subject);
    },
  );

  it('keeps the backend pattern precedence when patterns overlap', () => {
    expect(screenSubject('mrn 1234567')).toEqual({ suspicious: true, reason: 'DIGIT_RUN' });
  });
});
