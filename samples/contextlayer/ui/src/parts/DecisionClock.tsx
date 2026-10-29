import { formatMoment, outcomeOf, readable, type HumanEvent, type OverseerRegistry } from "@akka-human/kit";
import { afterDeadline, decisionClock, deciders, hms, LINK_REVIEW } from "../model";
import { Chip } from "./ui";

// The decision clock: the deadline as the outer ring and the minimum review
// time as the inner one, with the people who may decide beside it and the
// people who may not struck out.

const R = 96;
const r = 80;
const CR = 2 * Math.PI * R;
const cr = 2 * Math.PI * r;

const TONE_COLOUR: Record<string, string> = {
  running: "var(--gold)",
  "at-risk": "var(--warn)",
  overdue: "var(--bad)",
  met: "var(--ok)",
  missed: "var(--bad)"
};

/** How contextlayer names the people a control keeps from deciding. */
const WORDS: Record<string, { proposer: string; builder: string }> = {
  [LINK_REVIEW]: { proposer: "loaded one of the records", builder: "loaded one of the records" }
};

export function DecisionClockView({
  event,
  registry,
  me,
  now
}: {
  event: HumanEvent;
  registry?: OverseerRegistry;
  me?: string;
  now: number;
}) {
  const clock = decisionClock(event, now, me);
  const people = deciders(event, registry, me, WORDS[event.control.rowId]);
  const nameOf = (s: string) => registry?.overseers.find((o) => o.subject === s)?.name ?? s;
  const { minimum } = clock;
  const reviewed = minimum.seconds === 0 ? 1 : minimum.started ? minimum.elapsed / minimum.seconds : 0;
  const roles = event.routing.roles.map((x) => readable(x).toLowerCase()).join(" or ");
  const barred = (event.routing.independentOf ?? []).map(nameOf);
  const needs = event.routing.required <= 1 ? "1 decision" : `${event.routing.required} decisions`;

  return (
    <div className="cl-clock" data-testid="decision-clock">
      <div className="cl-ring">
        <svg viewBox="0 0 220 220" aria-hidden="true">
          <circle cx="110" cy="110" r={R} fill="none" stroke="var(--surface-3)" strokeWidth="11" />
          <circle
            cx="110"
            cy="110"
            r={R}
            fill="none"
            stroke={TONE_COLOUR[clock.tone] ?? "var(--faint)"}
            strokeWidth="11"
            strokeLinecap="round"
            strokeDasharray={`${CR * clock.remaining} ${CR}`}
          />
          <circle cx="110" cy="110" r={r} fill="none" stroke="var(--surface-2)" strokeWidth="6" />
          <circle
            cx="110"
            cy="110"
            r={r}
            fill="none"
            stroke={minimum.met && minimum.started ? "var(--ok)" : "var(--gold)"}
            strokeWidth="6"
            strokeLinecap="round"
            strokeDasharray={`${cr * reviewed} ${cr}`}
          />
        </svg>
        <div className="cl-ring__ctr">
          {event.pending ? (
            <>
              <span className="cl-muted cl-small">{clock.left !== undefined && clock.left < 0 ? "past the deadline" : "left to decide"}</span>
              <span className="cl-ring__cd" data-testid="clock-left">
                {clock.left === undefined ? "—" : hms(Math.abs(clock.left))}
              </span>
              <span className="cl-muted cl-small">{afterDeadline(event)}</span>
            </>
          ) : (
            <>
              <span className="cl-muted cl-small">decided</span>
              <span className="cl-ring__cd cl-ring__cd--word">{outcomeOf(event).word}</span>
              <span className="cl-muted cl-small">{event.closedAt ? formatMoment(event.closedAt) : ""}</span>
            </>
          )}
          <span className="cl-small cl-ring__min" data-testid="clock-minimum">
            {minimum.seconds === 0 ? (
              <span className="cl-muted">no minimum review time</span>
            ) : minimum.started && minimum.met ? (
              <span className="cl-ok">minimum review time met</span>
            ) : minimum.started ? (
              <span className="cl-muted">
                looked at for {minimum.elapsed} s of {minimum.seconds} s
              </span>
            ) : (
              <span className="cl-muted">{minimum.seconds} s of review, from your claim</span>
            )}
          </span>
        </div>
      </div>

      <div className="cl-ppl">
        <p className="cl-muted cl-clock__sum">
          Needs {needs} from {/^[aeiou]/.test(roles) ? "an" : "a"} {roles}
          {barred.length ? ` other than ${barred.join(" and ")}` : ""}.
          {event.pending && event.sla.dueAt ? ` Due ${formatMoment(event.sla.dueAt)}, ${afterDeadline(event)}.` : ""}
        </p>
        <ul className="cl-ppl__list" aria-label="Who may decide">
          {people.map((p) => (
            <li key={p.subject} className={`cl-pp${p.may ? "" : " cl-pp--no"}${p.me ? " cl-pp--me" : ""}`} data-testid="decider" data-may={p.may}>
              <span className="cl-pp__av">{p.initials}</span>
              <div className="cl-pp__who">
                <div className="cl-pp__nm">
                  {p.name}
                  {p.me ? <span className="cl-muted"> (you)</span> : null}
                </div>
                <div className="cl-muted cl-small">{p.note}</div>
              </div>
              <span className="cl-pp__r">
                {p.may ? (
                  <Chip tone="ok">May decide</Chip>
                ) : p.barred === "requested" || p.barred === "built" || p.barred === "independent" ? (
                  <Chip tone="bad">{p.barred === "independent" ? "Barred" : event.control.rowId === LINK_REVIEW ? "Loaded it" : p.barred === "built" ? "Built it" : "Requested"}</Chip>
                ) : p.barred === "unqualified" ? (
                  <Chip tone="warn">Not qualified</Chip>
                ) : p.barred === "voted" ? (
                  <Chip tone="mute">Voted</Chip>
                ) : (
                  <Chip tone="mute">Closed</Chip>
                )}
              </span>
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
}
