/**
 * The two nested "stacks" in the patient app are deliberately small and
 * presentational: a root menu, push, and back. These tests cover both sides of
 * every branch and every control label before the navigator composes them.
 */
import { fireEvent, render, waitFor } from '@testing-library/react-native';
import { Text } from 'react-native';

import { ScreenStack, type StackRoute } from './ScreenStack';

const routes: readonly StackRoute[] = [
  { key: 'root', label: 'Root menu', render: () => <Text testID="root-content">Root</Text> },
  { key: 'first', label: 'First screen', render: () => <Text testID="first-content">First</Text> },
  { key: 'second', label: 'Second screen', render: () => <Text testID="second-content">Second</Text> },
];

async function renderStack() {
  return await render(
    <ScreenStack
      testID="stack"
      title="Stack title"
      backLabel="Back"
      locale="en"
      rootKey="root"
      routes={routes}
    />,
  );
}

describe('ScreenStack', () => {
  it('renders the root content, a heading and a menu of pushed screens', async () => {
    const view = await renderStack();

    expect(view.getByText('Stack title')).toBeTruthy();
    expect(view.getByTestId('root-content')).toBeTruthy();
    expect(view.getByTestId('stack-open-first')).toBeTruthy();
    expect(view.getByTestId('stack-open-second')).toBeTruthy();
    expect(view.queryByTestId('stack-back')).toBeNull();
  });

  it('gives every menu control a role and an accessibility label', async () => {
    const view = await renderStack();

    expect(view.getByTestId('stack-open-first').props.accessibilityRole).toBe('button');
    expect(view.getByTestId('stack-open-first').props.accessibilityLabel).toBe('First screen');
    expect(view.getByTestId('stack-open-second').props.accessibilityRole).toBe('button');
    expect(view.getByTestId('stack-open-second').props.accessibilityLabel).toBe('Second screen');
  });

  it('pushes a screen and reveals a labelled back control', async () => {
    const view = await renderStack();

    fireEvent.press(view.getByTestId('stack-open-first'));

    await waitFor(() => expect(view.getByTestId('first-content')).toBeTruthy());
    expect(view.queryByTestId('root-content')).toBeNull();
    expect(view.queryByTestId('stack-open-first')).toBeNull();
    const back = view.getByTestId('stack-back');
    expect(back.props.accessibilityRole).toBe('button');
    expect(back.props.accessibilityLabel).toBe('Back');
  });

  it('pops back to the root menu', async () => {
    const view = await renderStack();

    fireEvent.press(view.getByTestId('stack-open-second'));
    await waitFor(() => expect(view.getByTestId('second-content')).toBeTruthy());

    fireEvent.press(view.getByTestId('stack-back'));

    await waitFor(() => expect(view.getByTestId('root-content')).toBeTruthy());
    expect(view.queryByTestId('stack-back')).toBeNull();
  });
});
