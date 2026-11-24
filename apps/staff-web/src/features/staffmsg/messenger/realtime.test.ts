import { afterEach, describe, expect, it, vi } from 'vitest';
import { defined } from '../../../components/invariant';
import { alphaOrganizationId, facilityId, wireMessageEvent } from './fixtures';
import {
  consumeSseBuffer,
  createFetchStreamOpener,
  createMessengerRealtime,
  parseSseFrame,
} from './realtime';
import type { RealtimeDeps, StreamHandlers } from './realtime';
import type { ConnectionStatus, RealtimePage } from './types';

afterEach(() => {
  vi.unstubAllGlobals();
});

/** A fake scheduler that records callbacks instead of waiting on real timers. */
function fakeScheduler() {
  const pending: { callback: () => void; delayMs: number; cancelled: boolean }[] = [];
  return {
    pending,
    schedule: (callback: () => void, delayMs: number) => {
      const entry = { callback, delayMs, cancelled: false };
      pending.push(entry);
      return {
        cancel: () => {
          entry.cancelled = true;
        },
      };
    },
  };
}

/** A controllable stream fake used by the lifecycle tests. */
function fakeStream() {
  let handlers: StreamHandlers | undefined;
  let lastEventId: string | undefined;
  const signal: { aborted: boolean } = { aborted: false };
  const cleanup = { cancel: vi.fn() };
  const openStream = vi.fn((next: StreamHandlers, nextSignal: AbortSignal, id: string | undefined) => {
    handlers = next;
    lastEventId = id;
    nextSignal.addEventListener('abort', () => {
      signal.aborted = true;
    });
    return cleanup;
  });
  return {
    openStream,
    cleanup,
    signal,
    fireOpen: () => handlers?.onOpen(),
    fireFrame: () => handlers?.onFrame({ id: 'e', sequence: 1, type: 'message', conversationId: 'c', subject: 's' }),
    fireError: () => handlers?.onError(),
    lastEventId: () => lastEventId,
  };
}

/** Build dependency bundle around the supplied stream fake. */
function depsFor(
  stream: ReturnType<typeof fakeStream>,
  poll: RealtimeDeps['poll'] = vi.fn(() => Promise.resolve({ events: [], nextCursor: undefined })),
) {
  const scheduler = fakeScheduler();
  const statuses: ConnectionStatus[] = [];
  const deps: RealtimeDeps = {
    openStream: stream.openStream,
    poll,
    schedule: scheduler.schedule,
    onDataChanged: vi.fn(),
    onStatus: (status) => statuses.push(status),
  };
  return { deps, statuses, scheduler, poll };
}

describe('SSE buffer parsing', () => {
  it('splits complete frames from an incomplete remainder', () => {
    expect(consumeSseBuffer('data:one\n\ndata:two\n\npartial')).toEqual({
      frames: ['data:one', 'data:two'],
      remainder: 'partial',
    });
  });

  it('keeps a buffer with no boundary as the remainder', () => {
    expect(consumeSseBuffer('data:partial')).toEqual({ frames: [], remainder: 'data:partial' });
  });

  it('parses a valid data frame into a notification', () => {
    const frame = `event: message\ndata: ${JSON.stringify(wireMessageEvent)}`;
    expect(parseSseFrame(frame)).toMatchObject({ id: wireMessageEvent.id, type: 'message' });
  });

  it('ignores a frame with no data line', () => {
    expect(parseSseFrame('event: comment')).toBeUndefined();
  });

  it('ignores malformed JSON and a payload missing required identifiers', () => {
    expect(parseSseFrame('data: {not json')).toBeUndefined();
    expect(parseSseFrame('data: {"type":"message"}')).toBeUndefined();
  });

  it('ignores an unknown event kind and an unusable sequence', () => {
    const frame = (type: string, sequence: unknown) =>
      `data: ${JSON.stringify({ id: 'e', sequence, type, conversationId: 'c', subject: 's' })}`;
    expect(parseSseFrame(frame('bogus', 21))).toBeUndefined();
    expect(parseSseFrame(frame('message', 'not-a-number'))).toBeUndefined();
    expect(parseSseFrame(frame('message', Number.MAX_SAFE_INTEGER + 1))).toBeUndefined();
    expect(parseSseFrame(frame('message', -1))).toBeUndefined();
    expect(parseSseFrame(frame('read', 0))).toMatchObject({ id: 'e', type: 'read', sequence: 0 });
  });
});

