import { fireEvent, render } from '@testing-library/react-native';

import { ServiceProfileNotice } from './ServiceProfileNotice';
import {
  channelText,
  createSyntheticServiceInfoClient,
  formatFacilityInstant,
  localizedText,
  noticeLines,
} from './serviceNotice';
import type { ServiceAvailabilityView, ServiceProfileView } from './types';

const UNIT = 'cccccccc-cccc-cccc-cccc-cccccccccccc';
const ORG = '11111111-1111-1111-1111-111111111111';

function profile(overrides: Partial<ServiceProfileView> = {}): ServiceProfileView {
  return {
    unitId: UNIT,
    organizationId: ORG,
    unitName: 'Synthetic Laboratory',
    version: 1,
    serviceHours: {
      timeZone: 'America/New_York',
      windows: [
        { weekday: 'FRIDAY', start: '09:00', end: '17:00', wraps: false },
        { weekday: 'SUNDAY', start: '22:00', end: '02:00', wraps: true },
      ],
    },
    responseTargetMinutes: 30,
    topics: ['collection', 'results'],
    urgentInstructions: [
      { language: 'en', value: 'For urgent care call the unit' },
      { language: 'ar', value: 'للرعاية العاجلة اتصل بالوحدة' },
    ],
    urgentChannels: [
      { key: 'phone', label: 'Urgent phone', value: '+1-555-0100' },
      { key: 'link', label: 'Downtime guidance', value: 'https://example.test/downtime' },
      { key: 'email', label: 'Unit mailbox', value: 'unit@example.test' },
    ],
    queueOwner: 'nurse-lead',
    backupCoverage: ['nurse-backup'],
    escalationChannel: 'escalation-queue',
    effectiveFrom: '2026-11-19T12:00:00Z',
    ...overrides,
  };
}

function closed(): ServiceAvailabilityView {
  return { open: false, nextHandlingAt: '2026-11-20T14:00:00Z', timeZone: 'America/New_York' };
}

describe('MSG-06 localized guidance', () => {
  it('selects the requested language and falls back to English', () => {
    const entries = [
      { language: 'en' as const, value: 'English' },
      { language: 'ar' as const, value: 'Arabic' },
    ];
    expect(localizedText(entries, 'ar')).toBe('Arabic');
    expect(localizedText([entries[0] as (typeof entries)[number]], 'ar')).toBe('English');
    expect(localizedText([entries[1] as (typeof entries)[number]], 'en')).toBe(undefined);
  });

  it('formats the absolute instant in the facility zone with an invariant fallback', () => {
    expect(formatFacilityInstant('2026-11-20T14:00:00Z', 'America/New_York')).toBe('20 Nov 2026, 09:00');
    expect(formatFacilityInstant('2026-11-20T14:00:00Z', 'Not/AZone')).toBe('2026-11-20T14:00:00Z');
  });

  it('formats a non-sensitive channel label and value', () => {
    expect(channelText({ key: 'phone', label: 'Urgent phone', value: '+1-555-0100' }))
      .toBe('Urgent phone: +1-555-0100');
  });

  it('puts urgent guidance before routine next-handling text when closed', () => {
    const lines = noticeLines(profile(), closed(), 'en');
    expect(lines[0]).toBe('For urgent care call the unit');
    expect(lines[1]).toBe('Urgent phone: +1-555-0100');
    expect(lines[2]).toBe('Downtime guidance: https://example.test/downtime');
    expect(lines[3]).toBe('Unit mailbox: unit@example.test');
    expect(lines[4]).toContain('Routine queue next handling: 20 Nov 2026, 09:00');
    expect(lines[4]).toContain('2026-11-20T14:00:00Z UTC');
  });

  it('omits routine handling text when open or when no window is published', () => {
    const open = noticeLines(profile(), { open: true, timeZone: 'UTC' }, 'en');
    expect(open.some((line) => line.includes('Routine queue'))).toBe(false);
    const noWindows = noticeLines(
      profile({ urgentInstructions: [], urgentChannels: [] }),
      { open: false, timeZone: 'UTC' },
      'en',
    );
    expect(noWindows).toEqual([]);
  });

  it('uses Arabic guidance when requested', () => {
    expect(noticeLines(profile(), closed(), 'ar')[0]).toBe('للرعاية العاجلة اتصل بالوحدة');
  });
});

