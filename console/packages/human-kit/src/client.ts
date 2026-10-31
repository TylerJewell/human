import {
  ContractError,
  parseEvent,
  parseRow,
  readControls,
  readHalts,
  readQuality,
  readRegistry,
  readServiceInfo,
  readStanding,
  readStandingOne,
  readSummary,
  rowOf
} from "./parse";
import type {
  CompanionService,
  ControlSettings,
  DecisionInput,
  Duration,
  ErrorBody,
  Halt,
  HumanEvent,
  HumanEventRow,
  OverseerRegistry,
  Quality,
  RaiseRequest,
  Reviewer,
  ServiceInfo,
  StandingDecision,
  Summary,
  Verdict
} from "./types";

// Two ways to reach the oversight routes. The companion merges every service and
// stamps the local reviewer on each write, so a console sends no reviewer. A
// service reached directly takes the reviewer in each write's body, so the
// client stamps the one chosen for it; that is for development.

export interface EventsQuery {
  /** `pending`, `resolved`, or a state name. */
  state?: string;
  pattern?: string;
  mode?: string;
  flavor?: string;
  /** A subject kind. */
  subject?: string;
  control?: string;
  queue?: string;
  holder?: string;
  sla?: "at-risk" | "overdue";
  /** Only the events the acting reviewer can act on now. */
  routable?: boolean;
  shadow?: boolean;
  since?: string;
  q?: string;
  limit?: number;
  /** Service paging. */
  offset?: number;
  /** Companion paging: the `next` of the page before. */
  cursor?: string;
  /** Companion: one service's events. */
  service?: string;
}

export interface EventsList {
  rows: HumanEventRow[];
  total: number;
  /** The companion's cursor for the next page, when there is one. */
  next?: string | null;
  /** Rows the server sent that could not be read, left out of `rows`. */
  unreadable: number;
}

/** Every write on one event, by the route it takes. */
export type EventWrite =
  | { kind: "claim" }
  | { kind: "release" }
  | { kind: "decision"; body: DecisionInput }
  | { kind: "escalate"; reason: string }
  | { kind: "send-back"; requested: string }
  | { kind: "resubmit"; note?: string }
  | { kind: "withdraw"; reason: string }
  | { kind: "extend"; extra: Duration; reason: string }
  | { kind: "reopen"; reason: string };

export interface OversightClient {
  readonly mode: "companion" | "service";
  readonly baseUrl: string;
  events(query?: EventsQuery, init?: RequestInit): Promise<EventsList>;
  event(id: string, init?: RequestInit): Promise<HumanEvent>;
  /** Sends one write on an event and answers the event as it stands after it. */
  write(id: string, write: EventWrite): Promise<HumanEvent>;
  claim(id: string): Promise<HumanEvent>;
  release(id: string): Promise<HumanEvent>;
  decide(id: string, decision: DecisionInput): Promise<HumanEvent>;
  escalate(id: string, reason: string): Promise<HumanEvent>;
  sendBack(id: string, requested: string): Promise<HumanEvent>;
  resubmit(id: string, note?: string): Promise<HumanEvent>;
  withdraw(id: string, reason: string): Promise<HumanEvent>;
  extend(id: string, extra: Duration, reason: string): Promise<HumanEvent>;
  reopen(id: string, reason: string): Promise<HumanEvent>;
  summary(control?: string): Promise<Summary>;
  quality(window?: Duration): Promise<Quality>;
  verdicts(query?: { since?: string; until?: string; offset?: number; limit?: number }): Promise<Verdict[]>;
  halts(): Promise<Halt[]>;
  standing(): Promise<StandingDecision[]>;
  revokeStanding(id: string, reason: string): Promise<StandingDecision>;
  registry(): Promise<OverseerRegistry>;
  controls(): Promise<ControlSettings[]>;
  /** Halts a control's scope (`lift: false`) or asks to restart it (`lift: true`). */
  halt(control: string, lift: boolean, reason: string): Promise<HumanEvent>;
  flag(body: { note: string; flowId?: string; sessionId?: string; output?: string; control?: string }): Promise<HumanEvent>;
  contest(body: { control: string; ref: string; grounds: string; appellant?: Record<string, unknown> }): Promise<HumanEvent>;
  override(body: { control: string; ref: string; action: string; rationale: string; replacement?: string }): Promise<HumanEvent>;
  /** Who writes are made as; null when nobody is chosen. */
  reviewer(): Promise<Reviewer | null>;
  setReviewer(reviewer: Reviewer): Promise<Reviewer>;
}

