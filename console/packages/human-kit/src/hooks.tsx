import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useRef,
  useState,
  useSyncExternalStore,
  type ReactNode
} from "react";
import type { EventsList, EventsQuery, EventWrite, OversightClient } from "./client";
import { settleStage } from "./clock";
import type {
  ControlSettings,
  Duration,
  Halt,
  HumanEvent,
  OverseerRegistry,
  Quality,
  Reviewer,
  StandingDecision,
  Summary
} from "./types";

// Headless hooks over an OversightClient: reads that poll, and writes that put
// the answer where every view of it reads. Reads are kept in one small cache
// per provider, keyed by what was read, so the event page, its decision panel
// and the queue show the same event; a write replaces that event and marks
// the lists stale, which re-reads them.

interface Entry {
  data?: unknown;
  error?: Error;
  loading: boolean;
  stale: boolean;
}

const EMPTY: Entry = { loading: false, stale: true };

/** The reads of one provider, by key. */
export class QueryCache {
  private entries = new Map<string, Entry>();
  private listeners = new Map<string, Set<() => void>>();
  private inflight = new Map<string, Promise<void>>();

  get(key: string): Entry {
    return this.entries.get(key) ?? EMPTY;
  }

  subscribe(key: string, listener: () => void): () => void {
    let set = this.listeners.get(key);
    if (!set) this.listeners.set(key, (set = new Set()));
    set.add(listener);
    return () => set.delete(listener);
  }

  private put(key: string, entry: Entry) {
    this.entries.set(key, entry);
    this.listeners.get(key)?.forEach((l) => l());
  }

  /** Reads `key` with `fn`, unless a read of it is already under way. */
  fetch(key: string, fn: () => Promise<unknown>): Promise<void> {
    const running = this.inflight.get(key);
    if (running) return running;
    const before = this.get(key);
    this.put(key, { ...before, loading: true });
    const run = fn()
      .then(
        (data) => this.put(key, { data, loading: false, stale: false }),
        (error: unknown) =>
          this.put(key, {
            ...this.get(key),
            error: error instanceof Error ? error : new Error(String(error)),
            loading: false,
            stale: false
          })
      )
      .finally(() => this.inflight.delete(key));
    this.inflight.set(key, run);
    return run;
  }

  /** Puts `data` under `key`, as a write's answer does. */
  set(key: string, data: unknown) {
    this.put(key, { data, loading: false, stale: false });
  }

  /** Puts `data` under `key` when nothing is there yet, without telling anyone: safe during a render. */
  seed(key: string, data: unknown) {
    if (!this.entries.has(key)) this.entries.set(key, { data, loading: false, stale: false });
  }

  /** Marks every key that starts with one of `prefixes` stale, so it is read again. */
  invalidate(...prefixes: string[]) {
    for (const [key, entry] of this.entries) {
      if (prefixes.some((p) => key.startsWith(p))) this.put(key, { ...entry, stale: true });
    }
  }
}

interface Oversight {
  client: OversightClient;
  cache: QueryCache;
  /** How often lists and events are read again, in milliseconds; 0 reads once. */
  pollMs: number;
}

const OversightContext = createContext<Oversight | undefined>(undefined);

/**
 * Gives every component and hook under it the client to read and write with.
 * `pollMs` is how often reads are repeated while shown.
 */
export function OversightProvider({
  client,
  pollMs = 5000,
  children
}: {
  client: OversightClient;
  pollMs?: number;
  children: ReactNode;
}) {
  const [cache] = useState(() => new QueryCache());
  const [value, setValue] = useState<Oversight>({ client, cache, pollMs });
  // A new client (another target, another reviewer) starts from nothing read.
  useEffect(() => {
    setValue((v) => (v.client === client && v.pollMs === pollMs ? v : { client, cache: new QueryCache(), pollMs }));
  }, [client, pollMs]);
  return <OversightContext.Provider value={value}>{children}</OversightContext.Provider>;
}

