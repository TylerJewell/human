import { formatMoment, StateChip, OutcomeChip, type HumanEvent } from "@akka-human/kit";
import type { ReactNode } from "react";
import { Button, ErrorText, Icon, Mono } from "../parts/ui";
import type { StepId } from "../tour/model";

// Parts every scenario's steps share: the bar that starts a scenario, the line
// that says what was raised, and the line that says how the decision was
// applied.

/** The call a scenario makes, and the button that makes it. */
export function StartBar({
  call,
  label,
  onRun,
  busy,
  error,
  ran,
  children
}: {
  call: string;
  label: string;
  onRun: () => void;
  busy: boolean;
  error?: string;
  ran: boolean;
  children?: ReactNode;
}) {
  return (
    <div className="pt-start">
      <div className="pt-start__row">
        <Button icon={ran ? "reset" : "play"} variant={ran ? "secondary" : "primary"} onClick={onRun} busy={busy} testId="run">
          {ran ? "Run it again" : label}
        </Button>
        <span className="pt-start__call">
          The tour calls <Mono>{call}</Mono> on the patterns service.
        </span>
      </div>
      {children}
      <ErrorText>{error}</ErrorText>
    </div>
  );
}

/** What the scenario raised, and the way on to the next step. */
export function RaisedBar({ event, go, next = "decide", nextLabel }: { event?: HumanEvent; go: (s: StepId) => void; next?: StepId; nextLabel?: string }) {
  if (!event) return null;
  return (
    <div className="pt-raised" data-testid="raised">
      <span className="pt-raised__mark">
        <Icon name="check" size={14} />
      </span>
      <span>
        The service raised <Mono>{event.id}</Mono> under <Mono>{event.control.rowId}</Mono>.
      </span>
      {event.pending ? <StateChip state={event.state} /> : <OutcomeChip row={event} />}
      <span className="pt-raised__sp" />
      <Button variant="quiet" icon="arrowR" onClick={() => go(next)}>
        {nextLabel ?? (event.pending ? "See who decides" : "See what changed")}
      </Button>
    </div>
  );
}

/** How the library applied the outcome, from the event's history. */
export function AppliedLine({ event }: { event?: HumanEvent }) {
  if (!event) return null;
  const step = [...event.history].reverse().find((h) => h.state === "APPLIED");
  const applied = event.outcome?.applied ? { at: event.outcome.appliedAt ?? step?.at ?? "", note: event.outcome.applied } : step;
  if (event.pending) {
    return <p className="pt-applied pt-applied--wait">The event is still waiting for a decision.</p>;
  }
  if (!applied) {
    return (
      <p className="pt-applied pt-applied--wait">
        <span className="pt-spin" aria-hidden="true" /> The library is applying the decision.
      </p>
    );
  }
  return (
    <p className="pt-applied" data-testid="applied">
      <Icon name="check" size={14} />
      <span>
        The library applied the decision{applied.at ? ` at ${formatMoment(applied.at)}` : ""}: <Mono>{applied.note}</Mono>
      </span>
    </p>
  );
}

export function NextBar({ go, to, label }: { go: (s: StepId) => void; to: StepId; label: string }) {
  return (
    <div className="pt-next">
      <Button variant="quiet" icon="arrowR" onClick={() => go(to)}>
        {label}
      </Button>
    </div>
  );
}

export function Waiting({ children }: { children: ReactNode }) {
  return (
    <div className="pt-waiting" aria-busy="true">
      <span className="pt-spin" aria-hidden="true" />
      {children}
    </div>
  );
}

/** What a step shows before the scenario has reached it. */
export function NotYet({ go, children }: { go: (s: StepId) => void; children: ReactNode }) {
  return (
    <div className="pt-notyet">
      <p>{children}</p>
      <Button variant="secondary" icon="play" onClick={() => go("trigger")}>
        Start the scenario
      </Button>
    </div>
  );
}