describe('MSG-06 synthetic service-information client', () => {
  const availability = closed();
  const client = createSyntheticServiceInfoClient({
    profiles: [profile()],
    availability: { [UNIT]: availability },
    language: 'en',
  });

  it('returns only published profiles and availability', () => {
    expect(client.get(UNIT)?.unitName).toBe('Synthetic Laboratory');
    expect(client.get('unknown')).toBeUndefined();
    expect(client.status(UNIT)).toEqual(availability);
    expect(client.status('unknown')).toBeUndefined();
  });

  it('builds a notice only when both facts exist', () => {
    expect(client.notice(UNIT).length).toBeGreaterThan(0);
    const missing = createSyntheticServiceInfoClient({
      profiles: [],
      availability: {},
      language: 'en',
    });
    expect(missing.notice(UNIT)).toEqual([]);
    const noAvailability = createSyntheticServiceInfoClient({
      profiles: [profile()],
      availability: {},
      language: 'en',
    });
    expect(noAvailability.notice(UNIT)).toEqual([]);
  });
});

describe('MSG-06 patient service notice', () => {
  it('renders published hours, owners, channels and an after-hours banner before acknowledgement', async () => {
    let acknowledged = false;
    const view = await render(
      <ServiceProfileNotice
        profile={profile()}
        availability={closed()}
        language="en"
        isRTL={false}
        onAcknowledge={() => { acknowledged = true; }}
      />,
    );
    expect(view.getByTestId('unit-name')).toBeTruthy();
    expect(view.getByTestId('service-hours')).toBeTruthy();
    expect(view.getByText(/FRIDAY 09:00-17:00/)).toBeTruthy();
    expect(view.getByTestId('response-target')).toBeTruthy();
    expect(view.getByTestId('topics')).toBeTruthy();
    expect(view.getByTestId('urgent-instructions')).toBeTruthy();
    expect(view.getByTestId('urgent-channel-0')).toBeTruthy();
    expect(view.getByTestId('urgent-channel-1')).toBeTruthy();
    expect(view.getByTestId('urgent-channel-2')).toBeTruthy();
    expect(view.getByTestId('queue-owner')).toBeTruthy();
    expect(view.getByTestId('backup-coverage')).toBeTruthy();
    expect(view.getByTestId('escalation-channel')).toBeTruthy();
    expect(view.getByTestId('next-handling')).toBeTruthy();
    await fireEvent.press(view.getByTestId('acknowledge-guidance'));
    expect(acknowledged).toBe(true);
  });

  it('uses a forward layout for LTR and mirrors it for RTL', async () => {
    const ltr = await render(
      <ServiceProfileNotice profile={profile()} availability={closed()} language="en" isRTL={false}
        onAcknowledge={() => undefined} />,
    );
    expect(ltr.getByTestId('service-profile-notice').props.style.flexDirection).toBe('row');
    const rtl = await render(
      <ServiceProfileNotice profile={profile()} availability={closed()} language="ar" isRTL={true}
        onAcknowledge={() => undefined} />,
    );
    expect(rtl.getByTestId('service-profile-notice').props.style.flexDirection).toBe('row-reverse');
  });

  it('omits the after-hours banner when open and when no handling instant is published', async () => {
    const open = await render(
      <ServiceProfileNotice profile={profile()} availability={{ open: true, timeZone: 'UTC' }}
        language="en" isRTL={false} onAcknowledge={() => undefined} />,
    );
    expect(open.queryByTestId('next-handling')).toBeNull();
    const noNext = await render(
      <ServiceProfileNotice profile={profile()} availability={{ open: false, timeZone: 'UTC' }}
        language="en" isRTL={false} onAcknowledge={() => undefined} />,
    );
    expect(noNext.queryByTestId('next-handling')).toBeNull();
  });

  it('renders no urgent instruction when none are approved', async () => {
    const view = await render(
      <ServiceProfileNotice
        profile={profile({ urgentInstructions: [], urgentChannels: [] })}
        availability={closed()}
        language="en"
        isRTL={false}
        onAcknowledge={() => undefined}
      />,
    );
    expect(view.queryByTestId('urgent-instructions')).toBeNull();
  });
});
