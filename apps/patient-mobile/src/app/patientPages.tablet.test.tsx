/**
 * PAT-01, PAT-02, UX-01: responsive-frame acceptance for the data-backed
 * patient tabs. Every page is rendered at each window class, orientation and
 * system font scale; the test asserts the frame structure, safe-area insets,
 * unchanged screen-reader labels and the right-to-left mirror.
 *
 * All payloads are synthetic. No real patient data, credential or clinical
 * narrative appears in this file.
 */
import { jest } from '@jest/globals';
import { render, waitFor } from '@testing-library/react-native';
import { createElement } from 'react';
import type { ReactElement } from 'react';
import { Dimensions, StyleSheet } from 'react-native';
import type { StyleProp, ViewStyle } from 'react-native';

import type { Transport } from '../api/transport';

import type { Locale } from './messages';
import type { WindowClass } from './layout/useWindowClass';
import {
  AppointmentListPage,
  ConversationInboxPage,
  ReleasedReportsPage,
} from './patientPages';
import type { PatientPageProps } from './patientPages';

/** Safe-area values exposed by the working headless safe-area mock. */
const mockInsets = { top: 12, right: 24, bottom: 36, left: 48 };

jest.mock('react-native-safe-area-context', () => ({
  useSafeAreaInsets: () => mockInsets,
}));

/**
 * The maximum reading width applied to the centred column on large windows.
 * Kept in sync with the production layout constant by the structural test.
 */
const EXPECTED_READING_MAX_WIDTH = 1120;

/** Synthetic window size consumed by React Native's dimension API. */
type WindowSize = { width: number; height: number; scale: number; fontScale: number };

/** Mutable window snapshot read by the dimension API at render time. */
let mockWindow: WindowSize = windowSnapshot('compact', false, 1);

/** Build one synthetic window snapshot with the four layout inputs. */
function windowSnapshot(
  windowClass: WindowClass,
  isLandscape: boolean,
  fontScale: number,
): WindowSize {
  const width = windowClass === 'compact' ? 390 : windowClass === 'regular' ? 700 : 1024;
  return {
    width,
    height: isLandscape ? 360 : 900,
    scale: 2,
    fontScale,
  };
}

/** Flatten a component's style prop into a single view style for assertions. */
function flattenedStyle(node: { props: { style?: StyleProp<ViewStyle> } }): ViewStyle {
  return StyleSheet.flatten(node.props.style) ?? {};
}

/** Synthetic server payloads: identifiers and text are invented for tests. */
const appointmentPayload = [{
  id: 'apt-1',
  version: 1,
  patientId: 'synthetic-patient',
  facilityCode: 'synthetic-facility',
  facilityTimeZone: 'Asia/Riyadh',
  startsAt: '2026-11-21T10:00:00Z',
  status: 'booked',
  confirmationEnabled: true,
  confirmedAt: null,
}];

const reportPayload = [{
  reportId: 'report-1',
  revision: 1,
  issuingOrganization: 'Synthetic clinic',
  effectiveDate: '2026-11-20',
  language: 'en',
  correctionReason: null,
  supersedesRevision: null,
}];

const inboxPayload = {
  entries: [{
    conversation: {
      id: 'conv-1',
      version: 1,
      patientId: 'synthetic-patient',
      patientSubject: 'synthetic-subject',
      unitKey: 'synthetic-lab',
      connectionId: 'conn-1',
      topic: 'follow up',
      state: 'awaiting_staff',
      assigneeSubject: null,
      patientReadSequence: 0,
      staffReadSequence: 1,
      createdAt: '2026-11-21T10:00:00Z',
    },
    unreadCount: 2,
  }],
};

/** Build an inert transport whose GET returns one synthetic payload. */
function fakeTransport(get: () => Promise<unknown>): Transport {
  return {
    get: jest.fn(get) as Transport['get'],
    post: jest.fn(async () => undefined) as Transport['post'],
    put: jest.fn(async () => undefined) as Transport['put'],
  };
}

interface PageCase {
  readonly name: string;
  readonly page: (props: PatientPageProps) => ReactElement;
  readonly pageId: string;
  readonly payload: unknown;
  readonly screenTestId: string;
  readonly englishLabel: string | undefined;
}

const pageCases: readonly PageCase[] = [
  {
    name: 'appointments',
    page: AppointmentListPage,
    pageId: 'appointments',
    payload: appointmentPayload,
    screenTestId: 'appointment-list',
    englishLabel: undefined,
  },
  {
    name: 'reports',
    page: ReleasedReportsPage,
    pageId: 'reports',
    payload: reportPayload,
    screenTestId: 'released-reports',
    englishLabel: 'released-reports',
  },
  {
    name: 'conversations',
    page: ConversationInboxPage,
    pageId: 'conversations',
    payload: inboxPayload,
    screenTestId: 'conversation-inbox',
    englishLabel: 'conversation inbox',
  },
];

/** Render one page with the current synthetic window snapshot. */
async function renderPage(pageCase: PageCase, locale: Locale) {
  const props: PatientPageProps = {
    transport: fakeTransport(async () => pageCase.payload),
    locale,
    organizationId: 'synthetic-organization',
    patientId: 'synthetic-patient',
    onSessionExpired: () => undefined,
  };
  return render(createElement(pageCase.page, props));
}

const windowCases = [
  { name: 'compact portrait', windowClass: 'compact' as const, isLandscape: false },
  { name: 'compact landscape', windowClass: 'compact' as const, isLandscape: true },
  { name: 'regular portrait', windowClass: 'regular' as const, isLandscape: false },
  { name: 'regular landscape', windowClass: 'regular' as const, isLandscape: true },
  { name: 'large portrait', windowClass: 'large' as const, isLandscape: false },
  { name: 'large landscape', windowClass: 'large' as const, isLandscape: true },
] as const;

