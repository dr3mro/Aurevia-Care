/**
 * Realtime lifecycle for the staff instant-messenger panel (MSG-02, WEB-09).
 *
 * EventSource cannot carry the tenant header or a resume id, so the stream is
 * opened with `fetch` and read as a byte stream using the shared descriptor's
 * headers. When the stream drops, the same identifier-only feed is polled with
 * a high-water cursor until the stream is resynced; the panel shows a quiet
 * reconnecting state throughout. Events carry identifiers only, so every
 * message or read event simply asks the owner to refetch thread data. A stopped
 * session aborts its fetch, cancels its reader and ignores late callbacks, so
 * a stream from a previous tenant cannot push events into a new tenant's view.
 */

import { eventStreamDescriptor, narrowEvent } from './api';
import { advanceConnection, isIdentifierEvent } from './domain';
import type { ConnectionEvent } from './domain';
import type { ConnectionStatus, RealtimeNotification, RealtimePage, WireEvent } from './types';

/** How often a polling fallback asks for the next identifier page. */
const POLL_INTERVAL_MS = 5_000;

/** How often a failed poll is retried while reconnecting. */
const POLL_RETRY_MS = 2_000;

/** Callbacks the stream producer invokes while it is alive. */
export interface StreamHandlers {
  /** The HTTP stream opened successfully. */
  readonly onOpen: () => void;
  /** One identifier-only event arrived. */
  readonly onFrame: (frame: RealtimeNotification) => void;
  /** The stream ended, failed or was refused. */
  readonly onError: () => void;
}

/** Cleanup handle for one stream attempt. */
export interface StreamCleanup {
  /** Abort the read loop and release the reader. */
  readonly cancel: () => void;
}

/** Injectable dependencies that make the lifecycle deterministic in tests. */
export interface RealtimeDeps {
  /** Open a stream and return its cleanup handle. */
  readonly openStream: (
    handlers: StreamHandlers,
    signal: AbortSignal,
    lastEventId: string | undefined,
  ) => StreamCleanup;
  /** Fetch one bounded, identifier-only event page. */
  readonly poll: (cursor: string | undefined) => Promise<RealtimePage>;
  /** Schedule one retry callback; the returned handle cancels it. */
  readonly schedule: (callback: () => void, delayMs: number) => StreamCleanup;
  /** Thread data changed and the owner must refetch messages and unread counts. */
  readonly onDataChanged: () => void;
  /** Report each connection-state transition. */
  readonly onStatus: (status: ConnectionStatus) => void;
}

/** The observable realtime session. */
export interface RealtimeSession {
  /** Current connection state. */
  readonly status: () => ConnectionStatus;
  /** Open the stream for the first time. */
  readonly start: () => void;
  /** Permanently stop the stream, polling and all late callbacks. */
  readonly stop: () => void;
  /** Reopen the stream after a polling fallback. */
  readonly reconnect: () => void;
}

/**
 * Split an SSE buffer into complete frames and the trailing partial frame.
 * Recursion keeps a single observable branch: a boundary either exists or it
 * does not, so the function is fully determined by its input.
 */
export function consumeSseBuffer(buffer: string): { frames: string[]; remainder: string } {
  const boundary = buffer.indexOf('\n\n');
  if (boundary < 0) {
    return { frames: [], remainder: buffer };
  }
  const frame = buffer.slice(0, boundary);
  const rest = consumeSseBuffer(buffer.slice(boundary + 2));
  return { frames: [frame, ...rest.frames], remainder: rest.remainder };
}

/**
 * Parse one SSE frame into a narrowed identifier-only notification. Frames
 * without a data line, malformed JSON, payloads missing required identifiers
 * and payloads outside the published event vocabulary or cursor range are
 * ignored: a bad notification can never trigger a refetch or a bad resume id.
 */
export function parseSseFrame(frame: string): RealtimeNotification | undefined {
  const dataLine = frame.split('\n').find((line) => line.startsWith('data:'));
  if (dataLine === undefined) {
    return undefined;
  }
  try {
    const wire = JSON.parse(dataLine.slice('data:'.length).trim()) as WireEvent;
    const event = narrowEvent(wire);
    return isIdentifierEvent(event) ? event : undefined;
  } catch {
    return undefined;
  }
}

/**
 * Create a fetch-based stream opener over the published SSE descriptor.
 * Descriptor headers, including the tenant and resume id, are forwarded on
 * every connect; the abort signal belongs to the owning session.
 */