describe('realtime lifecycle', () => {
  it('records stream-open, stream-lost, poll-succeeded and resynced in order', async () => {
    const stream = fakeStream();
    const poll = vi.fn((): Promise<RealtimePage> => Promise.resolve({ events: [], nextCursor: 21 }));
    const { deps, statuses, scheduler } = depsFor(stream, poll);
    const session = createMessengerRealtime(deps);

    session.start();
    stream.fireOpen();
    stream.fireError();
    await vi.waitFor(() => expect(statuses).toContain('polling'));
    session.reconnect();
    stream.fireOpen();

    expect(statuses).toEqual(['streaming', 'reconnecting', 'polling', 'streaming']);
    expect(session.status()).toBe('streaming');
    expect(poll).toHaveBeenCalledTimes(1);
    expect(stream.cleanup.cancel).toHaveBeenCalled();
    expect(scheduler.pending.some((entry) => !entry.cancelled)).toBe(false);
    session.stop();
  });

  it('advances the poll cursor and refetches on identifier events', async () => {
    const stream = fakeStream();
    const poll: RealtimeDeps['poll'] = vi.fn(() =>
      Promise.resolve({
        events: [{ id: 'e', sequence: 21, type: 'message' as const, conversationId: 'c', subject: 's' }],
        nextCursor: 21,
      }),
    );
    const { deps, statuses } = depsFor(stream, poll);
    const session = createMessengerRealtime(deps);
    session.start();
    stream.fireError();
    await vi.waitFor(() => expect(deps.onDataChanged).toHaveBeenCalledTimes(1));
    expect(statuses).toContain('polling');
    session.stop();
  });

  it('keeps the cursor when a polling page has no high-water mark', async () => {
    const stream = fakeStream();
    const poll = vi.fn((): Promise<RealtimePage> => Promise.resolve({ events: [], nextCursor: undefined }));
    const { deps } = depsFor(stream, poll);
    const session = createMessengerRealtime(deps);
    session.start();
    stream.fireError();
    await vi.waitFor(() => expect(poll).toHaveBeenCalledTimes(1));
    expect(deps.onDataChanged).not.toHaveBeenCalled();
    session.stop();
  });
});

