import { render } from '@testing-library/react-native';

import { TrackerScreen } from './TrackerScreen';
import type { TrackerItem } from './types';

const labItem: TrackerItem = {
  itemId: 'lab-1',
  kind: 'lab_order',
  steps: [
    { key: 'placed', releasedToPatient: true },
    { key: 'collected_or_scheduled', releasedToPatient: true },
    { key: 'accepted', releasedToPatient: false },
    { key: 'released', releasedToPatient: true },
  ],
};

describe('TrackerScreen "Where is it?" (PAT-02, XCH-03)', () => {
  it('shows a plain-word step line and omits hidden steps', async () => {
    const view = await render(<TrackerScreen items={[labItem]} locale="en" />);
    expect(view.getByTestId('tracker-step-lab-1-placed')).toHaveTextContent('Ordered');
    expect(view.getByTestId('tracker-step-lab-1-collected_or_scheduled')).toHaveTextContent('Sample taken');
    expect(view.getByTestId('tracker-step-lab-1-released')).toHaveTextContent('Released to you');
    expect(view.queryByTestId('tracker-step-lab-1-accepted')).toBeNull();
    expect(view.getByLabelText('tracker-item-title')).toHaveTextContent('Lab test');
    expect(view.getByTestId('tracker-steps-lab-1').props.style.flexDirection).toBe('row');
  });

  it('renders one card per item', async () => {
    const prescription: TrackerItem = {
      itemId: 'rx-1',
      kind: 'prescription',
      steps: [{ key: 'placed', releasedToPatient: true }],
    };
    const view = await render(<TrackerScreen items={[labItem, prescription]} locale="en" />);
    expect(view.getByTestId('tracker-item-lab-1')).toBeTruthy();
    expect(view.getByTestId('tracker-item-rx-1')).toBeTruthy();
  });

  it('resolves the locale from the device direction when no locale is supplied', async () => {
    const view = await render(<TrackerScreen items={[labItem]} />);
    expect(view.getByTestId('tracker-step-lab-1-placed')).toBeTruthy();
  });

  it('shows an empty state and nothing stale after sign-out clearing', async () => {
    const view = await render(<TrackerScreen items={[labItem]} locale="en" />);
    expect(view.getByTestId('tracker-step-lab-1-placed')).toBeTruthy();
    // Sign-out removes every tracked item; the same screen now has nothing to show.
    await view.rerender(<TrackerScreen items={[]} locale="en" />);
    expect(view.getByLabelText('tracker-empty')).toHaveTextContent('Nothing to track right now');
    expect(view.queryByTestId('tracker-item-lab-1')).toBeNull();
  });

  it('renders Arabic plain words with right-to-left layout', async () => {
    const view = await render(<TrackerScreen items={[labItem]} locale="ar" />);
    expect(view.getByTestId('tracker')).toBeTruthy();
    expect(view.getByTestId('tracker').props.style).toEqual(
      expect.arrayContaining([expect.objectContaining({ direction: 'rtl' })]),
    );
    expect(view.getByTestId('tracker-steps-lab-1').props.style.flexDirection).toBe('row-reverse');
    expect(view.getByTestId('tracker-step-lab-1-placed')).toHaveTextContent('تم الطلب');
    expect(view.getByTestId('tracker-step-lab-1-released')).toHaveTextContent('أُتيحت لك');
    expect(view.queryByLabelText('tracker-empty')).toBeNull();
  });

  it('renders an empty step line without a step when nothing is released', async () => {
    const hidden: TrackerItem = {
      itemId: 'hidden-1',
      kind: 'lab_order',
      steps: [{ key: 'resulted', releasedToPatient: false }],
    };
    const view = await render(<TrackerScreen items={[hidden]} locale="en" />);
    expect(view.getByTestId('tracker-item-hidden-1')).toBeTruthy();
    expect(view.getByTestId('tracker-steps-hidden-1')).toBeTruthy();
    expect(view.queryByTestId('tracker-step-hidden-1-resulted')).toBeNull();
  });
});
