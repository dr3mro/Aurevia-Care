/**
 * Screen and entry-point tests for patient handouts (PAT-02, CLN-07, UX-01).
 *
 * The screen is presentational; the client owns validation, release filtering
 * and download authorization. These tests trace a tap from the rendered card to
 * the exact handout version that reaches the authorization client.
 */
import { fireEvent, render } from '@testing-library/react-native';

import { confirmAndAuthorizeDownload } from './downloadFlow';
import { HandoutClient } from './handoutClient';
import { HandoutsScreen } from './HandoutsScreen';
import { handoutText } from './messages';
import type { HandoutAuthorization, ReleasedHandout } from './types';
import type { TokenStore } from '../reports/types';

const handout: ReleasedHandout = {
  handoutId: 'handout-1',
  version: 2,
  kind: 'prescription',
  issuingOrganization: 'Clinic A',
  releasedDate: '2026-06-10',
  language: 'en',
  releaseState: 'released',
  supersededBy: { version: 1, reason: 'dose corrected' },
};

function tokens(token: string | null): TokenStore {
  return {
    read: async () => token,
    save: async () => undefined,
    clear: async () => undefined,
  };
}

const authorization: HandoutAuthorization = {
  handoutId: 'handout-1',
  version: 2,
  documentId: 'document-2',
  checksum: 'checksum-2',
  language: 'en',
  patientId: 'patient-1',
  releaseState: 'current',
};

describe('HandoutsScreen (PAT-02, CLN-07)', () => {
  it('shows a load error and no empty state', async () => {
    const view = await render(
      <HandoutsScreen
        handouts={[]}
        error="Unable to load handouts"
        onView={() => undefined}
        onSave={() => undefined}
        onShare={() => undefined}
      />,
    );
    expect(view.getByLabelText('handout-error')).toHaveTextContent('Unable to load handouts');
    expect(view.queryByLabelText('no-handouts')).toBeNull();
  });

  it('shows an empty state when nothing was released', async () => {
    const view = await render(
      <HandoutsScreen
        handouts={[]}
        error={null}
        onView={() => undefined}
        onSave={() => undefined}
        onShare={() => undefined}
      />,
    );
    expect(view.getByLabelText('no-handouts')).toHaveTextContent(handoutText('en', 'noHandouts'));
    expect(view.queryByLabelText('handout-error')).toBeNull();
  });

  it('shows kind, issuer, date, version, correction and view/save/share actions', async () => {
    const viewed: ReleasedHandout[] = [];
    const saved: ReleasedHandout[] = [];
    const shared: ReleasedHandout[] = [];
    const view = await render(
      <HandoutsScreen
        handouts={[handout]}
        error={null}
        onView={(item) => viewed.push(item)}
        onSave={(item) => saved.push(item)}
        onShare={(item) => shared.push(item)}
      />,
    );
    expect(view.getByTestId('handouts-title')).toHaveTextContent(handoutText('en', 'handoutsTitle'));
    expect(view.getByLabelText('handout-kind')).toHaveTextContent('Prescription');
    expect(view.getByLabelText('issuer')).toHaveTextContent('Clinic A');
    expect(view.getByLabelText('released-date')).toHaveTextContent('Released 2026-06-10');
    expect(view.getByTestId('version-handout-1')).toHaveTextContent('Version 2');
    expect(view.getByTestId('corrected-handout-1')).toHaveTextContent(/Corrected/);
    expect(view.getByLabelText('correction')).toHaveTextContent('Replaced by version 1 — dose corrected');
    expect(view.getByLabelText('language')).toHaveTextContent('LTR');
    expect(view.getByLabelText('handout-handout-1').props.style.direction).toBe('ltr');

    await fireEvent.press(view.getByTestId('view-handout-1'));
    await fireEvent.press(view.getByTestId('save-handout-1'));
    await fireEvent.press(view.getByTestId('share-handout-1'));
    expect(viewed).toEqual([handout]);
    expect(saved).toEqual([handout]);
    expect(shared).toEqual([handout]);
  });

  it('renders an Arabic original right-to-left with a mirrored tab bar', async () => {
    const original: ReleasedHandout = { ...handout, supersededBy: null, language: 'ar', kind: 'chart-summary' };
    const tabs: string[] = [];
    const view = await render(
      <HandoutsScreen
        handouts={[original]}
        error={null}
        onView={() => undefined}
        onSave={() => undefined}
        onShare={() => undefined}
        locale="ar"
        showTabBar
        activeTab="reports"
        onTab={(tab) => tabs.push(tab)}
      />,
    );
    expect(view.getByTestId('handouts-title')).toHaveTextContent(handoutText('ar', 'handoutsTitle'));
    expect(view.queryByLabelText('correction')).toBeNull();
    expect(view.queryByTestId('corrected-handout-1')).toBeNull();
    expect(view.getByLabelText('handout-kind')).toHaveTextContent(handoutText('ar', 'chartSummary'));
    expect(view.getByLabelText('language')).toHaveTextContent('RTL');
    expect(view.getByLabelText('handout-handout-1').props.style.direction).toBe('rtl');
    expect(view.getByTestId('save-handout-1').props.accessibilityLabel).toBe(handoutText('ar', 'saveCopy'));
    expect(view.getByLabelText('التقارير tab')).toBeTruthy();
    await fireEvent.press(view.getByTestId('tab-button-reports'));
    expect(tabs).toEqual(['reports']);
  });
});

describe('handout entry-point to authorization (PAT-02)', () => {
  it('omits withheld handouts from the rendered list', async () => {
    const body = [
      {
        handoutId: 'handout-1',
        version: 2,
        kind: 'prescription',
        issuingOrganization: 'Clinic A',
        releasedDate: '2026-06-10',
        language: 'en',
        releaseState: 'released',
        supersededByVersion: null,
        supersededByReason: null,
      },
      {
        handoutId: 'handout-2',
        version: 1,
        kind: 'order-sheet',
        issuingOrganization: 'Clinic A',
        releasedDate: '2026-06-11',
        language: 'en',
        releaseState: 'withheld',
        supersededByVersion: null,
        supersededByReason: null,
      },
    ];
    const client = new HandoutClient(
      { getJson: async () => body, postJson: async () => authorization },
      tokens('token-1'),
      'patient-1',
    );
    const handouts = await client.listHandouts();
    const view = await render(
      <HandoutsScreen
        handouts={handouts}
        error={null}
        onView={() => undefined}
        onSave={() => undefined}
        onShare={() => undefined}
      />,
    );
    expect(view.queryByLabelText('handout-handout-1')).toBeTruthy();
    expect(view.queryByLabelText('handout-handout-2')).toBeNull();
  });

  it('authorizes the exact version tapped through the save action', async () => {
    const calls: [string, number][] = [];
    const client = {
      authorizeDownload: async (handoutId: string, version: number): Promise<HandoutAuthorization> => {
        calls.push([handoutId, version]);
        return authorization;
      },
    };
    let pending: Promise<unknown> | undefined;
    const view = await render(
      <HandoutsScreen
        handouts={[handout]}
        error={null}
        onView={() => undefined}
        onSave={(item) => {
          pending = confirmAndAuthorizeDownload(client, item, async () => 'save');
        }}
        onShare={() => undefined}
      />,
    );
    await fireEvent.press(view.getByTestId('save-handout-1'));
    await pending;
    expect(calls).toEqual([['handout-1', 2]]);
  });
});