describe('realtime lifecycle — polling fallback', () => {
  it('refetches once for a whole page of identifier events', async () => {
    const events = [1, 2, 3].map((sequence) => ({
      id: `synthetic-event-${sequence}`,
      sequence,
      type: 'message' as const,
      conversationId: 'c',
      subject: 's',
    }));
    const stream = fakeStream();
    const { deps, scheduler } = depsFor(
      stream,
      vi.fn(() => Promise.resolve({ events, nextCursor: 3 })),
    );
    const session = createMessengerRealtime(deps);
    session.start();
    stream.fireError();
    await vi.waitFor(() => expect(deps.onDataChanged).toHaveBeenCalledTimes(1));
    expect(scheduler.pending.some((entry) => !entry.cancelled)).toBe(true);
    session.stop();
  });

  it('resumes the next poll from the page high-water cursor', async () => {
    const stream = fakeStream();
    const poll = vi.fn((_cursor: string | undefined) =>
      Promise.resolve({
        events: [{ id: 'synthetic-event', sequence: 21, type: 'message' as const, conversationId: 'c', subject: 's' }],
        nextCursor: 21,
      }),
    );
    const { deps, scheduler } = depsFor(stream, poll);
    const session = createMessengerRealtime(deps);
    session.start();
    stream.fireError();
    await vi.waitFor(() => expect(poll).toHaveBeenCalledTimes(1));
    scheduler.pending.filter((entry) => !entry.cancelled).forEach((entry) => entry.callback());
    await vi.waitFor(() => expect(poll).toHaveBeenCalledTimes(2));
    expect(poll.mock.calls[1]?.[0]).toBe('21');
    session.stop();
  });

  it('discards an in-flight poll result once the stream reopens', async () => {
    const stream = fakeStream();
    const { deps, statuses, scheduler } = depsFor(
      stream,
      vi.fn(() =>
        Promise.resolve({
          events: [{ id: 'synthetic-event', sequence: 21, type: 'message' as const, conversationId: 'c', subject: 's' }],
          nextCursor: 21,
        }),
      ),
    );
    const session = createMessengerRealtime(deps);
    session.start();
    stream.fireOpen();
    stream.fireError();
    stream.fireOpen();
    await Promise.resolve();
    await Promise.resolve();
    expect(deps.onDataChanged).not.toHaveBeenCalled();
    expect(statuses).toEqual(['streaming', 'reconnecting', 'streaming']);
    expect(scheduler.pending.some((entry) => !entry.cancelled)).toBe(false);
    session.stop();
  });

  it('discards a failed poll once the stream reopens', async () => {
    const stream = fakeStream();
    let rejectPage: ((reason?: unknown) => void) | undefined;
    const poll = vi.fn(
      () =>
        new Promise<RealtimePage>((_resolve, reject) => {
          rejectPage = reject;
        }),
    );
    const { deps, scheduler } = depsFor(stream, poll);
    const session = createMessengerRealtime(deps);
    session.start();
    stream.fireError();
    stream.fireOpen();
    rejectPage?.(new Error('synthetic poll failure'));
    await Promise.resolve();
    await Promise.resolve();
    expect(scheduler.pending.some((entry) => !entry.cancelled)).toBe(false);
    session.stop();
  });
});

describe('realtime lifecycle — stream frames and stop guards', () => {
  it('records an active frame and resumes the stream from its sequence', () => {
    const stream = fakeStream();
    const { deps, statuses } = depsFor(stream);
    const session = createMessengerRealtime(deps);
    session.start();
    stream.fireFrame();
    expect(deps.onDataChanged).toHaveBeenCalledTimes(1);
    session.reconnect();
    stream.fireOpen();
    expect(stream.lastEventId()).toBe('1');
    expect(statuses).toContain('streaming');
    session.stop();
  });

  it('ignores scheduled polls, late results and frames after a stop', async () => {
    const stream = fakeStream();
    let resolvePage: ((page: RealtimePage) => void) | undefined;
    const poll = vi.fn(
      () =>
        new Promise<RealtimePage>((resolve) => {
          resolvePage = resolve;
        }),
    );
    const { deps, scheduler } = depsFor(stream, poll);
    const session = createMessengerRealtime(deps);
    session.start();
    stream.fireError();
    session.stop();
    resolvePage?.({ events: [], nextCursor: 3 });
    await Promise.resolve();
    await Promise.resolve();
    for (const entry of scheduler.pending) {
      entry.callback();
    }
    stream.fireFrame();
    expect(poll).toHaveBeenCalledTimes(1);
    expect(deps.onDataChanged).not.toHaveBeenCalled();
  });

});

