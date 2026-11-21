/**
 * Acceptance tests for the patient handout feature (PAT-02, CLN-07).
 *
 * Synthetic fixtures only. The tests exercise the client trust boundary, the
 * correction/RTL presentation rules, the transient store, the sign-out path and
 * the explicit save/share confirmation flow.
 */
import { jest } from '@jest/globals';
import { Alert } from 'react-native';
import type { AlertButton } from 'react-native';

import { clearToken } from '../../api/tokenStore';
import type { Locale } from '../appointments/patientDesign';
import type { TokenStore } from '../reports/types';

import { confirmAndAuthorizeDownload, createHandoutAlertConfirmer } from './downloadFlow';
import type { Confirmer } from './downloadFlow';
import { HandoutClient, loadHandouts } from './handoutClient';
import { HANDOUT_TEXT_KEYS, handoutText } from './messages';
import { correctedNote, isRtl, kindText, releasedOnly } from './presentation';
import { clearSessionHandouts, rememberHandouts, visibleHandouts } from './store';
import type {
  HandoutAuthorization,
  HandoutKind,
  ReleasedHandout,
} from './types';

/** Minimal call recorder: no mock library is needed to verify a boundary. */
function recorder<A extends unknown[], R>(impl: (...args: A) => R) {
  const calls: A[] = [];
  const fn = (...args: A): R => {
    calls.push(args);
    return impl(...args);
  };
  return { fn, calls };
}

/** Only persistent API the app uses, spied so a clinical write would be visible. */
jest.mock('expo-secure-store', () => ({
  setItemAsync: jest.fn(async (_key: string, _value: string) => undefined),
  getItemAsync: jest.fn(async () => null),
  deleteItemAsync: jest.fn(async (_key: string) => undefined),
}));

/** Raw server projection of one released handout. */
const payload = {
  handoutId: 'handout-1',
  version: 2,
  kind: 'prescription',
  issuingOrganization: 'Clinic A',
  releasedDate: '2026-06-10',
  language: 'en',
  releaseState: 'released',
  supersededByVersion: 1,
  supersededByReason: 'dose corrected',
};

/** Parsed patient-visible handout matching {@link payload}. */
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

const withheldPayload = { ...payload, handoutId: 'handout-2', releaseState: 'withheld' };

const authorizationPayload = {
  handoutId: 'handout-1',
  version: 2,
  documentId: 'document-2',
  checksum: 'checksum-2',
  language: 'en',
  patientId: 'patient-1',
  releaseState: 'current',
};

const authorization: HandoutAuthorization = {
  handoutId: 'handout-1',
  version: 2,
  documentId: 'document-2',
  checksum: 'checksum-2',
  language: 'en',
  patientId: 'patient-1',
  releaseState: 'current',
};

function tokens(token: string | null): TokenStore {
  return {
    read: async () => token,
    save: async () => undefined,
    clear: async () => undefined,
  };
}

function transport(getResult: unknown, postResult: unknown) {
  const get = recorder(async (_path: string, _token: string): Promise<unknown> => getResult);
  const post = recorder(
    async (_path: string, _token: string, _body: unknown): Promise<unknown> => postResult,
  );
  return {
    api: { getJson: get.fn, postJson: post.fn },
    getCalls: get.calls,
    postCalls: post.calls,
  };
}

beforeEach(() => {
  clearSessionHandouts();
});

