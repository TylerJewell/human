import { useEffect, useId, useMemo, useState } from "react";
import type { EventsQuery } from "../client";
import { useEvents, useNow, useOversight, useRegistry } from "../hooks";
import { SUBJECT_KINDS } from "../parse";
import type { HumanEventRow } from "../types";
import {
  byClosed,
  byUrgency,
  formatSpan,
  patternName,
  PATTERNS,
  readable,
  readClock,
  stateLabel,
  serviceNow,
  subjectKindLabel
} from "../words";
import { ConclusionChip, DeadlineRing, OutcomeChip, StateChip } from "./status";
import { Button, cx, ErrorLine, Tag } from "./ui";

// The queue: one row per event, most urgent first while waiting and newest
// first once answered, each with its deadline ring, its question, what it is
// and who holds it. Filters by state, service, pattern, subject kind,
// deadline and whether the acting reviewer can act on it now. More rows come
// a page at a time, by the server's cursor when it gives one. A row opens its
// event.

export interface QueueFilters {
  state: "pending" | "resolved";
  /** One state within waiting or answered, by its contract name. */
  status?: string;
  /** One service's events, where the queue spans several. */
  service?: string;
  pattern?: string;
  subject?: string;
  sla?: "at-risk" | "overdue";
  routable?: boolean;
  q?: string;
}

/** Whether a row passes `filters` at this browser's `now`, for rows filtered here rather than by the server. */
export function matches(row: HumanEventRow, filters: QueueFilters, now: number): boolean {
  if (filters.state === "pending" ? !row.pending : row.pending) return false;
  if (filters.status && row.state !== filters.status) return false;
  if (filters.service && (row.owner?.service ?? row.subject.service) !== filters.service) return false;
  if (filters.pattern && row.pattern !== filters.pattern) return false;
  if (filters.subject && row.subject.kind !== filters.subject) return false;
  if (filters.sla) {
    const tone = readClock(row.sla, row.detectedAt, serviceNow(row, now)).tone;
    if (filters.sla === "overdue" ? tone !== "overdue" : tone !== "at-risk") return false;
  }
  if (filters.q) {
    const q = filters.q.toLowerCase();
    const text = `${row.ask.question} ${row.subject.summary} ${row.id} ${row.control.name}`.toLowerCase();
    if (!text.includes(q)) return false;
  }
  return true;
}

/** Rows in the order the contract gives them: urgency while waiting, newest closed once answered. */
export const ordered = (rows: readonly HumanEventRow[], state: QueueFilters["state"]) =>
  state === "pending" ? byUrgency(rows) : byClosed(rows);

function Row({
  row,
  now,
  selected,
  nameOf,
  onOpen
}: {
  row: HumanEventRow;
  now: number;
  selected: boolean;
  nameOf: (s: string) => string;
  onOpen?: (row: HumanEventRow) => void;
}) {
  // Each row's times are its own service's, so each is read against that service's clock.
  const at = serviceNow(row, now);
  const clock = readClock(row.sla, row.detectedAt, at);
  const where = [row.subject.service, row.subject.agent].filter(Boolean).join(" › ");
  const inner = (
    <>
      <DeadlineRing sla={row.sla} detectedAt={row.detectedAt} now={at} kind={row.subject.kind} size="sm" showLabel={false} testId="row-ring" />
      <span className="hk-row__main">
        <span className="hk-row__question">{row.ask.question || row.subject.summary}</span>
        <span className="hk-row__meta">
          <Tag>{patternName(row.pattern, row.patternName)}</Tag>
          <span>{subjectKindLabel(row.subject.kind)}</span>
          <span className="hk-row__control">{row.control.name}</span>
          {where ? <span>{where}</span> : null}
          {row.reviewers.required > 1 ? (
            <span className="hk-nums">
              {row.reviewers.voted} of {row.reviewers.required} votes
            </span>
          ) : null}
        </span>
      </span>
      <span className="hk-row__state">
        {row.pending ? <StateChip state={row.state} /> : <OutcomeChip row={row} />}
        {!row.pending && row.conclusion ? <ConclusionChip conclusion={row.conclusion} /> : null}
      </span>
      <span className="hk-row__holder">
        {row.pending ? (row.holder ? nameOf(row.holder.subject) : <span className="hk-muted">Unclaimed</span>) : (row.decidedBy ?? []).map(nameOf).join(", ") || <span className="hk-muted">—</span>}
      </span>
      <span className={`hk-row__clock hk-nums hk-tone--${clock.tone}`} data-testid="row-clock">
        {row.pending ? clock.label : row.closedAt ? `${formatSpan(at - Date.parse(row.closedAt))} ago` : ""}
      </span>
    </>
  );
  return (
    <li className="hk-queue__item" data-testid="queue-row" data-id={row.id} data-tone={clock.tone}>
      {onOpen ? (
        <button type="button" className={cx("hk-row", selected && "hk-row--selected")} aria-current={selected || undefined} onClick={() => onOpen(row)}>
          {inner}
        </button>
      ) : (
        <div className={cx("hk-row", selected && "hk-row--selected")}>{inner}</div>
      )}
    </li>
  );
}

