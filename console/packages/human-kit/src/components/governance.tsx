import { useState, type ReactNode } from "react";
import { useHalts, useHaltWrite, useNow, useQuality, useRegistry, useRevokeStanding, useStanding, useSummary } from "../hooks";
import type { Halt, LoopFigures, OverseerRegistry, Quality, StandingDecision, Summary } from "../types";
import { fromNow, SafeStateChecks } from "../subjects/operations";
import {
  formatDay,
  formatDuration,
  formatMoment,
  formatPercent,
  formatSpan,
  listNames,
  MISSING,
  patternName,
  readable
} from "../words";
import { Button, Chip, Confirm, ErrorLine, Facts, Field, Meter, Section, Tag, cx, type Fact, type Tone } from "./ui";

// The views of the oversight as a whole, beside the queue: the halts and their
// safe-state checks, the standing decisions that settle events on their own,
// how well people oversee, who may oversee, and the figures of the loop.

const reasonBox = (reason: string, setReason: (r: string) => void, label = "Reason") => (
  <Field label={label} required>
    {(id) => <textarea id={id} rows={3} className="hk-input" value={reason} onChange={(e) => setReason(e.target.value)} data-testid="write-reason" />}
  </Field>
);

// ---------------------------------------------------------------- halts

function HaltTile({ halt, now, onOpenEvent }: { halt: Halt; now: number; onOpenEvent?: (id: string) => void }) {
  const write = useHaltWrite();
  const [asking, setAsking] = useState(false);
  const [reason, setReason] = useState("");
  const [done, setDone] = useState<string>();
  const open = halt.safeStateChecks.filter((c) => c.passed !== true).length;
  const lift = halt.halted;
  const facts: Fact[] = [{ label: "Scope", value: readable(halt.scope) }];
  if (halt.service) facts.push({ label: "Service", value: halt.service });
  if (halt.affectedCalls) facts.push({ label: "Affected calls", value: readable(halt.affectedCalls) });
  if (halt.halted && halt.since) facts.push({ label: "Halted", value: `${formatMoment(halt.since)} (${fromNow(halt.since, now)})` });
  if (halt.halted && halt.by) facts.push({ label: "By", value: halt.by });
  if (halt.callsPerMinute != null)
    facts.push({ label: halt.halted ? "Calls a minute held" : "Calls a minute", value: halt.callsPerMinute.toLocaleString("en-US") });
  facts.push({ label: "Control", value: halt.control.name || halt.control.rowId });
  const eventLink = (id: string, text: string) =>
    onOpenEvent ? (
      <button type="button" className="hk-link" onClick={() => onOpenEvent(id)}>
        {text}
      </button>
    ) : (
      <code className="hk-code">{id}</code>
    );
  if (halt.eventId) facts.push({ label: "Halt event", value: eventLink(halt.eventId, halt.eventId) });
  if (halt.liftEventId) facts.push({ label: "Restart request", value: eventLink(halt.liftEventId, `${halt.liftEventId}, waiting`) });
  return (
    <div className="hk-halt" data-testid="halt-tile" data-target={halt.target} data-halted={halt.halted}>
      <Section
        title={readable(halt.target)}
        status={<Chip tone={halt.halted ? "danger" : "success"}>{halt.halted ? "Halted" : "Running"}</Chip>}
        controls={
          halt.liftEventId ? null : halt.halted ? (
            <Button size="sm" variant="secondary" disabled={open > 0 || asking} onClick={() => setAsking(true)} testId="halt-restart">
              Ask to restart
            </Button>
          ) : (
            <Button size="sm" variant="danger" disabled={asking} onClick={() => setAsking(true)} testId="halt-exercise">
              Halt
            </Button>
          )
        }
      >
        <div className="hk-stack">
          <Facts facts={facts} />
          <SafeStateChecks checks={halt.safeStateChecks} lift={lift} />
          {asking ? (
            <Confirm
              title={lift ? `Ask to restart ${readable(halt.target)}` : `Halt ${readable(halt.target)}`}
              verb={lift ? "Ask to restart" : "Halt now"}
              danger={!lift}
              changes={
                lift
                  ? "A restart event is raised. It takes the control's review, two people by default, and is approved only while every safe-state check passes."
                  : `${readable(halt.affectedCalls ?? "every runtime operation")} on ${readable(halt.target)} stop at once. Restarting it takes a second decision.`
              }
              pending={write.pending}
              problem={reason.trim() ? undefined : lift ? "Say why it is safe to restart." : "Say why it must stop."}
              error={write.error}
              onConfirm={() =>
                void write
                  .run(halt.control.rowId, lift, reason.trim())
                  .then((event) => {
                    setAsking(false);
                    setReason("");
                    setDone(lift ? `Restart requested as ${event.id}.` : `Halted; the event is ${event.id}.`);
                  })
                  .catch(() => undefined)
              }
              onCancel={() => setAsking(false)}
            >
              {reasonBox(reason, setReason)}
            </Confirm>
          ) : null}
          {done ? (
            <p className="hk-said" role="status">
              {done}
            </p>
          ) : null}
        </div>
      </Section>
    </div>
  );
}