describe('HandoutClient parsing and authorization (PAT-02, CLN-07)', () => {
  it('refuses an unverified patient identity at construction', () => {
    const api = transport([], authorizationPayload).api;
    expect(() => new HandoutClient(api, tokens('token-1'), '')).toThrow('INVALID_PATIENT');
    expect(() => new HandoutClient(api, tokens('token-1'), '   ')).toThrow('INVALID_PATIENT');
  });

  it('requires a protected credential for both commands', async () => {
    const fake = transport([], authorizationPayload);
    const client = new HandoutClient(fake.api, tokens(null), 'patient-1');
    await expect(client.listHandouts()).rejects.toThrow('NOT_AUTHENTICATED');
    await expect(client.authorizeDownload('handout-1', 2)).rejects.toThrow('NOT_AUTHENTICATED');
    expect(fake.getCalls.length).toBe(0);
    expect(fake.postCalls.length).toBe(0);
  });

  it('lists handouts with the stored token and holds no local cache', async () => {
    const fake = transport([payload], undefined);
    const client = new HandoutClient(fake.api, tokens('token-1'), 'patient-1');
    await expect(client.listHandouts()).resolves.toEqual([handout]);
    expect(fake.getCalls[0]).toEqual(['/api/v1/handouts', 'token-1']);
    await expect(client.listHandouts()).resolves.toEqual([handout]);
    expect(fake.getCalls.length).toBe(2);
  });

  it('omits withheld handouts from the patient list', async () => {
    const fake = transport([payload, withheldPayload], undefined);
    const client = new HandoutClient(fake.api, tokens('token-1'), 'patient-1');
    await expect(client.listHandouts()).resolves.toEqual([handout]);
  });

  it('rejects a non-array list response', async () => {
    const client = new HandoutClient(transport({}, undefined).api, tokens('token-1'), 'patient-1');
    await expect(client.listHandouts()).rejects.toThrow('MALFORMED_RESPONSE');
  });

  it('rejects every malformed handout shape', async () => {
    const invalid: unknown[] = [
      null,
      'not an object',
      [],
      { ...payload, handoutId: '' },
      { ...payload, handoutId: 7 },
      { ...payload, version: 0 },
      { ...payload, version: 1.5 },
      { ...payload, version: '2' },
      { ...payload, kind: 'lab-result' },
      { ...payload, issuingOrganization: '' },
      { ...payload, releasedDate: 5 },
      { ...payload, releasedDate: '10-06-2026' },
      { ...payload, language: 'de' },
      { ...payload, releaseState: 'unknown' },
      { ...payload, supersededByVersion: 1, supersededByReason: '' },
      { ...payload, supersededByVersion: 0, supersededByReason: 'reason' },
      { ...payload, supersededByVersion: 1, supersededByReason: null },
      { ...payload, supersededByVersion: null, supersededByReason: 'reason' },
      { ...payload, supersededByVersion: 'one', supersededByReason: 'reason' },
    ];
    for (const item of invalid) {
      const client = new HandoutClient(transport([item], undefined).api, tokens('token-1'), 'patient-1');
      await expect(client.listHandouts()).rejects.toThrow('MALFORMED_HANDOUT');
    }
  });

  it('accepts an original handout and an Arabic handout in every kind', async () => {
    const original = { ...payload, supersededByVersion: null, supersededByReason: null };
    const arabic = { ...original, handoutId: 'handout-2', language: 'ar', kind: 'chart-summary' };
    const orderSheet = { ...original, handoutId: 'handout-3', kind: 'order-sheet' };
    const client = new HandoutClient(
      transport([original, arabic, orderSheet], undefined).api,
      tokens('token-1'),
      'patient-1',
    );
    const listed = await client.listHandouts();
    expect(listed.map((item) => item.supersededBy)).toEqual([null, null, null]);
    expect(listed.map((item) => item.kind)).toEqual<HandoutKind[]>([
      'prescription',
      'chart-summary',
      'order-sheet',
    ]);
    expect(listed[1]?.language).toBe('ar');
  });

  it('validates download inputs before any request', async () => {
    const fake = transport([], authorizationPayload);
    const client = new HandoutClient(fake.api, tokens('token-1'), 'patient-1');
    await expect(client.authorizeDownload('', 2)).rejects.toThrow('INVALID_HANDOUT_ID');
    await expect(client.authorizeDownload('handout-1', 0)).rejects.toThrow('INVALID_VERSION');
    await expect(client.authorizeDownload('handout-1', 1.2)).rejects.toThrow('INVALID_VERSION');
    expect(fake.postCalls.length).toBe(0);
  });

  it('authorizes a download through the server with the exact version', async () => {
    const fake = transport([], authorizationPayload);
    const client = new HandoutClient(fake.api, tokens('token-1'), 'patient-1');
    await expect(client.authorizeDownload('handout-1', 2)).resolves.toEqual(authorization);
    expect(fake.postCalls[0]).toEqual([
      '/api/v1/handouts/handout-1/download',
      'token-1',
      { version: 2 },
    ]);
  });

  it('rejects every malformed authorization shape', async () => {
    const invalid: unknown[] = [
      'not an object',
      { ...authorizationPayload, handoutId: '' },
      { ...authorizationPayload, version: 0 },
      { ...authorizationPayload, documentId: '' },
      { ...authorizationPayload, checksum: '' },
      { ...authorizationPayload, language: 'de' },
      { ...authorizationPayload, patientId: '' },
      { ...authorizationPayload, patientId: 7 },
      { ...authorizationPayload, releaseState: 'pending' },
      { ...authorizationPayload, releaseState: null },
    ];
    for (const item of invalid) {
      const client = new HandoutClient(transport([], item).api, tokens('token-1'), 'patient-1');
      await expect(client.authorizeDownload('handout-1', 2)).rejects.toThrow('MALFORMED_AUTHORIZATION');
    }
  });

  it('rejects a well-formed authorization for another handout or version', async () => {
    const otherHandout = new HandoutClient(
      transport([], { ...authorizationPayload, handoutId: 'handout-2' }).api,
      tokens('token-1'),
      'patient-1',
    );
    await expect(otherHandout.authorizeDownload('handout-1', 2)).rejects.toThrow('AUTHORIZATION_MISMATCH');

    const otherVersion = new HandoutClient(
      transport([], { ...authorizationPayload, version: 3 }).api,
      tokens('token-1'),
      'patient-1',
    );
    await expect(otherVersion.authorizeDownload('handout-1', 2)).rejects.toThrow('AUTHORIZATION_MISMATCH');
  });

  it('stops access when the current release has been withdrawn', async () => {
    const fake = transport([], { ...authorizationPayload, releaseState: 'withdrawn' });
    const client = new HandoutClient(fake.api, tokens('token-1'), 'patient-1');
    await expect(client.authorizeDownload('handout-1', 2)).rejects.toThrow('RELEASE_UNAVAILABLE');
    expect(fake.postCalls.length).toBe(1);
  });

  it('denies a download authorized for a different linked patient', async () => {
    const fake = transport([], { ...authorizationPayload, patientId: 'patient-2' });
    const client = new HandoutClient(fake.api, tokens('token-1'), 'patient-1');
    await expect(client.authorizeDownload('handout-1', 2)).rejects.toThrow('WRONG_PATIENT');
    expect(fake.postCalls.length).toBe(1);
  });
});

