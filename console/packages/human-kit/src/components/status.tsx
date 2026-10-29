import type { Condition, HumanEventRow, Sla, SubjectKind } from "../types";
import {
  actionDone,
  conclusionLabel,
  formatDay,
  formatShortMoment,
  readClock,
  stateLabel,
  subjectKindLabel,
  type ClockTone
} from "../words";
import { Chip, cx, Icon, type Tone } from "./ui";

// How an event stands: its state, its outcome and verdict as chips, its
// approval's conditions, and its deadline as a ring that fills clockwise as
// the time is spent.

/**
 * The conditions an approval carries, each with its due day and whether it
 * was met; one past its day and not yet met is overdue at `now`.
 */
export function ConditionList({ conditions, now }: { conditions: Condition[]; now: number }) {
  return (
    <ul className="hk-conditions" aria-label="Conditions" data-testid="conditions">
      {conditions.map((c, i) => {
        const overdue = c.met == null && c.dueAt ? Date.parse(c.dueAt) < now : false;
        const [word, tone]: [string, Tone] =
          c.met === true ? ["Met", "success"] : c.met === false ? ["Not met", "danger"] : overdue ? ["Overdue", "warning"] : ["Open", "default"];
        return (
          <li key={i} className="hk-conditions__item" data-testid="condition" data-met={String(c.met ?? "open")}>
            <span>{c.text}</span>
            <span className="hk-inline-list">
              {c.dueAt ? <span className="hk-muted hk-nums">by {formatDay(c.dueAt)}</span> : null}
              <Chip tone={tone} icon={tone !== "default"}>
                {word}
              </Chip>
            </span>
          </li>
        );
      })}
    </ul>
  );
}

const STATE_TONE: Record<string, Tone> = {
  ESCALATED: "warning",
  TIMED_OUT: "warning",
  KILLED: "danger",
  REOPENED: "accent",
  CHANGES_REQUESTED: "default",
  AWAITING_SECOND: "default"
};

export function StateChip({ state }: { state: string }) {
  return (
    <Chip tone={STATE_TONE[state] ?? "default"} icon={false} testId="hk-state">
      {stateLabel(state)}
    </Chip>
  );
}

const CONCLUSION_TONE: Record<string, Tone> = {
  upheld: "success",
  breached: "danger",
  error: "danger",
  inconclusive: "default"
};

export function ConclusionChip({ conclusion }: { conclusion: string }) {
  return (
    <Chip tone={CONCLUSION_TONE[conclusion] ?? "default"} testId="hk-conclusion">
      {conclusionLabel(conclusion)}
    </Chip>
  );
}

/** How a closed event ended, in one word and its tone. */
export function outcomeOf(row: Pick<HumanEventRow, "state" | "outcome">): { word: string; tone: Tone } {
  const sla = row.outcome?.slaOutcome;
  if (sla === "TIMED_OUT") return { word: "Timed out", tone: "warning" };
  if (sla === "KILLED") return { word: "Killed", tone: "danger" };
  if (sla === "ABANDONED") return { word: row.state === "WITHDRAWN" ? "Withdrawn" : "Abandoned", tone: "default" };
  if (row.state === "WITHDRAWN") return { word: "Withdrawn", tone: "default" };
  if (row.outcome?.action) return { word: actionDone(row.outcome.action), tone: "default" };
  if (sla === "ESCALATED") return { word: "Escalated", tone: "warning" };
  return { word: stateLabel(row.state), tone: STATE_TONE[row.state] ?? "default" };
}

export function OutcomeChip({ row }: { row: Pick<HumanEventRow, "state" | "outcome"> }) {
  const { word, tone } = outcomeOf(row);
  return (
    <Chip tone={tone} icon={false} testId="hk-outcome">
      {word}
    </Chip>
  );
}

const SUBJECT_ICON: Record<SubjectKind, string> = {
  action: "tool",
  behaviour: "chart",
  change: "change",
  system: "system",
  output: "output",
  records: "records",
  appeal: "appeal",
  exception: "exception",
  conversation: "conversation",
  attestation: "attestation"
};

export const subjectIcon = (kind: string) => SUBJECT_ICON[kind as SubjectKind] ?? "unknown";

const SIZE = {
  sm: { px: 28, stroke: 2.5, icon: 12 },
  md: { px: 40, stroke: 3, icon: 16 },
  lg: { px: 56, stroke: 4, icon: 20 }
} as const;

const TONE_WORD: Record<ClockTone, string> = {
  none: "No deadline",
  running: "On time",
  "at-risk": "At risk",
  overdue: "Overdue",
  paused: "Paused",
  met: "Kept",
  missed: "Missed"
};

/**
 * The deadline as a ring around the subject kind's icon: it fills clockwise as
 * the time is spent, in warning once the event is at risk and in danger once
 * it is overdue, with the time left or over beside it. An event without a
 * deadline has an empty ring and its age.
 */
export function DeadlineRing({
  sla,
  detectedAt,
  now,
  kind,
  size = "md",
  showLabel = true,
  testId = "hk-deadline"
}: {
  sla: Pick<Sla, "state" | "dueAt" | "target" | "startedAt" | "atRiskAt">;
  detectedAt: string;
  now: number;
  kind?: string;
  size?: keyof typeof SIZE;
  showLabel?: boolean;
  testId?: string;
}) {
  const clock = readClock(sla, detectedAt, now);
  const { px, stroke, icon } = SIZE[size];
  const r = (px - stroke) / 2 - 0.5;
  const c = 2 * Math.PI * r;
  const due = sla.dueAt ? formatShortMoment(sla.dueAt) : undefined;
  const spoken = [
    kind ? subjectKindLabel(kind) : undefined,
    `${TONE_WORD[clock.tone]}: ${clock.label}`,
    due && clock.tone !== "met" && clock.tone !== "missed" ? `due ${due}` : undefined
  ]
    .filter(Boolean)
    .join(", ");
  return (
    <span
      className={cx("hk-ring", `hk-ring--${size}`, `hk-ring--${clock.tone}`)}
      role="img"
      aria-label={spoken}
      title={spoken}
      data-testid={testId}
      data-tone={clock.tone}
    >
      <span className="hk-ring__dial" style={{ inlineSize: px, blockSize: px }}>
        <svg viewBox={`0 0 ${px} ${px}`} aria-hidden="true" focusable="false">
          <circle className="hk-ring__track" cx={px / 2} cy={px / 2} r={r} strokeWidth={stroke} fill="none" />
          <circle
            className="hk-ring__arc"
            cx={px / 2}
            cy={px / 2}
            r={r}
            strokeWidth={stroke}
            fill="none"
            strokeLinecap="round"
            strokeDasharray={`${clock.fraction * c} ${c}`}
            transform={`rotate(-90 ${px / 2} ${px / 2})`}
          />
        </svg>
        {kind ? (
          <span className="hk-ring__icon" style={{ inlineSize: icon, blockSize: icon }}>
            <Icon name={subjectIcon(kind)} />
          </span>
        ) : null}
      </span>
      {showLabel ? (
        <span className="hk-ring__text" aria-hidden="true">
          <span className="hk-ring__label" data-testid={`${testId}-label`}>
            {clock.label}
          </span>
          {size !== "sm" && due && (clock.tone === "running" || clock.tone === "at-risk" || clock.tone === "overdue") ? (
            <span className="hk-ring__due">due {due}</span>
          ) : null}
        </span>
      ) : null}
    </span>
  );
}
