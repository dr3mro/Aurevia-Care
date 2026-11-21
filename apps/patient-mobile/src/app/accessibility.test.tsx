/**
 * UX-01: every patient-app-shell screen renders in English and Arabic with a
 * complete message key set, an explicit text language on every localized run
 * and an accessible role plus label on every control.
 *
 * These are headless component assertions: the native screen-reader passes on
 * iOS VoiceOver and Android TalkBack remain separate, named evidence items and
 * are not claimed by this test (docs/design/accessibility-patterns.md section
 * 10). All values below are synthetic.
 */
import { jest } from '@jest/globals';
import { fireEvent, render, waitFor } from '@testing-library/react-native';
import { Text } from 'react-native';

import { TransportError } from '../api/transport';

import { appMessageKeys, appText, type Locale } from './messages';
import { ScreenStack, type StackRoute } from './ScreenStack';
import { SignedOutScreen } from './SignedOutScreen';
import { screenMessages, ScreenStateView } from './screenStates';
import type { ResourceState } from './screenStates';
import { TAB_DEFINITIONS } from './tabs';

const locales: readonly Locale[] = ['en', 'ar'];

// Fixture labels come from the real bundle so a regression that hardcodes
// English in a route label or rendered run fails the Arabic case.
const routesFor = (locale: Locale): readonly StackRoute[] => [
  {
    key: 'root',
    label: appText(locale, 'accountEnrollment'),
    render: () => (
      <Text accessibilityLanguage={locale} testID="root-run">
        {appText(locale, 'accountRecovery')}
      </Text>
    ),
  },
  {
    key: 'next',
    label: appText(locale, 'accountSupport'),
    render: () => (
      <Text accessibilityLanguage={locale} testID="next-run">
        {appText(locale, 'accountLockout')}
      </Text>
    ),
  },
];

/** All non-ready states that the shared state view can present. */
const states: readonly ResourceState<never>[] = [
  { kind: 'loading' },
  { kind: 'empty' },
  { kind: 'error', error: new TransportError('server', { correlationId: 'corr-7' }) },
  { kind: 'offline', error: new TransportError('network', {}) },
  { kind: 'session-expired', error: new TransportError('unauthenticated', {}) },
];

/** One node of the React Native test renderer's JSON tree. */
interface RenderedNode {
  readonly type: string;
  readonly props: Record<string, unknown>;
  readonly children: readonly (RenderedNode | string)[] | null;
}

/** Flatten the rendered tree so every host node can be inspected. */
function flatten(node: RenderedNode | string | null, into: RenderedNode[]): RenderedNode[] {
  if (node === null || typeof node === 'string') {
    return into;
  }
  into.push(node);
  for (const child of node.children ?? []) {
    flatten(child, into);
  }
  return into;
}

/** Assert that a rendered control exposes everything a screen reader needs. */
function expectLabelledControls(nodes: readonly RenderedNode[], locale: Locale): void {
  const controls = nodes.filter((node) => node.props.accessibilityRole === 'button');
  expect(controls.length).toBeGreaterThan(0);
  for (const control of controls) {
    expect(typeof control.props.accessibilityLabel).toBe('string');
    expect(String(control.props.accessibilityLabel).length).toBeGreaterThan(0);
    expect(control.props.accessibilityLanguage).toBe(locale);
  }
}

/** Assert that every rendered text run declares the language of its script. */
function expectLocalizedRuns(nodes: readonly RenderedNode[]): void {
  const runs = nodes.filter((node) => node.type === 'Text');
  expect(runs.length).toBeGreaterThan(0);
  for (const run of runs) {
    expect(typeof run.props.accessibilityLanguage).toBe('string');
    expect(String(run.props.accessibilityLanguage).length).toBeGreaterThan(0);
  }
}

/** Read the flattened host tree of one render. */
function renderedNodes(view: { toJSON: () => unknown }): RenderedNode[] {
  return flatten(view.toJSON() as RenderedNode | null, []);
}

describe.each(locales)('patient shell accessibility in %s', (locale) => {
  it('has a non-empty translation for every application and tab key', () => {
    for (const key of appMessageKeys) {
      expect(appText(locale, key)).toBeTruthy();
    }
    for (const tab of TAB_DEFINITIONS) {
      expect(appText(locale, tab.labelKey)).toBeTruthy();
      expect(appText(locale, tab.accessibilityLabelKey)).toBeTruthy();
    }
  });

  it('labels every ScreenStack control and tags every text run at the root', async () => {
    const view = await render(
      <ScreenStack
        testID="stack"
        title={appText(locale, 'appTitle')}
        backLabel={appText(locale, 'back')}
        locale={locale}
        rootKey="root"
        routes={routesFor(locale)}
      />,
    );
    expectLabelledControls(renderedNodes(view), locale);
    expectLocalizedRuns(renderedNodes(view));
  });

  it('labels the pushed Back control and tags every run on the pushed screen', async () => {
    const view = await render(
      <ScreenStack
        testID="stack"
        title={appText(locale, 'appTitle')}
        backLabel={appText(locale, 'back')}
        locale={locale}
        rootKey="root"
        routes={routesFor(locale)}
      />,
    );
    await fireEvent.press(view.getByTestId('stack-open-next'));
    await waitFor(() => expect(view.getByTestId('next-run')).toBeTruthy());

    expect(view.getByTestId('next-run')).toHaveTextContent(appText(locale, 'accountLockout'));
    const back = view.getByTestId('stack-back');
    expect(back.props.accessibilityRole).toBe('button');
    expect(back.props.accessibilityLabel).toBe(appText(locale, 'back'));
    expect(back.props.accessibilityLanguage).toBe(locale);
    expectLabelledControls(renderedNodes(view), locale);
    expectLocalizedRuns(renderedNodes(view));
  });

  it('labels every SignedOutScreen control and tags every text run', async () => {
    const view = await render(<SignedOutScreen locale={locale} onSignIn={jest.fn()} />);
    expectLabelledControls(renderedNodes(view), locale);
    expectLocalizedRuns(renderedNodes(view));
  });

  it.each(states)('tags every text run in the %s state', async (state) => {
    const view = await render(
      <ScreenStateView
        state={state}
        messages={screenMessages(locale)}
        emptyMessage={appText(locale, 'emptyAppointments')}
        onRetry={jest.fn()}>
        {() => null}
      </ScreenStateView>,
    );
    expectLocalizedRuns(renderedNodes(view));
    if (state.kind === 'error' || state.kind === 'offline') {
      expectLabelledControls(renderedNodes(view), locale);
    }
  });
});

it.each(locales)('keeps the correlation identifier in its own Latin run in %s', async (locale) => {
  const view = await render(
    <ScreenStateView
      state={{ kind: 'error', error: new TransportError('server', { correlationId: 'corr-7' }) }}
      messages={screenMessages(locale)}
      emptyMessage={appText(locale, 'emptyAppointments')}>
      {() => null}
    </ScreenStateView>,
  );
  const identifier = view.getByTestId('screen-correlation-id');
  expect(identifier).toHaveTextContent('corr-7');
  expect(identifier.props.accessibilityLanguage).toBe('en');
  // Native long-press copy works without adding a clipboard dependency.
  expect(identifier.props.selectable).toBe(true);
  expect(view.getByTestId('screen-correlation')).toBeTruthy();
  expect(view.getByText(appText(locale, 'referencePrefix'))).toBeTruthy();
});