describe('loadHandouts (PAT-02)', () => {
  it('returns loaded handouts without an error', async () => {
    const client = new HandoutClient(transport([payload], undefined).api, tokens('token-1'), 'patient-1');
    await expect(loadHandouts(client)).resolves.toEqual({ handouts: [handout], error: null });
  });

  it('reports a load failure without keeping partial clinical data', async () => {
    const get = recorder(async () => {
      throw new Error('network');
    });
    const post = recorder(async () => undefined);
    const client = new HandoutClient(
      { getJson: get.fn, postJson: post.fn },
      tokens('token-1'),
      'patient-1',
    );
    await expect(loadHandouts(client)).resolves.toEqual({
      handouts: [],
      error: 'Unable to load handouts',
    });
  });

  it('reports the load failure in the active locale', async () => {
    const get = recorder(async () => {
      throw new Error('network');
    });
    const post = recorder(async () => undefined);
    const client = new HandoutClient(
      { getJson: get.fn, postJson: post.fn },
      tokens('token-1'),
      'patient-1',
    );
    const locale: Locale = 'ar';
    const result = await loadHandouts(client, locale);
    expect(result.handouts).toEqual([]);
    expect(result.error).toBe(handoutText(locale, 'loadError'));
  });
});

describe('handout presentation (PAT-02, CLN-07, UX-01)', () => {
  it('carries an identical message key set in English and Arabic', () => {
    for (const key of HANDOUT_TEXT_KEYS) {
      expect(handoutText('en', key).length).toBeGreaterThan(0);
      expect(handoutText('ar', key).length).toBeGreaterThan(0);
    }
    expect(HANDOUT_TEXT_KEYS.length).toBeGreaterThan(0);
  });

  it('marks a replaced version with correction context in both languages', () => {
    expect(correctedNote('en', handout)).toBe('Replaced by version 1 — dose corrected');
    expect(correctedNote('ar', handout)).toBe('استُبدلت بالنسخة 1 — dose corrected');
    expect(correctedNote('en', { ...handout, supersededBy: null })).toBeNull();
  });

  it('marks Arabic handouts for RTL layout', () => {
    expect(isRtl({ ...handout, language: 'ar' })).toBe(true);
    expect(isRtl(handout)).toBe(false);
  });

  it('labels each kind in the active locale', () => {
    expect(kindText('en', 'prescription')).toBe('Prescription');
    expect(kindText('en', 'order-sheet')).toBe('Order sheet');
    expect(kindText('en', 'chart-summary')).toBe('Chart summary');
    expect(kindText('ar', 'prescription')).toBe(handoutText('ar', 'prescription'));
  });

  it('removes withheld handouts from a loaded list', () => {
    const withheld: ReleasedHandout = { ...handout, handoutId: 'handout-2', releaseState: 'withheld' };
    expect(releasedOnly([handout, withheld])).toEqual([handout]);
    expect(releasedOnly([])).toEqual([]);
  });
});