describe('realtime lifecycle — polling and stop guards', () => {
  it('does not poll again when a scheduled retry fires after stop', async () => {
    const stream = fakeStream();
    const poll = vi.fn((): Promise<RealtimePage> => Promise.resolve({ events: [], nextCursor: 1 }));
    const { deps, scheduler } = depsFor(stream, poll);
    const session = createMessengerRealtime(deps);
    session.start();
    stream.fireError();
    await vi.waitFor(() => expect(scheduler.pending.length).toBe(1));
    session.stop();
    defined(scheduler.pending[0], 'scheduled poll').callback();
    expect(poll).toHaveBeenCalledTimes(1);
  });

  it('keeps reconnecting and reschedules when a poll fails', async () => {
    const stream = fakeStream();
    const poll: RealtimeDeps['poll'] = vi.fn(() =>
      Promise.reject(new Error('synthetic poll failure')),
    );
    const { deps, statuses, scheduler } = depsFor(stream, poll);
    const session = createMessengerRealtime(deps);
    session.start();
    stream.fireError();
    await vi.waitFor(() => expect(scheduler.pending.length).toBe(1));
    expect(statuses).toEqual(['reconnecting']);
    expect(session.status()).toBe('reconnecting');
    expect(scheduler.pending[0]?.delayMs).toBeGreaterThan(0);
    session.stop();
  });

  it('aborts the stream, cancels the reader and ignores late events on stop or tenant switch', async () => {
    const stream = fakeStream();
    const { deps, scheduler } = depsFor(stream);
    const session = createMessengerRealtime(deps);
    session.start();
    const abortSpy = vi.spyOn(stream.cleanup, 'cancel');
    session.stop();

    expect(stream.signal.aborted).toBe(true);
    expect(abortSpy).toHaveBeenCalled();
    expect(scheduler.pending.every((entry) => entry.cancelled)).toBe(true);

    stream.fireOpen();
    stream.fireFrame();
    stream.fireError();
    await Promise.resolve();
    expect(deps.onDataChanged).not.toHaveBeenCalled();
    expect(session.status()).toBe('reconnecting');
  });

  it('does not reschedule a poll that rejects after the session stopped', async () => {
    const stream = fakeStream();
    let rejectPoll: ((error: Error) => void) | undefined;
    const poll = vi.fn(
      () =>
        new Promise<RealtimePage>((_resolve, reject) => {
          rejectPoll = reject;
        }),
    );
    const { deps, scheduler } = depsFor(stream, poll);
    const session = createMessengerRealtime(deps);
    session.start();
    stream.fireError();
    session.stop();
    rejectPoll?.(new Error('synthetic late failure'));
    await Promise.resolve();
    await Promise.resolve();
    expect(scheduler.pending).toHaveLength(0);
  });

  it('does not reconnect a stopped session', () => {
    const stream = fakeStream();
    const { deps } = depsFor(stream);
    const session = createMessengerRealtime(deps);
    session.stop();
    session.reconnect();
    expect(stream.openStream).not.toHaveBeenCalled();
  });

  it('ignores a poll result or failure that arrives after the session stopped', async () => {
    let resolvePoll: ((page: RealtimePage) => void) | undefined;
    const poll = vi.fn(
      () =>
        new Promise<RealtimePage>((resolve) => {
          resolvePoll = resolve;
        }),
    );
    const stream = fakeStream();
    const { deps, statuses } = depsFor(stream, poll);
    const session = createMessengerRealtime(deps);
    session.start();
    stream.fireError();
    session.stop();
    resolvePoll?.({ events: [], nextCursor: 1 });
    await Promise.resolve();
    await Promise.resolve();
    expect(statuses).toEqual(['reconnecting']);
  });
});