/** The provider's client and cache; undefined outside a provider. */
export function useOversight(): Oversight | undefined {
  return useContext(OversightContext);
}

export interface Read<T> {
  data: T | undefined;
  error: Error | undefined;
  /** True until the first answer. */
  loading: boolean;
  refresh: () => Promise<void>;
}

const NO_CACHE = new QueryCache();

/** A read of `key` through the provider's cache, repeated every `pollMs` while shown. */
export function useRead<T>(
  key: string,
  fn: (client: OversightClient) => Promise<T>,
  options: { enabled?: boolean; pollMs?: number; initialData?: T } = {}
): Read<T> {
  const oversight = useOversight();
  const cache = oversight?.cache ?? NO_CACHE;
  const enabled = (options.enabled ?? true) && oversight !== undefined;
  const fnRef = useRef(fn);
  fnRef.current = fn;
  const entry = useSyncExternalStore(
    useCallback((listener) => cache.subscribe(key, listener), [cache, key]),
    () => cache.get(key)
  );
  const run = useCallback(() => {
    if (!oversight) return Promise.resolve();
    return cache.fetch(key, () => fnRef.current(oversight.client));
  }, [cache, key, oversight]);

  if (options.initialData !== undefined && oversight) cache.seed(key, options.initialData);

  useEffect(() => {
    if (enabled && entry.stale && !entry.loading) void run();
  }, [enabled, entry.stale, entry.loading, run]);

  const pollMs = options.pollMs ?? oversight?.pollMs ?? 0;
  useEffect(() => {
    if (!enabled || !pollMs) return;
    const id = setInterval(() => {
      if (typeof document === "undefined" || document.visibilityState !== "hidden") void run();
    }, pollMs);
    return () => clearInterval(id);
  }, [enabled, pollMs, run]);

  const data = (entry.data as T | undefined) ?? options.initialData;
  return {
    data,
    error: entry.error,
    loading: data === undefined && entry.error === undefined && enabled,
    refresh: run
  };
}

const keyOf = (name: string, args: unknown) => `${name}:${JSON.stringify(args ?? null)}`;

/** One page of events matching `query`. */
export function useEvents(query: EventsQuery = {}, options: { enabled?: boolean; pollMs?: number } = {}) {
  return useRead<EventsList>(keyOf("events", query), (client) => client.events(query), options);
}

/** One event by id; `initialData` shows a known event at once while it is read again. */
export function useEvent(
  id: string | undefined,
  options: { enabled?: boolean; pollMs?: number; initialData?: HumanEvent } = {}
) {
  return useRead<HumanEvent>(
    `event:${id ?? ""}`,
    (client) => client.event(id!),
    { ...options, enabled: (options.enabled ?? true) && Boolean(id) }
  );
}

export const useSummary = (control?: string, options: { enabled?: boolean } = {}) =>
  useRead<Summary>(keyOf("summary", control), (client) => client.summary(control), options);

export const useQuality = (window: Duration = "P30D", options: { enabled?: boolean } = {}) =>
  useRead<Quality>(keyOf("quality", window), (client) => client.quality(window), { pollMs: 0, ...options });

export const useHalts = (options: { enabled?: boolean } = {}) =>
  useRead<Halt[]>("halts", (client) => client.halts(), options);

export const useStanding = (options: { enabled?: boolean } = {}) =>
  useRead<StandingDecision[]>("standing", (client) => client.standing(), options);

export const useRegistry = (options: { enabled?: boolean } = {}) =>
  useRead<OverseerRegistry>("registry", (client) => client.registry(), { pollMs: 0, ...options });

export const useControls = () =>
  useRead<ControlSettings[]>("controls", (client) => client.controls(), { pollMs: 0 });

/** Who writes are made as: the companion's local reviewer, or the one chosen for a service. */
export const useReviewer = (options: { enabled?: boolean } = {}) =>
  useRead<Reviewer | null>("reviewer", (client) => client.reviewer(), { pollMs: 0, ...options });