/** Each halt scope, running or halted, with its safe-state checks and the way to halt or restart it. */
export function HaltsPanel({ halts: given, now: fixedNow, onOpenEvent }: { halts?: Halt[]; now?: number; onOpenEvent?: (id: string) => void }) {
  const read = useHalts({ enabled: given === undefined });
  const now = useNow(1000, fixedNow);
  const halts = given ?? read.data;
  return (
    <div className="hk hk-halts" data-testid="halts">
      <ErrorLine error={given ? undefined : read.error} />
      {halts === undefined ? (
        read.error ? null : <div className="hk-skeleton hk-skeleton--block" aria-busy="true" />
      ) : halts.length === 0 ? (
        <p className="hk-empty">No control declares a halt.</p>
      ) : (
        <div className="hk-grid">
          {halts.map((h) => (
            <HaltTile key={`${h.service ?? ""}-${h.control.rowId}-${h.target}`} halt={h} now={now} onOpenEvent={onOpenEvent} />
          ))}
        </div>
      )}
    </div>
  );
}

// ---------------------------------------------------------------- standing decisions

const STANDING_STATE: Record<string, { word: string; tone: Tone }> = {
  ACTIVE: { word: "Active", tone: "success" },
  EXPIRING: { word: "Expiring", tone: "warning" },
  EXPIRED: { word: "Expired", tone: "default" },
  REVOKED: { word: "Revoked", tone: "default" }
};

const STANDING_ORDER = ["EXPIRING", "ACTIVE", "EXPIRED", "REVOKED"];

const KIND_WORD: Record<string, string> = {
  "pattern-approval": "Approves every match",
  "pattern-denial": "Denies every match",
  "session-approval": "Approves for a session",
  "conditional-approval": "Approves with conditions",
  change: "Approved change",
  exception: "Exception"
};

/** Expiring first, then active, ended and revoked; soonest to expire first within each. */
export function standingOrder(list: readonly StandingDecision[]) {
  const rank = (s: StandingDecision) => {
    const i = STANDING_ORDER.indexOf(s.state);
    return i < 0 ? STANDING_ORDER.length : i;
  };
  const expiry = (s: StandingDecision) => (s.expiresAt ? Date.parse(s.expiresAt) : Number.POSITIVE_INFINITY);
  return [...list].sort((a, b) => rank(a) - rank(b) || expiry(a) - expiry(b));
}

