import { useId } from "react";
import type { ActionDetail, BehaviourDetail, OutputDetail, Subject, SystemDetail } from "../types";
import { Chip, Facts, Meter, Prose, type Fact } from "../components/ui";
import {
  formatDuration,
  formatMoment,
  formatPercent,
  formatShortMoment,
  keyWords,
  listNames,
  readable,
  valueText
} from "../words";

// The subjects an agent's or system's work raises: an action it wants to take
// (or took, with an unknown result), a behaviour that crossed a threshold, a
// scope to halt or restart, and an output someone must look at.

/** A role with its article, as a sentence names it: "an AI system owner". */
const aRole = (role: string) => {
  const text = readable(role);
  const first = text.split(" ")[0];
  const lower = first === first.toUpperCase() ? text : text.charAt(0).toLowerCase() + text.slice(1);
  return `${/^[aeiou]/i.test(lower) ? "an" : "a"} ${lower}`;
};

/** The authority an action lacks, one sentence per requirement. */
export function authoritySentences(required: Record<string, unknown>): string[] {
  return Object.entries(required).map(([key, value]) => {
    const roles = typeof value === "string" ? [value] : Array.isArray(value) ? value.map(String) : [];
    if (/^(roles?|approvers?)$/i.test(key) && roles.length) return `Approval by ${roles.map(aRole).join(" or ")}.`;
    return `${keyWords(key)}: ${Array.isArray(value) ? listNames(value.map(String)) : valueText(value, key)}.`;
  });
}