export function createFetchStreamOpener(
  organizationId: string,
  facilityId: string,
  fetchFn: typeof fetch = fetch,
): (handlers: StreamHandlers, signal: AbortSignal, lastEventId: string | undefined) => StreamCleanup {
  return (handlers, signal, lastEventId) => {
    let reader: ReadableStreamDefaultReader<Uint8Array> | undefined;
    let buffer = '';
    void (async () => {
      try {
        const descriptor = eventStreamDescriptor(organizationId, facilityId, lastEventId);
        const response = await fetchFn(descriptor.url, {
          headers: descriptor.headers,
          credentials: 'include',
          signal,
        });
        if (!response.ok || response.body === null) {
          handlers.onError();
          return;
        }
        reader = response.body.getReader();
        handlers.onOpen();
        const decoder = new TextDecoder();
        let finished = false;
        while (!finished) {
          const result = await reader.read();
          if (result.done) {
            finished = true;
            continue;
          }
          buffer += decoder.decode(result.value, { stream: true });
          const { frames, remainder } = consumeSseBuffer(buffer);
          buffer = remainder;
          for (const frame of frames) {
            const event = parseSseFrame(frame);
            if (event !== undefined) {
              handlers.onFrame(event);
            }
          }
        }
        handlers.onError();
      } catch {
        if (!signal.aborted) {
          handlers.onError();
        }
      }
    })();
    return {
      cancel: () => {
        if (reader !== undefined) {
          void reader.cancel();
        }
      },
    };
  };
}

/**
 * Create the realtime session. The session owns its abort controller, stream
 * cleanup and poll timer so `stop` is the single place that guarantees no
 * callback from the old tenant can reach the new view.
 */
export function createMessengerRealtime(deps: RealtimeDeps): RealtimeSession {
  let status: ConnectionStatus = 'reconnecting';
  let stopped = false;
  let streamAbort: AbortController | undefined;
  let streamCleanup: StreamCleanup | undefined;
  let pollTimer: StreamCleanup | undefined;
  let cursor: string | undefined;
  let lastEventId: string | undefined;

  const emit = (event: ConnectionEvent): void => {
    status = advanceConnection(status, event);
    deps.onStatus(status);
  };

  const clearPolling = (): void => {
    if (pollTimer !== undefined) {
      pollTimer.cancel();
      pollTimer = undefined;
    }
  };

  const runPoll = (): void => {
    if (stopped) {
      return;
    }
    void deps
      .poll(cursor)
      .then((page) => {
        // A stream may have reopened while this poll was in flight. Its result
        // must not restart the fallback loop or advance a superseded cursor;
        // poll has no abort signal, so the status check is the single guard.
        if (stopped || status === 'streaming') {
          return;
        }
        // A page names changes; one refetch refreshes the whole thread and all
        // unread counts, so a hundred events must not cause a hundred refetches.
        if (page.events.length > 0) {
          deps.onDataChanged();
        }
        cursor = page.nextCursor === undefined ? cursor : String(page.nextCursor);
        emit('poll-succeeded');
        pollTimer = deps.schedule(runPoll, POLL_INTERVAL_MS);
      })
      .catch(() => {
        if (stopped || status === 'streaming') {
          return;
        }
        pollTimer = deps.schedule(runPoll, POLL_RETRY_MS);
      });
  };

  const open = (resync: boolean): void => {
    // Opening or reopening a stream ends the polling fallback: cancel its next
    // timer and let in-flight results be discarded by the status guard above.
    clearPolling();
    streamAbort?.abort();
    if (streamCleanup !== undefined) {
      streamCleanup.cancel();
      streamCleanup = undefined;
    }
    const controller = new AbortController();
    streamAbort = controller;
    const handlers: StreamHandlers = {
      onOpen: () => {
        if (stopped) {
          return;
        }
        clearPolling();
        emit(resync ? 'resynced' : 'stream-open');
      },
      onFrame: (frame) => {
        if (stopped) {
          return;
        }
        lastEventId = String(frame.sequence);
        deps.onDataChanged();
      },
      onError: () => {
        if (stopped) {
          return;
        }
        emit('stream-lost');
        clearPolling();
        runPoll();
      },
    };
    streamCleanup = deps.openStream(handlers, controller.signal, lastEventId);
  };

  return {
    status: () => status,
    start: () => {
      stopped = false;
      open(false);
    },
    stop: () => {
      stopped = true;
      streamAbort?.abort();
      if (streamCleanup !== undefined) {
        streamCleanup.cancel();
        streamCleanup = undefined;
      }
      clearPolling();
    },
    reconnect: () => {
      if (!stopped) {
        open(true);
      }
    },
  };
}