function StandingRow({ decision, now }: { decision: StandingDecision; now: number }) {
  const revoke = useRevokeStanding();
  const [asking, setAsking] = useState(false);
  const [reason, setReason] = useState("");
  const state = STANDING_STATE[decision.state] ?? { word: readable(decision.state), tone: "default" as Tone };
  const granted = Date.parse(decision.grantedAt);
  const expires = decision.expiresAt ? Date.parse(decision.expiresAt) : undefined;
  const spent = expires !== undefined && expires > granted ? (now - granted) / (expires - granted) : undefined;
  const live = decision.state === "ACTIVE" || decision.state === "EXPIRING";
  return (
    <li className="hk-standing" data-testid="standing-row" data-state={decision.state}>
      <div className="hk-standing__main">
        <span className="hk-standing__allows">{decision.allows}</span>
        <span className="hk-inline-list">
          <Chip tone={state.tone}>{state.word}</Chip>
          <Tag>{KIND_WORD[decision.kind] ?? readable(decision.kind)}</Tag>
          {decision.matchKey ? <code className="hk-code">{decision.matchKey}</code> : null}
        </span>
        <span className="hk-muted">
          {decision.control.name} · granted by {decision.grantedBy}, {formatDay(decision.grantedAt)} · used {decision.uses ?? 0}{" "}
          {decision.uses === 1 ? "time" : "times"}
          {decision.service ? ` · held by ${decision.service}` : ""}
        </span>
        {decision.revoked ? (
          <span className="hk-muted">
            Revoked{decision.revoked.by ? ` by ${decision.revoked.by}` : ""}
            {decision.revoked.reason ? `: ${decision.revoked.reason}` : ""}
          </span>
        ) : null}
      </div>
      <div className="hk-standing__expiry">
        {expires !== undefined ? (
          <>
            <span className="hk-nums">{expires > now ? `Expires ${fromNow(decision.expiresAt!, now)}` : `Ended ${formatDay(decision.expiresAt!)}`}</span>
            {spent !== undefined && live ? (
              <Meter value={spent} tone={decision.state === "EXPIRING" ? "warning" : "default"} label="Share of its life spent" />
            ) : null}
          </>
        ) : (
          <span className="hk-muted">No expiry</span>
        )}
      </div>
      <div className="hk-standing__actions">
        {live ? (
          <Button size="sm" variant="ghost" onClick={() => setAsking(true)} disabled={asking} testId="standing-revoke">
            Revoke
          </Button>
        ) : null}
      </div>
      {asking ? (
        <div className="hk-standing__confirm">
          <Confirm
            title={`Revoke: ${decision.allows}`}
            verb="Revoke"
            danger
            changes="Later events that match are routed to a person again. Events it already settled stay settled."
            pending={revoke.pending}
            problem={reason.trim() ? undefined : "Say why it is revoked."}
            error={revoke.error}
            onConfirm={() =>
              void revoke
                .run(decision.id, reason.trim())
                .then(() => setAsking(false))
                .catch(() => undefined)
            }
            onCancel={() => setAsking(false)}
          >
            {reasonBox(reason, setReason)}
          </Confirm>
        </div>
      ) : null}
    </li>
  );
}

/** Standing decisions: what each allows, until when, how often it was used, and the way to revoke it. */
export function StandingList({ standing: given, now: fixedNow }: { standing?: StandingDecision[]; now?: number }) {
  const read = useStanding({ enabled: given === undefined });
  const now = useNow(1000, fixedNow);
  const list = given ?? read.data;
  return (
    <div className="hk hk-standing-list" data-testid="standing">
      <ErrorLine error={given ? undefined : read.error} />
      {list === undefined ? (
        read.error ? null : <div className="hk-skeleton hk-skeleton--block" aria-busy="true" />
      ) : list.length === 0 ? (
        <p className="hk-empty">No standing decision holds.</p>
      ) : (
        <ul className="hk-panel hk-panel--flush hk-list">
          {standingOrder(list).map((d) => (
            <StandingRow key={d.id} decision={d} now={now} />
          ))}
        </ul>
      )}
    </div>
  );
}

// ---------------------------------------------------------------- quality

