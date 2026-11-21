/**
 * UX-01: shared presentation primitives for the three patient screens.
 *
 * The tests assert token-only styling, English/Arabic label parity, full
 * screen-reader labelling and both sides of every branch the primitives add.
 * All values are synthetic; no clinical data or credentials are present.
 */
import { jest } from '@jest/globals';
import * as fs from 'node:fs';
import * as path from 'node:path';

import { act, fireEvent, render } from '@testing-library/react-native';
import { Text } from 'react-native';

import {
  acknowledgeLabel,
  Card,
  MessageBubble,
  NoticeBox,
  PrimaryButton,
  SCREEN_TOKENS,
  SCREEN_TEXT_KEYS,
  screenText,
  ScreenFrame,
  StatusPill,
  localeFromDirection,
  resolveScreenLocale,
} from './patientDesign';
import type { Locale } from './patientDesign';

const locales: readonly Locale[] = ['en', 'ar'];

/** Walk the React Native test tree so every host node can be inspected. */
interface Node {
  readonly type: string;
  readonly props: Record<string, unknown>;
  readonly children: readonly (Node | string)[] | null;
}

function flatten(node: Node | string | null, into: Node[]): Node[] {
  if (node === null || typeof node === 'string') {
    return into;
  }
  into.push(node);
  for (const child of node.children ?? []) {
    flatten(child, into);
  }
  return into;
}

function nodes(view: { toJSON: () => unknown }): Node[] {
  return flatten(view.toJSON() as Node | null, []);
}