describe('fetch stream opener', () => {
  function fakeFetch(response: unknown): typeof fetch {
    return vi.fn(() => Promise.resolve(response)) as unknown as typeof fetch;
  }

  it('opens the published stream with tenant headers and reads identifier frames', async () => {
    const chunk = `event: message\ndata: ${JSON.stringify(wireMessageEvent)}\n\nevent: noise\ndata: {malformed\n\n`;
    const readable = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(new TextEncoder().encode(chunk));
      },
    });
    const fetchFn = fakeFetch({ ok: true, body: readable });
    const handlers: StreamHandlers = {
      onOpen: vi.fn(),
      onFrame: vi.fn(),
      onError: vi.fn(),
    };
    const opener = createFetchStreamOpener(alphaOrganizationId, facilityId, fetchFn);
    const cleanup = opener(handlers, new AbortController().signal, '13');
    await vi.waitFor(() => expect(handlers.onFrame).toHaveBeenCalledTimes(1));
    expect(handlers.onOpen).toHaveBeenCalledTimes(1);
    const url = vi.mocked(fetchFn).mock.calls[0]?.[0];
    expect(url).toBe('/api/v1/staff/instant/events/stream?facilityId=facility-synthetic-main');
    const init = vi.mocked(fetchFn).mock.calls[0]?.[1];
    expect(init?.headers).toMatchObject({
      'X-Active-Organization': alphaOrganizationId,
      'Last-Event-ID': '13',
    });
    cleanup.cancel();
  });

  it('reports an error for a non-ok response, a missing body and a rejected fetch', async () => {
    for (const response of [{ ok: false, body: null }, { ok: true, body: null }]) {
      const handlers: StreamHandlers = { onOpen: vi.fn(), onFrame: vi.fn(), onError: vi.fn() };
      createFetchStreamOpener(alphaOrganizationId, facilityId, fakeFetch(response))(
        handlers,
        new AbortController().signal,
        undefined,
      );
      await vi.waitFor(() => expect(handlers.onError).toHaveBeenCalledTimes(1));
    }
    const handlers: StreamHandlers = { onOpen: vi.fn(), onFrame: vi.fn(), onError: vi.fn() };
    const failing = fakeFetch(null);
    vi.mocked(failing).mockRejectedValueOnce(new Error('synthetic network failure'));
    createFetchStreamOpener(alphaOrganizationId, facilityId, failing)(
      handlers,
      new AbortController().signal,
      undefined,
    );
    await vi.waitFor(() => expect(handlers.onError).toHaveBeenCalledTimes(1));
  });

  it('does not report an error when an aborted fetch rejects', async () => {
    const controller = new AbortController();
    controller.abort();
    const failing = fakeFetch(null);
    vi.mocked(failing).mockRejectedValueOnce(new Error('aborted'));
    const handlers: StreamHandlers = { onOpen: vi.fn(), onFrame: vi.fn(), onError: vi.fn() };
    createFetchStreamOpener(alphaOrganizationId, facilityId, failing)(handlers, controller.signal, undefined);
    await Promise.resolve();
    await Promise.resolve();
    expect(handlers.onError).not.toHaveBeenCalled();
  });

  it('uses the ambient fetch when no opener function is supplied', async () => {
    const fetchStub = vi.fn((_url?: string, _init?: RequestInit) => Promise.resolve({ ok: false, body: null }));
    vi.stubGlobal('fetch', fetchStub);
    const handlers: StreamHandlers = { onOpen: vi.fn(), onFrame: vi.fn(), onError: vi.fn() };
    const opener = createFetchStreamOpener(alphaOrganizationId, facilityId);
    opener(handlers, new AbortController().signal, undefined);
    await vi.waitFor(() => expect(handlers.onError).toHaveBeenCalledTimes(1));
    expect(fetchStub).toHaveBeenCalledTimes(1);
    expect(fetchStub.mock.calls[0]?.[0]).toBe('/api/v1/staff/instant/events/stream?facilityId=facility-synthetic-main');
  });

  it('cancels a reader that has not opened yet without throwing', () => {
    const pending = new Promise<unknown>(() => undefined);
    const opener = createFetchStreamOpener(alphaOrganizationId, facilityId, fakeFetch(pending));
    const cleanup = opener(
      { onOpen: vi.fn(), onFrame: vi.fn(), onError: vi.fn() },
      new AbortController().signal,
      undefined,
    );
    expect(() => cleanup.cancel()).not.toThrow();
  });
});
