/**
 * PAT-01, PAT-02, UX-01: responsive layout evidence for the patient
 * appointment list. The screen is rendered at every window class, in both
 * orientations and at three font scales. The assertions prove the presentation
 * contract only: a centred, width-capped reading column, two panes on large
 * windows, 48 pt touch targets, unchanged screen-reader labels, safe-area
 * insets, mirrored RTL and no text clipping at 200 percent scale. No clinical
 * data is cached and no behavior, copy or callback is changed.
 */
import { jest } from '@jest/globals';
import { render } from '@testing-library/react-native';
import { Dimensions } from 'react-native';

import type { AppointmentView } from './appointments';
import type { DeviceToken, GenericNotice } from './types';
import { AppointmentListScreen } from './AppointmentListScreen';

type WindowSize = { width: number; height: number; scale: number; fontScale: number };
type Insets = { top: number; right: number; bottom: number; left: number };

/** Maximum readable inline width, in density-independent points. */
const CONTENT_MAX_WIDTH = 960;
/** Larger of the 44 pt / 48 dp platform touch-target pair. */
const TOUCH_TARGET = 48;

let mockWindow: WindowSize = { width: 390, height: 844, scale: 3, fontScale: 1 };
let mockInsets: Insets = { top: 20, right: 24, bottom: 32, left: 16 };

jest.mock('react-native-safe-area-context', () => ({
  useSafeAreaInsets: () => mockInsets,
}));

const notice: GenericNotice = { title: 'Appointment update', body: 'Open the app to view details.' };

const pending: AppointmentView = {
  key: 'org-a\u0000apt-1',
  id: 'apt-1',
  version: 3,
  timeZone: 'Asia/Riyadh',
  localStart: '10:00',
  status: 'booked',
  confirmation: { kind: 'pending', confirmedAt: null },
  confirmedLocal: null,
  dateLabel: 'Thu, Nov 26',
  timeLabel: '2:30 PM',
  visitLine: 'Follow-up with Dr. Noor Karim',
  facilityLine: 'Harbor Family Clinic, Brooklyn site',
};

const device: DeviceToken = { id: 'dev-1', patientId: 'patient-1', state: 'active', token: 'synthetic-token' };

const WINDOWS = [
  { name: 'compact portrait', width: 390, height: 844, large: false },
  { name: 'compact landscape', width: 560, height: 320, large: false },
  { name: 'regular portrait', width: 700, height: 900, large: false },
  { name: 'regular landscape', width: 800, height: 600, large: false },
  { name: 'large portrait', width: 900, height: 1200, large: true },
  { name: 'large landscape', width: 1024, height: 768, large: true },
] as const;

const FONT_SCALES = [1, 1.5, 2] as const;

/** The pane gutter grows with text scale so large text stays separated. */
function expectedGutter(fontScale: number): number {
  if (fontScale >= 2) return 24;
  if (fontScale >= 1.5) return 20;
  return 16;
}

const MATRIX = WINDOWS.flatMap((window) =>
  FONT_SCALES.map((fontScale) => ({ ...window, fontScale, title: `${window.name} at ${fontScale}x` })),
);

function renderScreen(locale: 'en' | 'ar' = 'en', appointments: readonly AppointmentView[] = [pending]) {
  return render(
    <AppointmentListScreen
      appointments={appointments}
      notice={notice}
      patientId="patient-1"
      devices={[device]}
      onConfirm={() => undefined}
      onOpenDetails={() => undefined}
      onRevokeDevice={() => undefined}
      locale={locale}
    />,
  );
}

/** Assert the screen-reader labels survive every responsive presentation. */
function expectLabelsPreserved(view: Awaited<ReturnType<typeof render>>): void {
  expect(view.getByLabelText('visit-apt-1')).toBeTruthy();
  expect(view.getByLabelText('facility-apt-1')).toBeTruthy();
  // The pill and the authoritative status word both carry this label (the
  // existing phone presentation does too): assert it is still exposed.
  expect(view.getAllByLabelText('Please confirm').length).toBeGreaterThan(0);
  expect(view.getByLabelText('Confirm appointment')).toBeTruthy();
  expect(view.getByLabelText('View details')).toBeTruthy();
  expect(view.getByLabelText('Revoke device')).toBeTruthy();
  // Focus order is the DOM order: confirm, details, then device revocation,
  // identical to the one-pane phone presentation.
  expect(view.getAllByRole('button').map((button) => button.props.testID)).toEqual([
    'confirm-apt-1',
    'details-apt-1',
    'revoke-dev-1',
  ]);
}