const fontScales = [1, 1.5, 2] as const;

const layoutCases = windowCases.flatMap((windowCase) =>
  fontScales.map((fontScale) => ({ ...windowCase, fontScale })),
);

describe.each(pageCases)('$name data-backed tab responsive frame', (pageCase) => {
  beforeEach(() => {
    mockWindow = windowSnapshot('compact', false, 1);
    jest.spyOn(Dimensions, 'get').mockImplementation(() => mockWindow);
    jest.spyOn(Dimensions, 'addEventListener').mockImplementation(() => ({ remove: () => undefined }));
  });

  afterEach(() => {
    jest.restoreAllMocks();
  });

  it.each(layoutCases)(
    '$windowClass $isLandscape at $fontScale font scale',
    async ({ windowClass, isLandscape, fontScale }) => {
      mockWindow = windowSnapshot(windowClass, isLandscape, fontScale);
      const view = await renderPage(pageCase, 'en');
      await waitFor(() => expect(view.getByTestId(pageCase.screenTestId)).toBeTruthy());

      const frame = flattenedStyle(view.getByTestId(`${pageCase.pageId}-frame`));
      expect(frame.flex).toBe(1);
      expect(frame.paddingTop).toBe(mockInsets.top);
      expect(frame.paddingBottom).toBe(mockInsets.bottom);
      expect(frame.paddingStart).toBe(mockInsets.left);
      expect(frame.paddingEnd).toBe(mockInsets.right);
      expect(frame.height).toBeUndefined();
      expect(frame.maxHeight).toBeUndefined();

      const column = flattenedStyle(view.getByTestId(`${pageCase.pageId}-column`));
      expect(column.width).toBe('100%');
      expect(column.height).toBeUndefined();
      expect(column.maxHeight).toBeUndefined();
      expect(flattenedStyle(view.getByTestId(`${pageCase.pageId}-list-pane`)).minWidth).toBe(0);

      if (windowClass === 'large') {
        expect(column.maxWidth).toBe(EXPECTED_READING_MAX_WIDTH);
        expect(column.alignSelf).toBe('center');
        const panes = flattenedStyle(view.getByTestId(`${pageCase.pageId}-panes`));
        expect(panes.flexDirection).toBe('row');
        expect(panes.direction).toBe('ltr');
        const detail = view.getByTestId(`${pageCase.pageId}-detail-pane`);
        expect(flattenedStyle(detail).flex).toBe(1);
        expect(detail.props.children ?? null).toBeNull();
      } else {
        expect(column.maxWidth).toBeUndefined();
        expect(column.alignSelf).toBeUndefined();
        expect(view.queryByTestId(`${pageCase.pageId}-panes`)).toBeNull();
        expect(view.queryByTestId(`${pageCase.pageId}-detail-pane`)).toBeNull();
      }

      expect(view.getByTestId(pageCase.screenTestId).props.accessibilityLabel).toBe(
        pageCase.englishLabel,
      );
    },
  );

  it('keeps the two panes in reading order on a right-to-left large window', async () => {
    mockWindow = windowSnapshot('large', false, 2);
    const view = await renderPage(pageCase, 'ar');
    await waitFor(() => expect(view.getByTestId(pageCase.screenTestId)).toBeTruthy());

    const panes = flattenedStyle(view.getByTestId(`${pageCase.pageId}-panes`));
    expect(panes.flexDirection).toBe('row');
    expect(panes.direction).toBe('rtl');
    const frame = flattenedStyle(view.getByTestId(`${pageCase.pageId}-frame`));
    expect(frame.paddingStart).toBe(mockInsets.right);
    expect(frame.paddingEnd).toBe(mockInsets.left);
    expect(view.getByTestId(pageCase.screenTestId).props.accessibilityLabel).toBe(
      pageCase.englishLabel,
    );
  });
});

describe('patient screen-reader labels survive the responsive frame', () => {
  beforeEach(() => {
    mockWindow = windowSnapshot('compact', false, 1);
    jest.spyOn(Dimensions, 'get').mockImplementation(() => mockWindow);
    jest.spyOn(Dimensions, 'addEventListener').mockImplementation(() => ({ remove: () => undefined }));
  });

  afterEach(() => {
    jest.restoreAllMocks();
  });

  it('keeps the appointment detail action label', async () => {
    mockWindow = windowSnapshot('large', false, 1);
    const view = await renderPage(pageCases[0]!, 'en');
    await waitFor(() => expect(view.getByTestId('details-apt-1')).toBeTruthy());
    expect(view.getByTestId('details-apt-1').props.accessibilityLabel).toBe('View details');
  });

  it('keeps the report download action label on an Arabic landscape window', async () => {
    mockWindow = windowSnapshot('compact', true, 2);
    const view = await renderPage(pageCases[1]!, 'ar');
    await waitFor(() => expect(view.getByTestId('download-report-1')).toBeTruthy());
    expect(view.getByTestId('download-report-1').props.accessibilityLabel).toBe('Download PDF');
  });

  it('keeps the conversation row unread label', async () => {
    mockWindow = windowSnapshot('regular', false, 1.5);
    const view = await renderPage(pageCases[2]!, 'en');
    await waitFor(() => expect(view.getByLabelText('synthetic-lab: awaiting_staff, 2 unread')).toBeTruthy());
  });
});