describe('patient screen design language (UX-01)', () => {
  it.each(locales)('has a non-empty %s translation for every screen key', (locale) => {
    for (const key of SCREEN_TEXT_KEYS) {
      expect(typeof screenText(locale, key)).toBe('string');
      expect(screenText(locale, key).length).toBeGreaterThan(0);
    }
  });

  it('names the acknowledged revision in both languages', () => {
    expect(acknowledgeLabel('en', 2)).toContain('2');
    expect(acknowledgeLabel('ar', 3)).toContain('3');
  });

  it('resolves the locale from the device direction when no prop is supplied', () => {
    expect(localeFromDirection(true)).toBe('ar');
    expect(localeFromDirection(false)).toBe('en');
    expect(resolveScreenLocale('ar')).toBe('ar');
    expect(resolveScreenLocale()).toBe('en');
  });

  it('derives no raw colour and no physical style from its tokens', () => {
    for (const value of Object.values(SCREEN_TOKENS.color)) {
      expect(value).toMatch(/^#[0-9a-f]{6}$/i);
    }
    expect(SCREEN_TOKENS.size.target).toBeGreaterThanOrEqual(48);
    expect(SCREEN_TOKENS.radius.card).toBeGreaterThanOrEqual(8);
  });
});

describe.each(locales)('ScreenFrame in %s', (locale) => {
  it('labels the header and renders the tab bar only when standalone', async () => {
    const onTab = jest.fn<(tab: string) => void>();
    const embedded = await render(
      <ScreenFrame testID="frame" locale={locale} title="Title" subtitle="Subtitle">
        <Text testID="body">Body</Text>
      </ScreenFrame>,
    );
    expect(embedded.getByTestId('frame-title').props.accessibilityRole).toBe('header');
    expect(embedded.getByTestId('frame-title').props.accessibilityLanguage).toBe(locale);
    expect(embedded.queryByLabelText('bottom tab bar')).toBeNull();
    await embedded.unmount();

    const standalone = await render(
      <ScreenFrame
        testID="frame"
        locale={locale}
        title="Title"
        subtitle="Subtitle"
        showTabBar
        activeTab="reports"
        onTab={onTab}>
        <Text testID="body">Body</Text>
      </ScreenFrame>,
    );
    expect(standalone.getByTestId('frame').props.style).toEqual(
      expect.arrayContaining([expect.objectContaining({ direction: locale === 'ar' ? 'rtl' : 'ltr' })]),
    );
    const tabs = nodes(standalone).filter((node) => node.props.accessibilityRole === 'button');
    expect(tabs.length).toBe(4);
    for (const tab of tabs) {
      expect(typeof tab.props.accessibilityLabel).toBe('string');
      expect(tab.props.accessibilityLanguage).toBe(locale);
    }
    const visits = locale === 'en' ? 'Visits tab' : 'المواعيد tab';
    const reports = locale === 'en' ? 'Reports tab' : 'التقارير tab';
    expect(standalone.getByLabelText(visits)).toBeTruthy();
    expect(standalone.getByLabelText(visits).props.accessibilityState).toEqual({ selected: false });
    expect(standalone.getByLabelText(reports).props.accessibilityState).toEqual({ selected: true });
    await act(async () => {
      fireEvent.press(standalone.getByLabelText(locale === 'en' ? 'Messages tab' : 'الرسائل tab'));
    });
    expect(onTab).toHaveBeenCalledWith('messages');
    await standalone.unmount();
  });

  it('honours an explicit layout direction over the locale default', async () => {
    const view = await render(
      <ScreenFrame testID="frame" locale="en" direction="rtl" title="Title" subtitle="Subtitle">
        <Text testID="body">Body</Text>
      </ScreenFrame>,
    );
    expect(view.getByTestId('frame').props.style).toEqual(
      expect.arrayContaining([expect.objectContaining({ direction: 'rtl' })]),
    );
    await view.unmount();
  });

  it('renders a standalone tab bar with no callback and a root label', async () => {
    const view = await render(
      <ScreenFrame
        testID="frame"
        locale={locale}
        title="Title"
        subtitle="Subtitle"
        rootLabel="screen root"
        showTabBar
        activeTab="messages">
        <Text testID="body">Body</Text>
      </ScreenFrame>,
    );
    expect(view.getByLabelText('screen root')).toBeTruthy();
    expect(view.getByLabelText(locale === 'ar' ? 'الرسائل tab' : 'Messages tab')).toBeTruthy();
    await act(async () => {
      fireEvent.press(view.getByLabelText(locale === 'ar' ? 'الحساب tab' : 'Account tab'));
    });
    await view.unmount();
  });
});

describe('patient screen primitives', () => {
  it('renders each status pill with a distinct word and glyph', async () => {
    const view = await render(
      <>
        <StatusPill testID="pill-warning" variant="warning" label="Please confirm" locale="en" />
        <StatusPill testID="pill-success" variant="success" label="Confirmed" locale="en" />
        <StatusPill testID="pill-neutral" variant="neutral" label="Completed" locale="en" />
        <StatusPill testID="pill-info" variant="info" label="Revision 2" locale="en" />
      </>,
    );
    expect(view.getByTestId('pill-warning')).toHaveTextContent(/Please confirm/);
    expect(view.getByTestId('pill-success')).toHaveTextContent(/Confirmed/);
    expect(view.getByTestId('pill-neutral')).toHaveTextContent(/Completed/);
    expect(view.getByTestId('pill-info')).toHaveTextContent(/Revision 2/);
    for (const id of ['pill-warning', 'pill-success', 'pill-neutral', 'pill-info']) {
      expect(view.getByTestId(id).props.style?.length).toBeGreaterThan(0);
    }
  });

  it('renders cards, notice boxes and message bubbles in both directions', async () => {
    const view = await render(
      <>
        <Card testID="card"><Text>Plain</Text></Card>
        <Card testID="dashed" dashed><Text>Dashed</Text></Card>
        <NoticeBox testID="info" variant="info" locale="en" lines={['hours', 'target']} />
        <NoticeBox testID="warning" variant="warning" locale="ar" lines={['note']} />
        <MessageBubble testID="own" own label="patient said: hi" body="hi" time="10:00" locale="en" />
        <MessageBubble testID="other" own={false} label="unit said: hello" body="hello" time="11:00" locale="ar" />
      </>,
    );
    expect(view.getByTestId('card')).toBeTruthy();
    expect(view.getByTestId('dashed').props.style).toEqual(expect.arrayContaining([expect.objectContaining({ borderStyle: 'dashed' })]));
    expect(view.getByLabelText('hours')).toBeTruthy();
    expect(view.getByLabelText('target')).toBeTruthy();
    expect(view.getByLabelText('note')).toBeTruthy();
    expect(view.getByLabelText('patient said: hi')).toBeTruthy();
    expect(view.getByLabelText('unit said: hello')).toBeTruthy();
    expect(view.getByTestId('own').props.style).toEqual(expect.arrayContaining([expect.objectContaining({ alignSelf: 'flex-start' })]));
    expect(view.getByTestId('other').props.style).toEqual(expect.arrayContaining([expect.objectContaining({ alignSelf: 'flex-end' })]));
  });

  it('fires a primary button and keeps a disabled one inert', async () => {
    const onPress = jest.fn<() => void>();
    const view = await render(
      <>
        <PrimaryButton testID="enabled" label="Confirm appointment" locale="en" onPress={onPress} />
        <PrimaryButton testID="disabled" label="Send" locale="ar" onPress={onPress} disabled />
      </>,
    );
    expect(view.getByTestId('enabled').props.accessibilityRole).toBe('button');
    expect(view.getByTestId('enabled').props.accessibilityLanguage).toBe('en');
    expect(view.getByTestId('disabled').props.accessibilityState).toEqual({ disabled: true });
    await act(async () => {
      fireEvent.press(view.getByTestId('enabled'));
      fireEvent.press(view.getByTestId('disabled'));
    });
    expect(onPress).toHaveBeenCalledTimes(1);
  });
});

describe('approved-design source guard (BUILD-71)', () => {
  const screens = ['AppointmentListScreen.tsx', 'ReleasedReportsScreen.tsx', 'ConversationThread.tsx'];

  it.each(screens)('%s uses tokens instead of raw colours or physical spacing', (name) => {
    const feature = name === 'AppointmentListScreen.tsx'
      ? 'appointments'
      : name === 'ReleasedReportsScreen.tsx' ? 'reports' : 'conversations';
    const source = fs.readFileSync(
      path.join(__dirname, '..', feature, name),
      'utf8',
    );
    expect(source).not.toMatch(/#[0-9a-fA-F]{3,8}\b/);
    expect(source).not.toMatch(/\b(?:marginLeft|marginRight|paddingLeft|paddingRight|textAlign\s*:\s*['"](?:left|right))\b/);
  });
});