describe('transient handout store and sign-out (PAT-02)', () => {
  it('returns an empty list before anything is remembered', () => {
    expect(visibleHandouts()).toEqual([]);
  });

  it('holds handouts in memory and discards them on sign-out', async () => {
    rememberHandouts([handout]);
    expect(visibleHandouts()).toEqual([handout]);

    await clearToken();

    expect(visibleHandouts()).toEqual([]);
  });

  it('exposes an explicit sign-out hook for embedders', () => {
    rememberHandouts([handout]);
    clearSessionHandouts();
    expect(visibleHandouts()).toEqual([]);
  });
});

describe('save or share confirmation (PAT-02)', () => {
  function authorizer() {
    const calls: [string, number][] = [];
    const client = {
      authorizeDownload: async (handoutId: string, version: number): Promise<HandoutAuthorization> => {
        calls.push([handoutId, version]);
        return authorization;
      },
    };
    return { client, calls };
  }

  it('does not authorize when the patient cancels', async () => {
    const fake = authorizer();
    const messages: string[] = [];
    const confirm: Confirmer = async (message) => {
      messages.push(message);
      return 'cancel';
    };
    await expect(confirmAndAuthorizeDownload(fake.client, handout, confirm)).resolves.toBeNull();
    expect(fake.calls.length).toBe(0);
    expect(messages[0]).toBe('Save or share handout handout-1 version 2?');
  });

  it('authorizes the exact version for a save intent', async () => {
    const fake = authorizer();
    const result = await confirmAndAuthorizeDownload(fake.client, handout, async () => 'save');
    expect(result).toEqual({ authorization, intent: 'save' });
    expect(fake.calls[0]).toEqual(['handout-1', 2]);
  });

  it('authorizes the exact version for a share intent', async () => {
    const fake = authorizer();
    const result = await confirmAndAuthorizeDownload(fake.client, handout, async () => 'share');
    expect(result).toEqual({ authorization, intent: 'share' });
    expect(fake.calls[0]).toEqual(['handout-1', 2]);
  });
});

describe('createHandoutAlertConfirmer (PAT-02)', () => {
  const originalAlert = Alert.alert;

  afterEach(() => {
    Alert.alert = originalAlert;
  });

  it('resolves the intent chosen by the patient and carries only a generic title', async () => {
    let buttons: AlertButton[] | undefined;
    let title: unknown;
    Alert.alert = (alertTitle, _message, alertButtons) => {
      title = alertTitle;
      buttons = alertButtons;
    };
    const pending = createHandoutAlertConfirmer()('Save or share handout handout-1 version 2?');
    expect(title).toBe('Handout copy');
    expect(buttons?.length).toBe(3);
    buttons?.[2]?.onPress?.();
    await expect(pending).resolves.toBe('save');
  });

  it('resolves share and cancel from the native buttons', async () => {
    let buttons: AlertButton[] | undefined;
    Alert.alert = (_title, _message, alertButtons) => {
      buttons = alertButtons;
    };
    const share = createHandoutAlertConfirmer()('message');
    buttons?.[1]?.onPress?.();
    await expect(share).resolves.toBe('share');

    const cancel = createHandoutAlertConfirmer()('message');
    buttons?.[0]?.onPress?.();
    await expect(cancel).resolves.toBe('cancel');
  });
});
