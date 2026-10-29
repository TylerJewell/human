import {
  ContractError,
  DecisionPanel,
  ErrorLine,
  History,
  OutcomeChip,
  patternName,
  Queue,
  readable,
  StateChip,
  SubjectView,
  subjectTitle,
  TriggerFacts,
  UnreadableEvent,
  useEvent,
  useNow,
  useRegistry,
  useReviewer,
  type HumanEvent
} from "@akka-human/kit";
import type { ReactNode } from "react";
import { recordOf, serviceNow } from "../model";
import { DecisionClockView } from "../parts/DecisionClock";
import { Head, Icon, Kv, Loading } from "../parts/ui";

// Decisions: everything of contextlayer's that waits for a person, and what
// they answered. One decision on its own page: the clock, the control and who
// asked, what it would send or change, why it was raised, the decision panel
// beside them, and the history beneath.

export function Decisions({ id, go }: { id?: string; go: (path: string) => void }) {
  if (id) return <DecisionPage key={id} id={id} />;
  return (
    <div className="cl-page">
      <Head
        title="Decisions"
        sub="A decision is something that cannot happen until a person says yes or no, with a reason. Whoever asked can never be the one who decides."
      />
      <div className="cl-card cl-card--flush">
        <Queue title="Decisions" scope={{ service: "contextlayer" }} onOpen={(row) => go(`/decisions/${encodeURIComponent(row.id)}`)} />
      </div>
    </div>
  );
}

/** The section heading for the subject: what an action would send, what a change would change. */
const whatTitle = (event: HumanEvent) =>
  event.subject.kind === "action" ? (event.pending ? "What it would send" : "What it sent") : subjectTitle(event);

function DecisionPage({ id }: { id: string }) {
  const read = useEvent(id);
  const registry = useRegistry().data;
  const me = useReviewer().data;
  const now = useNow(1000);
  const event = read.data;
  const back: [string, string] = ["Decisions", "/decisions"];

  if (!event) {
    return (
      <div className="cl-page">
        <Head back={back} title="Decision" />
        {read.error instanceof ContractError ? (
          <UnreadableEvent summary={read.error.summary} issues={read.error.issues} raw={read.error.raw} />
        ) : read.error ? (
          <div className="hk">
            <ErrorLine error={read.error} />
          </div>
        ) : (
          <Loading what="the decision" />
        )}
      </div>
    );
  }

  const nameOf = (s: string) => registry?.overseers.find((o) => o.subject === s)?.name ?? s;
  const record = recordOf(event);
  const rows: [string, ReactNode, ReactNode?][] = [
    [
      "Control",
      <>
        <span className="cl-strong">{event.control.name}</span>
        <span className="cl-tag cl-mono">{event.control.rowId}</span>
      </>,
      <span className="cl-chip cl-chip--line">
        {patternName(event.pattern, event.patternName)} · {event.mode}
      </span>
    ]
  ];
  if (event.proposedBy) rows.push(["Requested by", nameOf(event.proposedBy), event.subject.agent ? <span className="cl-muted cl-small">{readable(event.subject.agent)}</span> : undefined]);
  if (event.builtBy) rows.push(["Built by", nameOf(event.builtBy)]);
  if (record)
    rows.push([
      record.label,
      <a className="cl-lk" href={`#${record.path}`}>
        {event.subject.ref} <Icon name="arrowR" size={13} />
      </a>
    ]);

  return (
    <div className="cl-page">
      <Head
        back={back}
        title={event.ask.question}
        chips={event.pending ? <StateChip state={event.state} /> : <OutcomeChip row={event} />}
        sub={event.subject.summary !== event.ask.question ? event.subject.summary : undefined}
      />
      <div className="cl-decide">
        <div className="cl-decide__main">
          <div className="cl-card">
            <DecisionClockView event={event} registry={registry} me={me?.subject} now={now} />
          </div>
          <div className="cl-card">
            <Kv rows={rows} />
          </div>
          <h2>{whatTitle(event)}</h2>
          <div className="cl-card hk">
            <SubjectView event={event} now={serviceNow(event, now)} reviewer={me} />
          </div>
          <h2>Why this was raised</h2>
          <div className="cl-card hk">
            <TriggerFacts event={event} />
          </div>
        </div>
        <div className="cl-decide__aside">
          <DecisionPanel event={event} />
        </div>
      </div>
      <div className="cl-history hk">
        <History event={event} nameOf={nameOf} />
      </div>
    </div>
  );
}
