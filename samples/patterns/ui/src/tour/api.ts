import { useRead, type HumanEvent, type Verdict } from "@akka-human/kit";
import { useCallback, useEffect, useState } from "react";

// The sample's own routes and the few contract reads the kit's client does not
// cover (one event's verdict, its proof records), plus the run each pattern
// page keeps: the ids its scenario produced, kept across reloads.

export class CallError extends Error {
  readonly status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

export async function call<T>(method: "GET" | "POST" | "PUT", path: string, body?: unknown): Promise<T> {
  const response = await fetch(path, {
    method,
    headers: { Accept: "application/json", ...(body === undefined ? {} : { "Content-Type": "application/json" }) },
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  const text = await response.text();
  const json = text ? safeJson(text) : undefined;
  if (!response.ok) {
    const said = json && typeof json === "object" && "error" in json ? String((json as { error: unknown }).error) : text;
    throw new CallError(response.status, said || `${method} ${path} answered ${response.status}`);
  }
  return json as T;
}

function safeJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}

/** A domain read that polls while the page shows it, through the oversight provider's cache. */
export const useDomain = <T,>(path: string | undefined, pollMs = 1500) =>
  useRead<T>(`domain:${path ?? ""}`, () => call<T>("GET", path!), { enabled: !!path, pollMs });

export const useVerdict = (eventId: string | undefined) =>
  useRead<Verdict | null>(
    `verdict:${eventId ?? ""}`,
    async () => (await call<{ verdicts: Verdict[] }>("GET", `/oversight/verdicts?eventId=${encodeURIComponent(eventId!)}&limit=5`)).verdicts[0] ?? null,
    { enabled: !!eventId, pollMs: 2000 }
  );

export interface ProofRecord {
  type: string;
  recordId: string;
  recordedAt: string;
  service: string;
  payload: Record<string, unknown>;
}

/** The proof records written for these events, oldest first. */
export const useProof = (eventIds: string[]) =>
  useRead<ProofRecord[]>(
    `proof:${eventIds.join(",")}`,
    async () => {
      const response = await fetch("/oversight/proof", { headers: { Accept: "application/x-ndjson" } });
      if (!response.ok) throw new CallError(response.status, `GET /oversight/proof answered ${response.status}`);
      const records = (await response.text())
        .split("\n")
        .filter((l) => l.trim())
        .map((l) => JSON.parse(l) as ProofRecord);
      return records.filter((r) => eventIds.includes(String(r.payload.event_id ?? "")));
    },
    { enabled: eventIds.length > 0, pollMs: 2500 }
  );

/** The pending events of a control and how many times each was raised. */
export async function pendingOf(control: string): Promise<Map<string, number>> {
  const page = await call<{ events: HumanEvent[] }>("GET", `/oversight/events?control=${control}&state=pending&limit=50`);
  return new Map(page.events.map((e) => [e.id, e.trigger.occurrences ?? 1]));
}

/**
 * The event a timer raises a moment after the call that set it: a pending event of the control
 * that was not there `before`, or one that took another occurrence (a raise under its dedupe key).
 */
export async function awaitRaised(control: string, before: Map<string, number>, tries = 25): Promise<HumanEvent> {
  for (let i = 0; i < tries; i++) {
    const page = await call<{ events: HumanEvent[] }>("GET", `/oversight/events?control=${control}&state=pending&limit=50`);
    const fresh = page.events.find((e) => !before.has(e.id) || (e.trigger.occurrences ?? 1) > before.get(e.id)!);
    if (fresh) return fresh;
    await new Promise((r) => setTimeout(r, 400));
  }
  throw new Error(`no event of ${control} was raised`);
}

// ---- the run

export interface Run {
  startedAt: string;
  /** The event the decide step is about. */
  eventId?: string;
  /** Every event the run produced, for the record step. */
  events: string[];
  /** The scenario's own id: the payment, notice, rollout, conversation or record it made. */
  entity?: string;
  data: Record<string, unknown>;
}

const KEY = (pattern: string) => `pattern-tour:run:${pattern}`;

function readRun(pattern: string): Run | null {
  try {
    const raw = localStorage.getItem(KEY(pattern));
    return raw ? (JSON.parse(raw) as Run) : null;
  } catch {
    return null;
  }
}

export function useRun(pattern: string): [Run | null, (run: Run | null) => void] {
  const [run, setRun] = useState<Run | null>(() => readRun(pattern));
  useEffect(() => setRun(readRun(pattern)), [pattern]);
  const save = useCallback(
    (next: Run | null) => {
      setRun(next);
      try {
        if (next) localStorage.setItem(KEY(pattern), JSON.stringify(next));
        else localStorage.removeItem(KEY(pattern));
      } catch {
        // The run then lasts until the page is reloaded.
      }
      window.dispatchEvent(new CustomEvent("pattern-tour:runs"));
    },
    [pattern]
  );
  return [run, save];
}

/** Every pattern's run, for the navigation's progress marks; updates when any run changes. */
export function useRuns(patterns: string[]): Record<string, Run | null> {
  const read = () => Object.fromEntries(patterns.map((p) => [p, readRun(p)]));
  const [runs, setRuns] = useState(read);
  useEffect(() => {
    const changed = () => setRuns(read());
    window.addEventListener("pattern-tour:runs", changed);
    window.addEventListener("storage", changed);
    return () => {
      window.removeEventListener("pattern-tour:runs", changed);
      window.removeEventListener("storage", changed);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [patterns.join(",")]);
  return runs;
}

export function clearRuns(patterns: string[]) {
  try {
    for (const p of patterns) localStorage.removeItem(KEY(p));
  } catch {
    // Nothing was kept.
  }
  window.dispatchEvent(new CustomEvent("pattern-tour:runs"));
}

export const newRun = (data: Partial<Run> = {}): Run => ({ startedAt: new Date().toISOString(), events: [], data: {}, ...data });