export interface Write<A extends unknown[], R> {
  run: (...args: A) => Promise<R>;
  pending: boolean;
  error: Error | undefined;
  reset: () => void;
}

/** A write's pending state and last error, around `fn`. */
function useWrite<A extends unknown[], R>(fn: (oversight: Oversight, ...args: A) => Promise<R>): Write<A, R> {
  const oversight = useOversight();
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<Error>();
  const fnRef = useRef(fn);
  fnRef.current = fn;
  const run = useCallback(
    async (...args: A) => {
      if (!oversight) {
        const e = new Error("No oversight client is configured, so nothing can be written.");
        setError(e);
        throw e;
      }
      setPending(true);
      setError(undefined);
      try {
        return await fnRef.current(oversight, ...args);
      } catch (e) {
        setError(e instanceof Error ? e : new Error(String(e)));
        throw e;
      } finally {
        setPending(false);
      }
    },
    [oversight]
  );
  return { run, pending, error, reset: useCallback(() => setError(undefined), []) };
}

/**
 * Every write on one event (claim, release, decide, escalate, send back,
 * resubmit, withdraw, extend, reopen). The answer replaces the event wherever
 * it is shown, and the lists and figures are read again.
 */
export function useEventWrite(id: string) {
  return useWrite(async ({ client, cache }, write: EventWrite) => {
    const event = await client.write(id, write);
    cache.set(`event:${id}`, event);
    cache.invalidate("events:", "summary:", "halts", "standing", "quality:");
    return event;
  });
}

/** Halts a scope or asks to restart it. */
export function useHaltWrite() {
  return useWrite(async ({ client, cache }, control: string, lift: boolean, reason: string) => {
    const event = await client.halt(control, lift, reason);
    cache.set(`event:${event.id}`, event);
    cache.invalidate("halts", "events:", "summary:");
    return event;
  });
}

/** Revokes a standing decision. */
export function useRevokeStanding() {
  return useWrite(async ({ client, cache }, id: string, reason: string) => {
    const decision = await client.revokeStanding(id, reason);
    cache.invalidate("standing", "summary:");
    return decision;
  });
}

/** Chooses who writes are made as, and reads everything again as them. */
export function useSetReviewer() {
  return useWrite(async ({ client, cache }, reviewer: Reviewer) => {
    const chosen = await client.setReviewer(reviewer);
    cache.set("reviewer", chosen);
    cache.invalidate("events:", "event:");
    return chosen;
  });
}

/** The time, moved on every `ms` so clocks and countdowns run; `fixed` holds it still. */
export function useNow(ms = 1000, fixed?: number): number {
  const [now, setNow] = useState(() => fixed ?? Date.now());
  useEffect(() => {
    if (fixed !== undefined) return;
    const id = setInterval(() => setNow(Date.now()), ms);
    return () => clearInterval(id);
  }, [ms, fixed]);
  return fixed ?? now;
}

/**
 * Calls `onSettled` each time `id`'s event moves on towards closed: when it is
 * decided, and again when it is applied or otherwise closed. It fires for a
 * decision made here, whose answer the write puts in the cache, and for one
 * made elsewhere, which the event's polling reads. An event already settled
 * when first read does not fire. Meant for a service's own data that changes
 * when its events are decided: give it that data's `refresh`.
 */
export function useOnSettled(id: string | undefined, onSettled: (event: HumanEvent) => void) {
  const event = useEvent(id).data;
  const callback = useRef(onSettled);
  callback.current = onSettled;
  const seen = useRef<{ id?: string; stage?: number }>({});
  useEffect(() => {
    if (!event || event.id !== id) return;
    const stage = settleStage(event);
    const before = seen.current.id === id ? seen.current.stage : undefined;
    seen.current = { id, stage };
    if (before !== undefined && stage > before) callback.current(event);
  }, [event, id]);
}