export interface QueueProps {
  /** Rows in hand, filtered and ordered here; without them the client is asked. */
  rows?: HumanEventRow[];
  /** The filters the queue starts with. */
  initial?: Partial<QueueFilters>;
  /** More of the server's query, fixed: a control, a queue, a service. */
  scope?: Omit<EventsQuery, "state" | "pattern" | "subject" | "sla" | "routable" | "q" | "limit" | "offset" | "cursor">;
  onOpen?: (row: HumanEventRow) => void;
  selectedId?: string;
  /** A fixed time, for tests and pictures. */
  now?: number;
  title?: string;
  pageSize?: number;
  /** The services the queue spans; given, it can be filtered to one of them. */
  services?: string[];
}

const WAITING_STATES = ["DETECTED", "OPEN", "CLAIMED", "IN_REVIEW", "AWAITING_SECOND", "ESCALATED", "CHANGES_REQUESTED"];
const ANSWERED_STATES = ["DECIDED", "APPLIED", "TIMED_OUT", "ABANDONED", "KILLED", "WITHDRAWN", "CLOSED", "REOPENED"];

/** The events waiting or answered, filtered, most urgent first. */
export function Queue({
  rows: given,
  initial,
  scope,
  onOpen,
  selectedId,
  now: fixedNow,
  title = "Events",
  pageSize = 50,
  services
}: QueueProps) {
  const now = useNow(1000, fixedNow);
  const [filters, setFilters] = useState<QueueFilters>({ state: "pending", ...initial });
  const [limit, setLimit] = useState(pageSize);
  const [draft, setDraft] = useState(filters.q ?? "");
  const query: EventsQuery = useMemo(
    () => ({
      ...scope,
      state: filters.status ?? filters.state,
      service: filters.service ?? scope?.service,
      pattern: filters.pattern,
      subject: filters.subject,
      sla: filters.state === "pending" ? filters.sla : undefined,
      routable: filters.routable || undefined,
      q: filters.q,
      limit
    }),
    [scope, filters, limit]
  );
  const read = useEvents(query, { enabled: given === undefined });
  const oversight = useOversight();
  // Rows past the first page, read once by the server's cursor; a new query starts again.
  const [more, setMore] = useState<{ rows: HumanEventRow[]; next: string | null }>();
  const [moreError, setMoreError] = useState<Error>();
  const [moreLoading, setMoreLoading] = useState(false);
  const firstQuery = JSON.stringify({ ...query, limit: undefined });
  useEffect(() => {
    setMore(undefined);
    setMoreError(undefined);
  }, [firstQuery]);
  const next = more ? more.next : (read.data?.next ?? null);
  const loadMore = () => {
    if (!oversight || !next) return;
    setMoreLoading(true);
    setMoreError(undefined);
    oversight.client
      .events({ ...query, cursor: next })
      .then(
        (page) => setMore((m) => ({ rows: [...(m?.rows ?? []), ...page.rows], next: page.next ?? null })),
        (e: Error) => setMoreError(e)
      )
      .finally(() => setMoreLoading(false));
  };
  const registry = useRegistry();
  const nameOf = (s: string) => registry.data?.overseers.find((o) => o.subject === s)?.name ?? s;
  const first = read.data?.rows;
  const seen = new Set(first?.map((r) => r.id));
  const source = given ?? (first ? [...first, ...(more?.rows ?? []).filter((r) => !seen.has(r.id))] : undefined);
  // Ordered here too: rows from services whose clocks differ compare by time left.
  const rows = source ? ordered(given ? source.filter((r) => matches(r, filters, now)) : source, filters.state) : undefined;
  const total = given ? rows?.length ?? 0 : read.data?.total ?? 0;
  const set = (patch: Partial<QueueFilters>) => {
    setLimit(pageSize);
    setFilters((f) => ({ ...f, ...patch }));
  };
  const ids = { status: useId(), service: useId(), pattern: useId(), subject: useId(), sla: useId(), q: useId() };

  return (
    <section className="hk hk-queue" aria-label={title} data-testid="queue">
      <div className="hk-queue__bar" role="toolbar" aria-label="Filters">
        <div className="hk-segmented hk-segmented--inline" role="radiogroup" aria-label="State">
          {(["pending", "resolved"] as const).map((state) => (
            <button
              key={state}
              type="button"
              role="radio"
              aria-checked={filters.state === state}
              className={cx("hk-segment", filters.state === state && "hk-segment--on")}
              onClick={() => set({ state, status: undefined })}
              data-testid={`queue-state-${state}`}
            >
              {state === "pending" ? "Waiting" : "Answered"}
            </button>
          ))}
        </div>
        <label className="hk-filter" htmlFor={ids.status}>
          <span className="hk-sr-only">State</span>
          <select
            id={ids.status}
            className="hk-input hk-input--sm"
            value={filters.status ?? ""}
            onChange={(e) => set({ status: e.target.value || undefined })}
            data-testid="queue-status"
          >
            <option value="">{filters.state === "pending" ? "Every waiting state" : "Every outcome state"}</option>
            {(filters.state === "pending" ? WAITING_STATES : ANSWERED_STATES).map((s) => (
              <option key={s} value={s}>
                {stateLabel(s)}
              </option>
            ))}
          </select>
        </label>
        {services?.length ? (
          <label className="hk-filter" htmlFor={ids.service}>
            <span className="hk-sr-only">Service</span>
            <select
              id={ids.service}
              className="hk-input hk-input--sm"
              value={filters.service ?? ""}
              onChange={(e) => set({ service: e.target.value || undefined })}
              data-testid="queue-service"
            >
              <option value="">Every service</option>
              {services.map((s) => (
                <option key={s} value={s}>
                  {s}
                </option>
              ))}
            </select>
          </label>
        ) : null}
        <label className="hk-filter" htmlFor={ids.pattern}>
          <span className="hk-sr-only">Pattern</span>
          <select id={ids.pattern} className="hk-input hk-input--sm" value={filters.pattern ?? ""} onChange={(e) => set({ pattern: e.target.value || undefined })} data-testid="queue-pattern">
            <option value="">Every pattern</option>
            {PATTERNS.map((p) => (
              <option key={p} value={p}>
                {patternName(p)}
              </option>
            ))}
          </select>
        </label>
        <label className="hk-filter" htmlFor={ids.subject}>
          <span className="hk-sr-only">Subject kind</span>
          <select id={ids.subject} className="hk-input hk-input--sm" value={filters.subject ?? ""} onChange={(e) => set({ subject: e.target.value || undefined })} data-testid="queue-subject">
            <option value="">Every subject</option>
            {SUBJECT_KINDS.map((k) => (
              <option key={k} value={k}>
                {subjectKindLabel(k)}
              </option>
            ))}
          </select>
        </label>
        {filters.state === "pending" ? (
          <label className="hk-filter" htmlFor={ids.sla}>
            <span className="hk-sr-only">Deadline</span>
            <select
              id={ids.sla}
              className="hk-input hk-input--sm"
              value={filters.sla ?? ""}
              onChange={(e) => set({ sla: (e.target.value || undefined) as QueueFilters["sla"] })}
              data-testid="queue-sla"
            >
              <option value="">Any deadline</option>
              <option value="at-risk">At risk</option>
              <option value="overdue">Overdue</option>
            </select>
          </label>
        ) : null}
        <button
          type="button"
          className={cx("hk-toggle", filters.routable && "hk-toggle--on")}
          aria-pressed={Boolean(filters.routable)}
          onClick={() => set({ routable: !filters.routable })}
          data-testid="queue-routable"
        >
          Only what I can act on
        </button>
        <form
          role="search"
          className="hk-filter hk-filter--grow"
          onSubmit={(e) => {
            e.preventDefault();
            set({ q: draft.trim() || undefined });
          }}
        >
          <label htmlFor={ids.q} className="hk-sr-only">
            Search events
          </label>
          <input
            id={ids.q}
            type="search"
            className="hk-input hk-input--sm"
            placeholder="Question, subject or id"
            value={draft}
            onChange={(e) => {
              setDraft(e.target.value);
              if (e.target.value === "") set({ q: undefined });
            }}
            data-testid="queue-search"
          />
        </form>
      </div>

      <ErrorLine error={given ? undefined : (read.error ?? moreError)} testId="queue-error" />
      {read.data?.unreadable ? (
        <p className="hk-note" data-testid="queue-unreadable">
          {read.data.unreadable} {read.data.unreadable === 1 ? "event" : "events"} could not be read and {read.data.unreadable === 1 ? "is" : "are"} left out.
        </p>
      ) : null}

      {rows === undefined ? (
        read.error ? null : (
          <div className="hk-queue__list" aria-busy="true">
            {Array.from({ length: 3 }, (_, i) => (
              <div key={i} className="hk-skeleton hk-skeleton--row" />
            ))}
          </div>
        )
      ) : rows.length === 0 ? (
        <p className="hk-empty" data-testid="queue-empty">
          {filters.state === "pending" ? "Nothing is waiting" : "Nothing has been answered"}
          {filters.status || filters.service || filters.pattern || filters.subject || filters.sla || filters.routable || filters.q
            ? " that matches these filters."
            : "."}
        </p>
      ) : (
        <>
          <div className="hk-queue__head" aria-hidden="true">
            <span />
            <span>Question</span>
            <span>{filters.state === "pending" ? "State" : "Outcome"}</span>
            <span>{filters.state === "pending" ? "Holder" : "Decided by"}</span>
            <span>{filters.state === "pending" ? "Deadline" : "Closed"}</span>
          </div>
          <ul className="hk-queue__list" aria-label={filters.state === "pending" ? "Waiting events" : "Answered events"}>
            {rows.map((row) => (
              <Row key={row.id} row={row} now={now} selected={row.id === selectedId} nameOf={nameOf} onOpen={onOpen} />
            ))}
          </ul>
          <div className="hk-queue__foot">
            <span className="hk-muted hk-nums" data-testid="queue-count">
              {rows.length} of {Math.max(total, rows.length)} {readable(filters.state === "pending" ? "waiting" : "answered").toLowerCase()}
            </span>
            {!given && (next || total > rows.length) ? (
              <Button
                size="sm"
                variant="ghost"
                disabled={moreLoading}
                onClick={() => (next ? loadMore() : setLimit(Math.min(200, limit + pageSize)))}
                testId="queue-more"
              >
                {moreLoading ? "Reading…" : "Show more"}
              </Button>
            ) : null}
          </div>
        </>
      )}
    </section>
  );
}