/** Review times in buckets, with the control's minimum drawn as a line and the reviews under it in warning. */
export function ReviewHistogram({ buckets, minimumSeconds }: { buckets: Quality["reviewTimes"][number]["buckets"]; minimumSeconds?: number }) {
  const tallest = Math.max(1, ...buckets.map((b) => b.count));
  const lower = (i: number) => (i === 0 ? 0 : buckets[i - 1].upTo ?? 0);
  const label = (i: number) => (buckets[i].upTo == null ? `over ${lower(i)} s` : `up to ${buckets[i].upTo} s`);
  let at: number | undefined;
  if (minimumSeconds != null) {
    at = buckets.length;
    for (let i = 0; i < buckets.length; i++) {
      const hi = buckets[i].upTo;
      if (hi == null || minimumSeconds <= hi) {
        at = hi == null ? i : i + (minimumSeconds - lower(i)) / (hi - lower(i) || 1);
        break;
      }
    }
  }
  return (
    <figure className="hk-histogram" data-testid="review-histogram">
      <div className="hk-histogram__plot">
        {buckets.map((b, i) => {
          const below = minimumSeconds != null && b.upTo != null && b.upTo <= minimumSeconds;
          return (
            <div key={i} className="hk-histogram__col" title={`${label(i)}: ${b.count} reviews`}>
              <span className="hk-histogram__count hk-nums">{b.count || ""}</span>
              <span className={cx("hk-histogram__bar", below && "hk-histogram__bar--below")} style={{ blockSize: `${(b.count / tallest) * 80}%` }} />
            </div>
          );
        })}
        {at !== undefined ? (
          <span className="hk-histogram__minimum" style={{ insetInlineStart: `${(at / buckets.length) * 100}%` }} role="img" aria-label={`Minimum review time: ${minimumSeconds} s`}>
            <span>minimum {minimumSeconds} s</span>
          </span>
        ) : null}
      </div>
      <figcaption className="hk-histogram__axis">
        {buckets.map((_, i) => (
          <span key={i}>{label(i)}</span>
        ))}
      </figcaption>
    </figure>
  );
}

const OUTCOME_WORD: Record<string, string> = {
  triggered: "Triggered",
  shadow: "Shadow",
  approved: "Approved",
  denied: "Denied",
  edited: "Edited",
  sentBack: "Sent back",
  escalated: "Escalated",
  timedOut: "Timed out",
  abandoned: "Abandoned",
  killed: "Killed"
};

const pct = (n: number | null | undefined) => (n == null ? MISSING : formatPercent(n));