/** A refused or failed call: the status and what the server said. */
export class OversightError extends Error {
  readonly status: number;
  /** The event's state, on a 409. */
  readonly state?: string;
  /** What was wrong with the request, on a 422. */
  readonly errors: string[];
  readonly body: unknown;

  constructor(status: number, message: string, body?: unknown) {
    super(message);
    this.name = "OversightError";
    this.status = status;
    this.body = body;
    const b = (body ?? {}) as Partial<ErrorBody>;
    this.state = typeof b.state === "string" ? b.state : undefined;
    this.errors = Array.isArray(b.errors) ? b.errors.filter((e): e is string => typeof e === "string") : [];
  }
}

type Fetch = typeof fetch;

interface Transport {
  get(path: string, init?: RequestInit): Promise<unknown>;
  post(path: string, body: unknown): Promise<unknown>;
  put(path: string, body: unknown): Promise<unknown>;
}

function transport(baseUrl: string, fetchImpl: Fetch | undefined): Transport {
  const base = baseUrl.replace(/\/+$/, "");
  const call = async (method: string, path: string, body?: unknown, init?: RequestInit) => {
    const doFetch = fetchImpl ?? globalThis.fetch.bind(globalThis);
    let response: Response;
    try {
      response = await doFetch(`${base}${path}`, {
        ...init,
        method,
        headers: {
          Accept: "application/json",
          ...(body === undefined ? {} : { "Content-Type": "application/json" })
        },
        body: body === undefined ? undefined : JSON.stringify(body)
      });
    } catch (error) {
      if (error instanceof DOMException && error.name === "AbortError") throw error;
      throw new OversightError(0, `${base || "The server"} could not be reached.`);
    }
    const text = await response.text();
    let json: unknown = undefined;
    if (text) {
      try {
        json = JSON.parse(text);
      } catch {
        json = undefined;
      }
    }
    if (!response.ok) {
      const said = (json as Partial<ErrorBody> | undefined)?.error;
      throw new OversightError(
        response.status,
        typeof said === "string" && said ? said : `${method} ${path} answered ${response.status} ${response.statusText}`.trim(),
        json
      );
    }
    if (text && json === undefined) {
      throw new OversightError(response.status, `${method} ${path} did not answer JSON.`);
    }
    return json;
  };
  return {
    get: (path, init) => call("GET", path, undefined, init),
    post: (path, body) => call("POST", path, body),
    put: (path, body) => call("PUT", path, body)
  };
}

/** A query string from the parameters that are set. */
function queryString(params: Record<string, string | number | boolean | undefined>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === "") continue;
    search.set(key, String(value));
  }
  const text = search.toString();
  return text ? `?${text}` : "";
}

/** The event a write answers, or a ContractError when it does not read as one. */
function eventOf(raw: unknown): HumanEvent {
  const parsed = parseEvent(raw);
  if (!parsed.ok) throw new ContractError("event", parsed.summary, parsed.raw, parsed.issues);
  return parsed.value;
}

const ROUTE: Record<EventWrite["kind"], string> = {
  claim: "claim",
  release: "release",
  decision: "decision",
  escalate: "escalate",
  "send-back": "send-back",
  resubmit: "resubmit",
  withdraw: "withdraw",
  extend: "extend",
  reopen: "reopen"
};

/** A write's body, without the reviewer. */
function bodyOf(write: EventWrite): Record<string, unknown> {
  switch (write.kind) {
    case "claim":
    case "release":
      return {};
    case "decision":
      return { ...write.body };
    case "escalate":
      return { reason: write.reason };
    case "send-back":
      return { requested: write.requested };
    case "resubmit":
      return write.note ? { note: write.note } : {};
    case "withdraw":
      return { reason: write.reason };
    case "extend":
      return { extra: write.extra, reason: write.reason };
    case "reopen":
      return { reason: write.reason };
  }
}