/** Named values in a monospace table, as a tool's arguments are written. */
export function Arguments({ values, label, testId }: { values: Record<string, unknown>; label: string; testId?: string }) {
  const entries = Object.entries(values);
  if (!entries.length) return <p className="hk-muted">No arguments.</p>;
  return (
    <table className="hk-kv" aria-label={label} data-testid={testId}>
      <tbody>
        {entries.map(([name, value]) => (
          <tr key={name}>
            <th scope="row">{name}</th>
            <td>{typeof value === "object" && value !== null ? JSON.stringify(value) : valueText(value, name)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

export function ActionView({ subject, detail }: { subject: Subject; detail: ActionDetail }) {
  const facts: Fact[] = [{ label: "Tool", value: <code className="hk-code">{detail.tool}</code> }];
  if (subject.agent) facts.push({ label: "Called by", value: readable(subject.agent) });
  if (detail.requestedBy) facts.push({ label: "Requested by", value: detail.requestedBy });
  if (detail.sentAt) facts.push({ label: "Sent", value: formatMoment(detail.sentAt) });
  if (detail.lastKnown) facts.push({ label: "Last known", value: detail.lastKnown });
  const missing = detail.authority?.missing ?? [];
  const required = authoritySentences(detail.authority?.required ?? {});
  if (missing.length)
    facts.push({
      label: "Authority it lacks",
      value: (
        <span className="hk-inline-list">
          {missing.map((m) => (
            <Chip key={m} tone="warning" icon={false}>
              {m}
            </Chip>
          ))}
        </span>
      )
    });
  if (required.length) facts.push({ label: "Requires", value: required.join(" ") });
  const steps = detail.recentSteps ?? [];
  return (
    <div className="hk-stack" data-testid="subject-action">
      <Facts facts={facts} testId="action-facts" />
      <div className="hk-stack hk-stack--tight">
        <h3 className="hk-subheading">{detail.sentAt ? "What was sent" : "Arguments"}</h3>
        <Arguments values={detail.arguments} label={`${detail.tool} arguments`} testId="action-arguments" />
      </div>
      {steps.length ? (
        <div className="hk-stack hk-stack--tight">
          <h3 className="hk-subheading">Recent steps</h3>
          <ol className="hk-steps" data-testid="action-steps">
            {steps.map((step, i) => (
              <li key={i}>
                <span>{typeof step.title === "string" ? step.title : JSON.stringify(step)}</span>
                {typeof step.at === "string" ? <span className="hk-muted hk-nums">{formatShortMoment(step.at)}</span> : null}
              </li>
            ))}
          </ol>
        </div>
      ) : null}
    </div>
  );
}

// ---------------------------------------------------------------- behaviour

const unitOf = (unit: string | undefined) => (unit === "%" ? "%" : unit ? ` ${unit}` : "");

/** Whether `value` stands against `threshold` as the trigger's comparison says. */
export function breaches(value: number, comparison: string | undefined, threshold: number | undefined): boolean {
  if (threshold === undefined) return false;
  switch (comparison) {
    case "<":
      return value < threshold;
    case "<=":
      return value <= threshold;
    case ">":
      return value > threshold;
    case ">=":
      return value >= threshold;
    case "==":
      return value === threshold;
    case "!=":
      return value !== threshold;
    default:
      return false;
  }
}

const W = 480;
const H = 132;
const PAD = { left: 8, right: 96, top: 14, bottom: 22 };

/**
 * The metric over its window as a sparkline: the series as a line over a soft
 * area, the threshold it crossed dashed in danger, the baseline dotted, and
 * the latest value marked. A table under it says the same to a screen reader.
 */
export function Sparkline({ detail }: { detail: BehaviourDetail }) {
  const titleId = useId();
  const unit = unitOf(detail.unit);
  const series = detail.series?.length ? detail.series : [];
  if (!series.length) return <p className="hk-muted">The metric has no points yet.</p>;
  const times = series.map((p) => Date.parse(p.at));
  const t0 = Math.min(...times);
  const t1 = Math.max(...times);
  const marks = [...series.map((p) => p.value), detail.threshold, detail.baseline].filter(
    (n): n is number => typeof n === "number"
  );
  const lo = Math.min(0, ...marks);
  const hi = Math.max(...marks) * 1.12 || 1;
  const innerW = W - PAD.left - PAD.right;
  const x = (t: number) => PAD.left + (t1 === t0 ? innerW : ((t - t0) / (t1 - t0)) * innerW);
  const y = (v: number) => PAD.top + (1 - (v - lo) / (hi - lo)) * (H - PAD.top - PAD.bottom);
  const points = series.map((p, i) => `${x(times[i]).toFixed(1)},${y(p.value).toFixed(1)}`);
  const line = series.length === 1 ? `${PAD.left},${y(series[0].value)} ${points[0]}` : points.join(" ");
  const area = `${PAD.left},${H - PAD.bottom} ${line} ${x(t1)},${H - PAD.bottom}`;
  const last = series[series.length - 1];
  const breach = breaches(last.value, detail.comparison, detail.threshold);
  const describe = [
    `${detail.metric}${detail.window ? ` over ${formatDuration(detail.window)}` : ""}`,
    `now ${last.value}${unit}`,
    detail.threshold !== undefined ? `threshold ${detail.comparison ?? ""} ${detail.threshold}${unit}` : "",
    detail.baseline !== undefined ? `baseline ${detail.baseline}${unit}` : ""
  ]
    .filter(Boolean)
    .join(", ");
  return (
    <figure className="hk-spark" data-testid="behaviour-sparkline">
      <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-labelledby={titleId}>
        <title id={titleId}>{describe}</title>
        <polygon className="hk-spark__area" points={area} />
        {detail.baseline !== undefined ? (
          <g className="hk-spark__baseline" data-testid="sparkline-baseline">
            <line x1={PAD.left} x2={W - PAD.right} y1={y(detail.baseline)} y2={y(detail.baseline)} />
            <text x={W - PAD.right + 6} y={y(detail.baseline) + 3}>
              baseline {detail.baseline}
              {unit}
            </text>
          </g>
        ) : null}
        {detail.threshold !== undefined ? (
          <g className="hk-spark__threshold" data-testid="sparkline-threshold">
            <line x1={PAD.left} x2={W - PAD.right} y1={y(detail.threshold)} y2={y(detail.threshold)} />
            <text x={W - PAD.right + 6} y={y(detail.threshold) + 3}>
              {detail.comparison ?? ""} {detail.threshold}
              {unit}
            </text>
          </g>
        ) : null}
        <polyline className="hk-spark__line" points={line} fill="none" />
        <circle
          className={breach ? "hk-spark__dot hk-spark__dot--breach" : "hk-spark__dot"}
          cx={x(times[times.length - 1])}
          cy={y(last.value)}
          r={4}
        />
        <text className="hk-spark__axis" x={PAD.left} y={H - 6}>
          {formatShortMoment(series[0].at)}
        </text>
        {series.length > 1 ? (
          <text className="hk-spark__axis" x={W - PAD.right} y={H - 6} textAnchor="end">
            {formatShortMoment(last.at)}
          </text>
        ) : null}
      </svg>
      <table className="hk-sr-only">
        <caption>{detail.metric}</caption>
        <tbody>
          {series.map((p) => (
            <tr key={p.at}>
              <td>{p.at}</td>
              <td>{p.value}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </figure>
  );
}

export function BehaviourView({ detail }: { detail: BehaviourDetail }) {
  const unit = unitOf(detail.unit);
  const breach = breaches(detail.value, detail.comparison, detail.threshold);
  const figures = [
    { label: "Now", value: `${detail.value}${unit}`, tone: breach ? "danger" : undefined, testId: "behaviour-value" },
    detail.threshold !== undefined
      ? { label: "Threshold", value: `${detail.comparison ?? ""} ${detail.threshold}${unit}`.trim() }
      : undefined,
    detail.baseline !== undefined ? { label: "Baseline", value: `${detail.baseline}${unit}` } : undefined,
    detail.window ? { label: "Window", value: formatDuration(detail.window) } : undefined
  ].filter((f): f is NonNullable<typeof f> => f !== undefined);
  const contributors = detail.contributors ?? [];
  return (
    <div className="hk-stack" data-testid="subject-behaviour">
      <div className="hk-figures">
        <span className="hk-figures__metric">{readable(detail.metric)}</span>
        <dl className="hk-figures__list">
          {figures.map((f) => (
            <div key={f.label} className="hk-figure" data-tone={f.tone} data-testid={f.testId}>
              <dt>{f.label}</dt>
              <dd>{f.value}</dd>
            </div>
          ))}
        </dl>
      </div>
      <Sparkline detail={detail} />
      {contributors.length ? (
        <div className="hk-stack hk-stack--tight">
          <h3 className="hk-subheading">What contributed</h3>
          <ul className="hk-bars" data-testid="behaviour-contributors">
            {contributors.map((c) => (
              <li key={`${c.kind}-${c.ref}`} className="hk-bars__row">
                <span className="hk-bars__label">
                  <span className="hk-muted">{readable(c.kind)}</span> <code className="hk-code">{c.ref}</code>
                </span>
                {c.weight !== undefined ? (
                  <>
                    <Meter value={c.weight} label={`${c.ref} weight ${formatPercent(c.weight)}`} />
                    <span className="hk-nums hk-muted">{formatPercent(c.weight)}</span>
                  </>
                ) : null}
              </li>
            ))}
          </ul>
        </div>
      ) : null}
    </div>
  );
}

// ---------------------------------------------------------------- system

const CHECK_WORD = { true: "Passed", false: "Not passed", null: "Not run" } as const;

/** Safe-state checks, each with whether it passed, and how many have. */
export function SafeStateChecks({ checks, lift }: { checks: SystemDetail["safeStateChecks"]; lift?: boolean }) {
  if (!checks.length) return <p className="hk-muted">No safe-state check is declared.</p>;
  const open = checks.filter((c) => c.passed !== true).length;
  return (
    <div className="hk-stack hk-stack--tight">
      <ul className="hk-checklist" data-testid="safe-state-checks">
        {checks.map((c) => {
          const key = c.passed === true ? "true" : c.passed === false ? "false" : "null";
          return (
            <li key={c.name} className="hk-checklist__item" data-testid="safe-state-check" data-passed={key}>
              <span>{c.name}</span>
              <Chip tone={c.passed === true ? "success" : c.passed === false ? "danger" : "default"}>{CHECK_WORD[key]}</Chip>
            </li>
          );
        })}
      </ul>
      <p className={open ? "hk-note hk-note--warning" : "hk-note"} data-testid="safe-state-summary">
        {open === 0
          ? `All ${checks.length === 1 ? "one check has" : `${checks.length} checks have`} passed.`
          : `${checks.length - open} of ${checks.length} ${checks.length === 1 ? "check has" : "checks have"} passed.${lift ? " A restart can be approved only once every check passes." : ""}`}
      </p>
    </div>
  );
}

const AFFECTED: Record<string, string> = {
  "all-runtime-operations": "Every runtime operation",
  "tool-calls-only": "Tool calls only",
  "llm-calls-only": "Model calls only",
  "write-operations-only": "Writes only"
};

export function SystemView({ detail }: { detail: SystemDetail }) {
  const target = readable(detail.target);
  const ask =
    detail.lift === true
      ? `Asks to restart ${target}.`
      : detail.lift === false
        ? `Asks to halt ${target}.`
        : `${target} is ${detail.halted ? "halted" : "running"}.`;
  const facts: Fact[] = [
    { label: "Covers", value: target },
    { label: "Scope", value: readable(detail.scope) },
    { label: "Affected calls", value: AFFECTED[detail.affectedCalls] ?? readable(detail.affectedCalls) },
    {
      label: "Now",
      value: <Chip tone={detail.halted ? "danger" : "success"}>{detail.halted ? "Halted" : "Running"}</Chip>
    }
  ];
  if (detail.callsPerMinute !== undefined)
    facts.push({
      label: detail.halted ? "Calls a minute held" : "Calls a minute",
      value: detail.callsPerMinute.toLocaleString("en-US")
    });
  return (
    <div className="hk-stack" data-testid="subject-system">
      <p className="hk-lead">{ask}</p>
      <Facts facts={facts} testId="system-facts" />
      <div className="hk-stack hk-stack--tight">
        <h3 className="hk-subheading">Safe-state checks</h3>
        <SafeStateChecks checks={detail.safeStateChecks} lift={detail.lift} />
      </div>
    </div>
  );
}

// ---------------------------------------------------------------- output

export function OutputView({ detail }: { detail: OutputDetail }) {
  const decision = detail.decision;
  const facts: Fact[] = [];
  if (decision?.summary) facts.push({ label: "Decision", value: decision.summary });
  if (decision?.affects) facts.push({ label: "Affects", value: decision.affects });
  if (decision?.ref) facts.push({ label: "Reference", value: <code className="hk-code">{decision.ref}</code> });
  if (detail.flowId) facts.push({ label: "Flow", value: <code className="hk-code">{detail.flowId}</code> });
  return (
    <div className="hk-stack" data-testid="subject-output">
      <figure className="hk-quote" data-testid="output-text">
        <figcaption className="hk-quote__caption">{detail.replacement ? "The system's output" : "Output"}</figcaption>
        <blockquote className={detail.replacement ? "hk-quote__body hk-quote__body--replaced" : "hk-quote__body"}>
          {detail.output}
        </blockquote>
      </figure>
      {detail.replacement ? (
        <figure className="hk-quote hk-quote--brand" data-testid="output-replacement">
          <figcaption className="hk-quote__caption">Replaced with</figcaption>
          <blockquote className="hk-quote__body">{detail.replacement}</blockquote>
        </figure>
      ) : null}
      {detail.flag ? (
        <Prose heading={`Flagged by ${detail.flag.by ?? "someone"}`} testId="output-flag">
          {detail.flag.note ?? "No note."}
        </Prose>
      ) : null}
      {facts.length ? <Facts facts={facts} testId="output-facts" /> : null}
    </div>
  );
}

/** How long ago `at` was, or how far off, in words. */
export function fromNow(at: string, now: number): string {
  const ms = Date.parse(at) - now;
  if (!Number.isFinite(ms)) return at;
  const words = humanSpan(Math.abs(ms));
  return ms >= 0 ? `in ${words}` : `${words} ago`;
}

/** A span as people say it: "3 days", "5 hours", "12 minutes". */
export function humanSpan(ms: number): string {
  const minutes = Math.round(ms / 60_000);
  if (minutes < 1) return "less than a minute";
  if (minutes < 60) return `${minutes} minute${minutes === 1 ? "" : "s"}`;
  const hours = Math.round(minutes / 60);
  if (hours < 48) return `${hours} hour${hours === 1 ? "" : "s"}`;
  const days = Math.round(hours / 24);
  return `${days} day${days === 1 ? "" : "s"}`;
}