/** How well people oversee over a window: review times against minimums, each reviewer's rates, outcomes and signals. */
export function QualityView({ quality: given, window = "P30D" }: { quality?: Quality; window?: string }) {
  const read = useQuality(window, { enabled: given === undefined });
  const quality = given ?? read.data;
  if (!quality) {
    return (
      <div className="hk hk-quality" data-testid="quality">
        <ErrorLine error={given ? undefined : read.error} />
        {read.error ? null : <div className="hk-skeleton hk-skeleton--block" aria-busy="true" />}
      </div>
    );
  }
  const triggered = quality.outcomes.triggered;
  const coverage = quality.coverage?.triggers ? (quality.coverage.reachedPerson ?? 0) / quality.coverage.triggers : undefined;
  const agreement = quality.agreement?.twoPersonEvents ? (quality.agreement.agreed ?? 0) / quality.agreement.twoPersonEvents : undefined;
  return (
    <div className="hk hk-quality hk-stack" data-testid="quality">
      <Section
        title={`Over ${formatDuration(quality.window).replace(/^1 /, "")}`}
        testId="quality-outcomes"
        empty={Object.keys(quality.outcomes).length === 0 && !quality.reviewers.length ? "Nothing reached a person in this window." : undefined}
      >
        <div className="hk-stack">
          <dl className="hk-tiles">
            {Object.entries(quality.outcomes).map(([k, v]) => (
              <div key={k} className="hk-tile">
                <dt>{OUTCOME_WORD[k] ?? readable(k)}</dt>
                <dd className="hk-nums">{v.toLocaleString("en-US")}</dd>
                {triggered && k !== "triggered" ? <span className="hk-tile__sub hk-nums">{formatPercent(v / triggered)}</span> : null}
              </div>
            ))}
          </dl>
          <div className="hk-pair">
            {coverage !== undefined ? (
              <div className="hk-stack hk-stack--tight">
                <span className="hk-band">Reached a person</span>
                <Meter value={coverage} tone={coverage < 1 ? "warning" : "success"} label="Share of triggers that reached a person" />
                <span className="hk-muted hk-nums">
                  {quality.coverage?.reachedPerson} of {quality.coverage?.triggers} triggers, {formatPercent(coverage)}
                </span>
              </div>
            ) : null}
            {agreement !== undefined ? (
              <div className="hk-stack hk-stack--tight">
                <span className="hk-band">Two-person agreement</span>
                <Meter value={agreement} tone="accent" label="Share of two-person events where the reviewers agreed" />
                <span className="hk-muted hk-nums">
                  {quality.agreement?.agreed} of {quality.agreement?.twoPersonEvents} events, {formatPercent(agreement)}
                </span>
              </div>
            ) : null}
          </div>
        </div>
      </Section>

      {quality.signals?.length ? (
        <Section title="Signals" testId="quality-signals">
          <ul className="hk-checklist">
            {quality.signals.map((s, i) => {
              const over = s.limit !== undefined && s.value > s.limit;
              return (
                <li key={i} className="hk-checklist__item" data-testid="quality-signal">
                  <span>
                    <strong>{readable(s.signal)}</strong> <span className="hk-muted">on {s.control}</span>
                  </span>
                  <span className="hk-inline-list">
                    <span className="hk-nums">
                      {s.value <= 1 ? formatPercent(s.value) : s.value}
                      {s.limit !== undefined ? ` against ${s.limit <= 1 ? formatPercent(s.limit) : s.limit}` : ""}
                    </span>
                    <Chip tone={over ? "danger" : "success"}>{over ? "Over the limit" : "Within"}</Chip>
                  </span>
                </li>
              );
            })}
          </ul>
        </Section>
      ) : null}

      {quality.reviewTimes.map((rt) => (
        <Section
          key={rt.control}
          title={`Review times: ${rt.name ?? rt.control}`}
          status={
            rt.belowMinimumShare !== undefined ? (
              <Chip tone={rt.belowMinimumShare > 0.2 ? "danger" : rt.belowMinimumShare > 0 ? "warning" : "success"}>
                {formatPercent(rt.belowMinimumShare)} under the minimum
              </Chip>
            ) : undefined
          }
          testId="quality-review-times"
        >
          <ReviewHistogram buckets={rt.buckets} minimumSeconds={rt.minimumSeconds} />
        </Section>
      ))}

      {quality.reviewers.length ? (
        <Section title="Reviewers" flush testId="quality-reviewers">
          <table className="hk-table">
            <thead>
              <tr>
                <th scope="col">Reviewer</th>
                <th scope="col" className="hk-num">Decisions</th>
                <th scope="col">Approval rate</th>
                <th scope="col" className="hk-num">Overrides</th>
                <th scope="col" className="hk-num">With the AI</th>
                <th scope="col" className="hk-num">Median review</th>
                <th scope="col" className="hk-num">Open</th>
                <th scope="col" className="hk-num">Share</th>
              </tr>
            </thead>
            <tbody>
              {quality.reviewers.map((r) => (
                <tr key={r.subject} data-testid="quality-reviewer">
                  <th scope="row">{r.name ?? r.subject}</th>
                  <td className="hk-num">{r.decisions}</td>
                  <td>
                    {r.approvalRate !== undefined ? (
                      <span className="hk-meter-cell">
                        <Meter value={r.approvalRate} label={`${r.name ?? r.subject} approval rate`} />
                        <span className="hk-nums">{pct(r.approvalRate)}</span>
                      </span>
                    ) : (
                      MISSING
                    )}
                  </td>
                  <td className="hk-num">{pct(r.overrideRate)}</td>
                  <td className="hk-num">{pct(r.agreementWithAi)}</td>
                  <td className="hk-num">{r.medianReviewSeconds != null ? formatSpan(r.medianReviewSeconds * 1000) : MISSING}</td>
                  <td className="hk-num">{r.open ?? MISSING}</td>
                  <td className="hk-num">{pct(r.share)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Section>
      ) : null}
    </div>
  );
}

// ---------------------------------------------------------------- overseers

/** The overseer registry: each person's roles, training and whether they may decide today; each control's owner. */
export function OverseersList({ registry: given, now: fixedNow }: { registry?: OverseerRegistry; now?: number }) {
  const read = useRegistry({ enabled: given === undefined });
  const now = useNow(60_000, fixedNow);
  const registry = given ?? read.data;
  if (!registry) {
    return (
      <div className="hk hk-overseers" data-testid="overseers">
        <ErrorLine error={given ? undefined : read.error} />
        {read.error ? null : <div className="hk-skeleton hk-skeleton--block" aria-busy="true" />}
      </div>
    );
  }
  const nameOf = (s: string) => registry.overseers.find((o) => o.subject === s)?.name ?? s;
  return (
    <div className="hk hk-overseers hk-stack" data-testid="overseers">
      <ul className="hk-panel hk-panel--flush hk-list">
        {registry.overseers.map((o) => (
          <li key={o.subject} className="hk-person" data-testid="overseer" data-qualified={o.qualified}>
            <div className="hk-person__who">
              <span className="hk-person__name">{o.name ?? o.subject}</span>
              <span className="hk-muted">{o.subject}</span>
              {o.reportsTo ? <span className="hk-muted">reports to {nameOf(o.reportsTo)}</span> : null}
            </div>
            <div className="hk-person__roles hk-inline-list">
              {o.roles.map((r) => (
                <Tag key={r}>{readable(r)}</Tag>
              ))}
            </div>
            <div className="hk-person__status">
              {o.qualified ? <Chip tone="success">Qualified</Chip> : <Chip tone="danger">Not qualified</Chip>}
              {!o.qualified && o.qualifiedReason ? <span className="hk-muted">{o.qualifiedReason}</span> : null}
            </div>
            {o.attestations?.length ? (
              <ul className="hk-person__training">
                {o.attestations.map((a) => {
                  const expired = a.expired ?? (a.expiresAt ? Date.parse(a.expiresAt) < now : false);
                  return (
                    <li key={a.id}>
                      <span>{readable(a.id)}</span>
                      <span className="hk-muted hk-nums">
                        {a.expiresAt ? `${expired ? "expired" : "until"} ${formatDay(a.expiresAt)}` : `since ${formatDay(a.completedAt)}`}
                      </span>
                      {expired ? <Chip tone="danger">Expired</Chip> : null}
                    </li>
                  );
                })}
              </ul>
            ) : null}
            {o.authority?.length ? (
              <p className="hk-muted hk-person__authority">
                Authority:{" "}
                {o.authority
                  .map((a) => `${a.controls.includes("*") ? "every control" : listNames(a.controls)}${a.actions ? ` (${a.actions.map((x) => readable(x).toLowerCase()).join(", ")})` : ""}`)
                  .join("; ")}
              </p>
            ) : null}
          </li>
        ))}
      </ul>
      {registry.owners?.length ? (
        <Section title="Accountable owners" testId="overseers-owners">
          <Facts facts={registry.owners.map((o) => ({ label: o.control, value: readable(o.accountable) }))} />
        </Section>
      ) : null}
    </div>
  );
}

// ---------------------------------------------------------------- summary

/** Counts per day, oldest first, as small columns. */
function DayColumns({ counts, label }: { counts: number[]; label: string }) {
  const tallest = Math.max(1, ...counts);
  return (
    <span className="hk-days" role="img" aria-label={`${label}: ${counts.join(", ")}`}>
      {counts.map((c, i) => (
        <span key={i} className={cx("hk-days__col", c > 0 && "hk-days__col--on")} style={{ blockSize: `${Math.max(8, (c / tallest) * 100)}%` }} />
      ))}
    </span>
  );
}

const LOOPS: { key: keyof Summary["loop"]; word: string; hint: string }[] = [
  { key: "in", word: "In the loop", hint: "The work waits for a person." },
  { key: "on", word: "On the loop", hint: "A person watches; the work carries on." },
  { key: "over", word: "Over the loop", hint: "A person can halt the system." }
];

function Tile({ label, value, tone, sub, children, testId }: { label: string; value: ReactNode; tone?: Tone; sub?: ReactNode; children?: ReactNode; testId?: string }) {
  return (
    <div className="hk-tile" data-tone={tone} data-testid={testId}>
      <dt>{label}</dt>
      <dd className="hk-nums">{value}</dd>
      {sub ? <span className="hk-tile__sub">{sub}</span> : null}
      {children}
    </div>
  );
}

/** The figures of the oversight: what waits in each loop, what is at risk or overdue, halts and standing decisions. */
export function SummaryTiles({ summary: given, control }: { summary?: Summary; control?: string }) {
  const read = useSummary(control, { enabled: given === undefined });
  const summary = given ?? read.data;
  if (!summary) {
    return (
      <div className="hk hk-summary" data-testid="summary">
        <ErrorLine error={given ? undefined : read.error} />
        {read.error ? null : <div className="hk-skeleton hk-skeleton--row" aria-busy="true" />}
      </div>
    );
  }
  const halted = summary.halts.filter((h) => h.halted).length;
  const busiest = Object.entries(summary.patterns).sort((a, b) => b[1] - a[1])[0];
  return (
    <dl className="hk hk-summary hk-tiles" data-testid="summary">
      {LOOPS.map(({ key, word, hint }) => {
        const loop: LoopFigures = summary.loop[key];
        return (
          <Tile
            key={key}
            label={word}
            value={loop.pending}
            tone={loop.overdue ? "danger" : undefined}
            sub={<span title={hint}>{loop.overdue ? `${loop.overdue} overdue` : "none overdue"}</span>}
            testId={`summary-${key}`}
          >
            {loop.raised7d?.length ? <DayColumns counts={loop.raised7d} label={`${word}, raised in the last 7 days`} /> : null}
          </Tile>
        );
      })}
      <Tile label="At risk" value={summary.atRisk} tone={summary.atRisk ? "warning" : undefined} testId="summary-at-risk" />
      <Tile label="Overdue" value={summary.overdue} tone={summary.overdue ? "danger" : undefined} testId="summary-overdue" />
      <Tile
        label="Halted"
        value={halted}
        tone={halted ? "danger" : undefined}
        sub={summary.halts.length ? `of ${summary.halts.length} scopes` : "no halt declared"}
        testId="summary-halted"
      />
      <Tile
        label="Standing decisions"
        value={summary.standing.active}
        tone={summary.standing.expiring ? "warning" : undefined}
        sub={summary.standing.expiring ? `${summary.standing.expiring} expiring` : "none expiring"}
        testId="summary-standing"
      />
      {summary.unownedControls ? (
        <Tile label="Unowned controls" value={summary.unownedControls} tone="warning" testId="summary-unowned" />
      ) : null}
      {busiest ? <Tile label="Busiest pattern" value={busiest[1]} sub={patternName(busiest[0])} testId="summary-pattern" /> : null}
    </dl>
  );
}