/** The shared half of both clients: everything but how events are listed and who is stamped. */
function common(
  http: Transport,
  prefix: string,
  stamp: (body: Record<string, unknown>) => Record<string, unknown>
) {
  const write = async (id: string, w: EventWrite) =>
    eventOf(await http.post(`${prefix}/events/${encodeURIComponent(id)}/${ROUTE[w.kind]}`, stamp(bodyOf(w))));
  const unwrap = (raw: unknown, key: string) =>
    raw && typeof raw === "object" ? (raw as Record<string, unknown>)[key] : undefined;
  return {
    event: async (id: string, init?: RequestInit) =>
      eventOf(await http.get(`${prefix}/events/${encodeURIComponent(id)}`, init)),
    write,
    claim: (id: string) => write(id, { kind: "claim" }),
    release: (id: string) => write(id, { kind: "release" }),
    decide: (id: string, body: DecisionInput) => write(id, { kind: "decision", body }),
    escalate: (id: string, reason: string) => write(id, { kind: "escalate", reason }),
    sendBack: (id: string, requested: string) => write(id, { kind: "send-back", requested }),
    resubmit: (id: string, note?: string) => write(id, { kind: "resubmit", note }),
    withdraw: (id: string, reason: string) => write(id, { kind: "withdraw", reason }),
    extend: (id: string, extra: Duration, reason: string) => write(id, { kind: "extend", extra, reason }),
    reopen: (id: string, reason: string) => write(id, { kind: "reopen", reason }),
    summary: async (control?: string) => readSummary(await http.get(`${prefix}/summary${queryString({ control })}`)),
    quality: async (window: Duration = "P30D") => readQuality(await http.get(`${prefix}/quality${queryString({ window })}`)),
    verdicts: async (query: { since?: string; until?: string; offset?: number; limit?: number } = {}) =>
      (unwrap(await http.get(`${prefix}/verdicts${queryString(query)}`), "verdicts") ?? []) as Verdict[],
    halts: async () => readHalts(unwrap(await http.get(`${prefix}/halts`), "halts")),
    standing: async () => readStanding(unwrap(await http.get(`${prefix}/standing`), "standing")),
    revokeStanding: async (id: string, reason: string) =>
      readStandingOne(await http.post(`${prefix}/standing/${encodeURIComponent(id)}/revoke`, stamp({ reason }))),
    registry: async () => readRegistry(await http.get(`${prefix}/registry`)),
    controls: async () => readControls(unwrap(await http.get(`${prefix}/controls`), "controls")),
    halt: async (control: string, lift: boolean, reason: string) =>
      eventOf(await http.post(`${prefix}/halts`, stamp({ control, lift, reason }))),
    flag: async (body: Record<string, unknown>) => eventOf(await http.post(`${prefix}/flags`, stamp(body))),
    contest: async (body: Record<string, unknown>) => eventOf(await http.post(`${prefix}/contests`, stamp(body))),
    override: async (body: Record<string, unknown>) => eventOf(await http.post(`${prefix}/overrides`, stamp(body)))
  };
}

function rowsOf(raw: unknown[]): { rows: HumanEventRow[]; unreadable: number } {
  const rows: HumanEventRow[] = [];
  let unreadable = 0;
  for (const item of raw) {
    const parsed = parseRow(item);
    if (parsed.ok) rows.push(parsed.value);
    else unreadable += 1;
  }
  return { rows, unreadable };
}

export interface CompanionClient extends OversightClient {
  readonly mode: "companion";
  /** Each service the companion has found. */
  services(): Promise<CompanionService[]>;
}

/**
 * The companion's merged API at `{baseUrl}/api/companion/human`. The companion
 * stamps the local reviewer on every write, so bodies carry none.
 */
