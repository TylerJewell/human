import { afterDeadline, barredWord, decisionClock, deciders, formatCountdown, type DeciderWords } from "../clock";
import { useNow, useRegistry, useReviewer } from "../hooks";
import type { HumanEvent, OverseerRegistry } from "../types";
import { formatMoment, readable } from "../words";
import { outcomeOf } from "./status";
import { Chip, cx, type Tone } from "./ui";

// The decision clock: the deadline as the outer ring and the minimum review
// time, counted from the acting reviewer's claim, as the inner one, with the
// people who may decide beside it and the people who may not struck through
// with why. Both rings run on the service's clock.

const R = 96;
const r = 80;
const CR = 2 * Math.PI * R;
const cr = 2 * Math.PI * r;

const BARRED_TONE: Record<string, Tone> = {
  requested: "danger",
  built: "danger",
  independent: "danger",
  unqualified: "warning",
  voted: "default"
};

export interface DecisionClockProps {
  event: HumanEvent;
  /** The overseer registry; read from the client when left out. */
  registry?: OverseerRegistry;
  /** The acting reviewer's subject; read from the client when left out. */
  me?: string;
  /** A fixed time, for tests and pictures; the clock runs otherwise. */
  now?: number;
  /** How this service names whoever asked for or built the subject. */
  words?: DeciderWords;
}

export function DecisionClock({ event, registry: givenRegistry, me: givenMe, now: fixedNow, words }: DecisionClockProps) {
  const readRegistry = useRegistry({ enabled: givenRegistry === undefined });
  const readReviewer = useReviewer({ enabled: givenMe === undefined });
  const registry = givenRegistry ?? readRegistry.data;
  const me = givenMe ?? readReviewer.data?.subject;
  const now = useNow(1000, fixedNow);
  const clock = decisionClock(event, now, me);
  const people = deciders(event, registry, me, words);
  const nameOf = (s: string) => registry?.overseers.find((o) => o.subject === s)?.name ?? s;
  const { minimum } = clock;
  const reviewed = minimum.seconds === 0 ? 1 : minimum.started ? minimum.elapsed / minimum.seconds : 0;
  const roles = event.routing.roles.map((x) => readable(x).toLowerCase()).join(" or ");
  const barred = (event.routing.independentOf ?? []).map(nameOf);
  const needs = event.routing.required <= 1 ? "1 decision" : `${event.routing.required} decisions`;
  const due = event.sla.dueAt;

  return (
    <div className="hk hk-dclock" data-testid="decision-clock" data-tone={clock.tone}>
      <div className="hk-dclock__ring">
        <svg viewBox="0 0 220 220" aria-hidden="true">
          <circle className="hk-dclock__track" cx="110" cy="110" r={R} fill="none" strokeWidth="11" />
          <circle
            className={`hk-dclock__deadline hk-dclock__deadline--${clock.tone}`}
            cx="110"
            cy="110"
            r={R}
            fill="none"
            strokeWidth="11"
            strokeLinecap="round"
            strokeDasharray={`${CR * clock.remaining} ${CR}`}
          />
          <circle className="hk-dclock__track hk-dclock__track--inner" cx="110" cy="110" r={r} fill="none" strokeWidth="6" />
          <circle
            className={cx("hk-dclock__review", minimum.met && minimum.started && "hk-dclock__review--met")}
            cx="110"
            cy="110"
            r={r}
            fill="none"
            strokeWidth="6"
            strokeLinecap="round"
            strokeDasharray={`${cr * reviewed} ${cr}`}
          />
        </svg>
        <div className="hk-dclock__centre">
          {event.pending ? (
            <>
              <span className="hk-dclock__small">{clock.left !== undefined && clock.left < 0 ? "past the deadline" : "left to decide"}</span>
              <span className="hk-dclock__count" data-testid="clock-left">
                {clock.left === undefined ? "—" : formatCountdown(Math.abs(clock.left))}
              </span>
              <span className="hk-dclock__small">{afterDeadline(event)}</span>
            </>
          ) : (
            <>
              <span className="hk-dclock__small">decided</span>
              <span className="hk-dclock__count hk-dclock__count--word">{outcomeOf(event).word}</span>
              <span className="hk-dclock__small">{event.closedAt ? formatMoment(event.closedAt) : ""}</span>
            </>
          )}
          <span className="hk-dclock__small hk-dclock__minimum" data-testid="clock-minimum">
            {minimum.seconds === 0 ? (
              "no minimum review time"
            ) : minimum.started && minimum.met ? (
              <span className="hk-dclock__met">minimum review time met</span>
            ) : minimum.started ? (
              `looked at for ${minimum.elapsed} s of ${minimum.seconds} s`
            ) : (
              `${minimum.seconds} s of review, from your claim`
            )}
          </span>
        </div>
      </div>

      <div className="hk-dclock__people">
        <p className="hk-dclock__sum" data-testid="clock-summary">
          Needs {needs} from {/^[aeiou]/.test(roles) ? "an" : "a"} {roles}
          {barred.length ? ` other than ${barred.join(" and ")}` : ""}.
          {event.pending && due ? ` Due ${formatMoment(due)}, ${afterDeadline(event)}.` : ""}
        </p>
        <ul className="hk-dclock__list" aria-label="Who may decide">
          {people.map((p) => (
            <li
              key={p.subject}
              className={cx("hk-person-row", !p.may && "hk-person-row--no", p.me && "hk-person-row--me")}
              data-testid="decider"
              data-may={p.may}
              data-barred={p.barred}
            >
              <span className="hk-person-row__avatar" aria-hidden="true">
                {p.initials}
              </span>
              <div className="hk-person-row__who">
                <div className="hk-person-row__name">
                  {p.name}
                  {p.me ? <span className="hk-muted"> (you)</span> : null}
                </div>
                <div className="hk-person-row__note">{p.note}</div>
              </div>
              <span className="hk-person-row__mark">
                {p.may ? (
                  <Chip tone="success">May decide</Chip>
                ) : (
                  <Chip tone={BARRED_TONE[p.barred ?? ""] ?? "default"} icon={p.barred !== "voted"}>
                    {barredWord(p, words)}
                  </Chip>
                )}
              </span>
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
}