describe('AppointmentListScreen responsive layout (PAT-01, PAT-02, UX-01)', () => {
  beforeEach(() => {
    mockWindow = { width: 390, height: 844, scale: 3, fontScale: 1 };
    mockInsets = { top: 20, right: 24, bottom: 32, left: 16 };
    jest.spyOn(Dimensions, 'get').mockImplementation(() => mockWindow);
  });

  afterEach(() => {
    jest.restoreAllMocks();
  });

  it.each(MATRIX)('lays out $title', async ({ width, height, fontScale, large }) => {
    mockWindow = { width, height, scale: 2, fontScale };
    const view = await renderScreen();

    const content = view.getByTestId('appointment-content');
    expect(content.props.style).toEqual(
      expect.objectContaining({
        alignSelf: 'center',
        width: '100%',
        maxWidth: CONTENT_MAX_WIDTH,
        flexDirection: large ? 'row' : 'column',
        flexWrap: large ? 'wrap' : 'nowrap',
        paddingTop: Math.max(mockInsets.top, 16),
        paddingBottom: Math.max(mockInsets.bottom, 16),
        paddingStart: Math.max(mockInsets.left, 16),
        paddingEnd: Math.max(mockInsets.right, 16),
      }),
    );

    // The list and its detail pane are present at every class; only their
    // arrangement changes. No pane carries an accessibility label, so the
    // screen-reader tree is identical to the phone presentation.
    const listPane = view.getByTestId('appointment-list-pane');
    const detailPane = view.getByTestId('appointment-detail-pane');
    expect(listPane.props.style).toEqual(expect.objectContaining({ flex: large ? 1 : undefined, minWidth: 0 }));
    expect(detailPane.props.style).toEqual(
      expect.objectContaining({
        flex: large ? 1 : undefined,
        minWidth: 0,
        marginStart: large ? expectedGutter(fontScale) : 0,
      }),
    );
    expect(listPane.props.accessibilityLabel).toBeUndefined();
    expect(detailPane.props.accessibilityLabel).toBeUndefined();

    // Touch targets keep the 48 pt floor on every window and at every scale.
    const details = view.getByTestId('details-apt-1');
    const revoke = view.getByTestId('revoke-dev-1');
    expect(details.props.style).toEqual(expect.objectContaining({ minWidth: TOUCH_TARGET, minHeight: TOUCH_TARGET }));
    expect(revoke.props.style).toEqual(expect.objectContaining({ minWidth: TOUCH_TARGET, minHeight: TOUCH_TARGET }));
    expect(view.getByTestId('confirm-apt-1').props.style).toEqual(
      expect.arrayContaining([expect.objectContaining({ minHeight: TOUCH_TARGET })]),
    );

    // Text is never shrunk or truncated to survive 200 percent scaling.
    for (const testID of ['time-apt-1', 'status-apt-1']) {
      const text = view.queryByTestId(testID);
      if (text === null) continue;
      expect(text.props.numberOfLines).toBeUndefined();
      expect(text.props.adjustsFontSizeToFit).toBeUndefined();
      expect(text.props.maxFontSizeMultiplier).toBeUndefined();
      expect(text.props.allowFontScaling).not.toBe(false);
    }

    expectLabelsPreserved(view);
    expect(view.getByTestId('generic-notice').props.children).toContain(notice.title);
    expect(view.getByTestId('appointment-apt-1')).toBeTruthy();
  });

  it('keeps the two-pane layout when a large window is in landscape', async () => {
    mockWindow = { width: 1024, height: 768, scale: 2, fontScale: 2 };
    const view = await renderScreen();
    expect(view.getByTestId('appointment-content').props.style).toEqual(
      expect.objectContaining({ flexDirection: 'row', flexWrap: 'wrap' }),
    );
    expectLabelsPreserved(view);
  });

  it('mirrors the panes and keeps Arabic labels under right-to-left', async () => {
    mockWindow = { width: 1024, height: 768, scale: 2, fontScale: 2 };
    const view = await renderScreen('ar');

    expect(view.getByTestId('appointment-list').props.style).toEqual(
      expect.arrayContaining([expect.objectContaining({ direction: 'rtl' })]),
    );
    // The pane gutter (flexDirection 'row' + logical marginStart) mirrors with
    // the native layout direction, so the panes swap sides under RTL.
    expect(view.getByTestId('appointment-content').props.style).toEqual(
      expect.objectContaining({ flexDirection: 'row' }),
    );
    // Safe-area insets are physical edges. The one direction-aware mapping in
    // the screen swaps the inline pair so logical paddingStart still lands on
    // the right edge in Arabic (insets.right = 24) and paddingEnd on the left
    // (insets.left = 16). A notch therefore stays on its physical side.
    expect(view.getByTestId('appointment-content').props.style).toEqual(
      expect.objectContaining({ paddingStart: 24, paddingEnd: 16 }),
    );
    expect(view.getByTestId('appointment-list-title').props.children).toBe('المواعيد');
    expect(view.getByLabelText('عرض التفاصيل')).toBeTruthy();
    expect(view.getByLabelText('إلغاء الجهاز')).toBeTruthy();
    expect(view.getByLabelText('تأكيد الموعد')).toBeTruthy();
    expect(view.getByTestId('appointment-list-pane')).toBeTruthy();
    expect(view.getByTestId('appointment-detail-pane')).toBeTruthy();
  });

  it('does not shrink a confirmed value at 200 percent text', async () => {
    mockWindow = { width: 1024, height: 768, scale: 2, fontScale: 2 };
    const confirmed: AppointmentView = {
      ...pending,
      id: 'apt-2',
      key: 'org-a\u0000apt-2',
      confirmation: { kind: 'confirmed', confirmedAt: '2026-11-26T12:00:00Z' },
      confirmedLocal: '12:00',
    };
    const view = await renderScreen('en', [confirmed]);
    const value = view.getByTestId('confirmed-apt-2');
    expect(value.props.children).toContain('12:00');
    expect(value.props.numberOfLines).toBeUndefined();
    expect(value.props.adjustsFontSizeToFit).toBeUndefined();
    expect(value.props.maxFontSizeMultiplier).toBeUndefined();
    expect(view.queryByTestId('confirm-apt-2')).toBeNull();
  });

  it('preserves the empty state inside the list pane on a large window', async () => {
    mockWindow = { width: 1024, height: 768, scale: 2, fontScale: 2 };
    const view = await renderScreen('en', []);
    expect(view.getByTestId('empty-appointments')).toBeTruthy();
    expect(view.getByTestId('appointment-list-pane')).toBeTruthy();
    expect(view.getByTestId('appointment-detail-pane')).toBeTruthy();
  });
});