export function createCompanionClient(options: { baseUrl?: string; fetch?: Fetch } = {}): CompanionClient {
  const baseUrl = options.baseUrl ?? "";
  const http = transport(baseUrl, options.fetch);
  const prefix = "/api/companion/human";
  const shared = common(http, prefix, (body) => body);
  return {
    mode: "companion",
    baseUrl,
    ...shared,
    async events(query = {}, init) {
      const { routable, offset: _offset, ...rest } = query;
      const raw = (await http.get(
        `${prefix}/events${queryString({ ...rest, routable: routable ? "me" : undefined })}`,
        init
      )) as Partial<{ rows: unknown[]; total: number; next: string | null }>;
      const { rows, unreadable } = rowsOf(Array.isArray(raw?.rows) ? raw.rows : []);
      return { rows, total: typeof raw?.total === "number" ? raw.total : rows.length, next: raw?.next ?? null, unreadable };
    },
    async reviewer() {
      const raw = (await http.get(`${prefix}/reviewer`)) as Reviewer | null | undefined;
      return raw && typeof raw.subject === "string" ? { ...raw, roles: raw.roles ?? [] } : null;
    },
    async setReviewer(reviewer) {
      const { subject, name, roles } = reviewer;
      return (await http.put(`${prefix}/reviewer`, { subject, name, roles })) as Reviewer;
    },
    async services() {
      const raw = await http.get(`${prefix}/services`);
      const list = Array.isArray(raw) ? raw : ((raw as Record<string, unknown>)?.services as unknown[] | undefined) ?? [];
      return list.filter((s): s is CompanionService => !!s && typeof (s as CompanionService).service === "string");
    }
  };
}

export interface ServiceClient extends OversightClient {
  readonly mode: "service";
  info(): Promise<ServiceInfo>;
  /** The development routes, served only when the service turns them on. */
  dev: {
    raise(request: RaiseRequest): Promise<HumanEvent>;
    clock(change: { advance: Duration } | { reset: true }): Promise<{ now: string; offset: string }>;
    tick(): Promise<{ expired: number; atRisk: number }>;
    reset(): Promise<{ removed: number }>;
  };
}

/**
 * One service's `/oversight` routes at `{baseUrl}/oversight`, for development. Each
 * write carries the chosen reviewer, stamped with `identityProvider` and marked
 * as a rehearsal unless told otherwise.
 */
export function createServiceClient(options: {
  baseUrl: string;
  reviewer?: Reviewer | null;
  identityProvider?: string;
  rehearsal?: boolean;
  fetch?: Fetch;
}): ServiceClient {
  const http = transport(options.baseUrl, options.fetch);
  const prefix = "/oversight";
  let chosen: Reviewer | null = options.reviewer ?? null;
  const stamp = (body: Record<string, unknown>) => {
    if (!chosen) throw new OversightError(0, "Choose who you act as before you write to the service.");
    return {
      ...body,
      reviewer: {
        subject: chosen.subject,
        ...(chosen.name ? { name: chosen.name } : {}),
        roles: chosen.roles,
        identityProvider: options.identityProvider ?? "human-kit",
        rehearsal: options.rehearsal ?? true
      }
    };
  };
  const shared = common(http, prefix, stamp);
  return {
    mode: "service",
    baseUrl: options.baseUrl,
    ...shared,
    async events(query = {}, init) {
      const { routable, cursor: _cursor, service: _service, ...rest } = query;
      const raw = (await http.get(
        `${prefix}/events${queryString({
          ...rest,
          ...(routable && chosen ? { roles: chosen.roles.join(","), reviewer: chosen.subject } : {})
        })}`,
        init
      )) as Partial<{ events: unknown[]; total: number }>;
      const rows: HumanEventRow[] = [];
      let unreadable = 0;
      for (const item of Array.isArray(raw?.events) ? raw.events : []) {
        const parsed = parseEvent(item);
        if (parsed.ok) rows.push(rowOf(parsed.value));
        else unreadable += 1;
      }
      return { rows, total: typeof raw?.total === "number" ? raw.total : rows.length, next: null, unreadable };
    },
    async reviewer() {
      return chosen;
    },
    async setReviewer(reviewer) {
      chosen = reviewer;
      return reviewer;
    },
    info: async () => readServiceInfo(await http.get(`${prefix}/info`)),
    dev: {
      raise: async (request) => eventOf(await http.post(`${prefix}/dev/events`, request)),
      clock: async (change) => (await http.post(`${prefix}/dev/clock`, change)) as { now: string; offset: string },
      tick: async () => (await http.post(`${prefix}/dev/tick`, {})) as { expired: number; atRisk: number },
      reset: async () => (await http.post(`${prefix}/dev/reset`, {})) as { removed: number }
    }
  };
}
