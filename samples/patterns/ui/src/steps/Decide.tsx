import {
  DecisionClock,
  DecisionPanel,
  deciders,
  OutcomeChip,
  serviceNow,
  StateChip,
  SubjectView,
  subjectTitle,
  TriggerFacts,
  useNow,
  useRegistry,
  type DeciderWords,
  type HumanEvent
} from "@akka-human/kit";
import { useEffect, useRef, useState } from "react";
import { initials, nameIn, rolesOf, useActAs, useMe } from "../parts/people";
import { Button, Eyebrow, Mono } from "../parts/ui";
import type { StepId } from "../tour/model";
import { NextBar } from "./common";

// The overseer's view of one event: the kit's decision page parts, with the
// person the tour acts as chosen from the people who may decide now. When the
// person acting may not decide (they voted, or they proposed it), the tour
// switches to the next eligible person and says so.

/** What the subject card is called, by subject kind. */
const SUBJECT_TITLE: Record<string, string> = {
  action: "The action",
  output: "The output",
  change: "The change",
  exception: "The exception",
  records: "The records",
  appeal: "The appeal",
  conversation: "The conversation",
  behaviour: "The signal",
  system: "The capability",
  attestation: "The attestation"
};

export function DecideStep({ event, go, words }: { event: HumanEvent; go: (s: StepId) => void; words?: DeciderWords }) {
  const registry = useRegistry().data;
  const me = useMe();
  const now = useNow(1000);
  return (
    <div className="pt-decide" data-testid="decide">
      <div className="pt-decide__main">
        <ActingAs event={event} words={words} go={go} />
        <div className="pt-card">
          <div className="pt-card__head">
            <Eyebrow>{SUBJECT_TITLE[event.subject.kind] ?? subjectTitle(event)}</Eyebrow>
            {event.pending ? <StateChip state={event.state} /> : <OutcomeChip row={event} />}
          </div>
          <h3 className="pt-question">{event.ask.question}</h3>
          <div className="hk">
            <SubjectView event={event} now={serviceNow(event, now)} reviewer={me} />
          </div>
        </div>
        <div className="pt-card">
          <Eyebrow>Why this was raised</Eyebrow>
          <div className="hk">
            <TriggerFacts event={event} />
          </div>
        </div>
      </div>
      <div className="pt-decide__aside">
        <div className="pt-card">
          <DecisionClock event={event} registry={registry} me={me?.subject} words={words} />
        </div>
        <DecisionPanel event={event} />
        {!event.pending ? <NextBar go={go} to="effect" label="See what changed" /> : null}
      </div>
    </div>
  );
}

/** Who the tour acts as for this event, and the other people who may decide it. */
function ActingAs({ event, words, go }: { event: HumanEvent; words?: DeciderWords; go: (s: StepId) => void }) {
  const registry = useRegistry().data;
  const me = useMe();
  const actAs = useActAs();
  const [switched, setSwitched] = useState<string>();
  const list = registry ? deciders(event, registry, me?.subject, words) : [];
  const eligible = list.filter((d) => d.may);
  const eligibleKey = eligible.map((d) => d.subject).join(",");
  const meMay = eligible.some((d) => d.me);
  const switching = useRef(false);

  useEffect(() => {
    if (!event.pending || meMay || !eligible.length || switching.current) return;
    switching.current = true;
    const next = eligible[0].subject;
    actAs(next)
      .then(() => setSwitched(next))
      .finally(() => {
        switching.current = false;
      });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [event.id, event.pending, eligibleKey, meMay]);

  if (!registry) return null;
  if (!event.pending) {
    return (
      <div className="pt-actas pt-actas--done">
        <span>
          Decided by {event.decisions.map((d) => nameIn(registry, d.reviewer.subject)).join(" and ") || "the system"}.
        </span>
        <Button variant="quiet" icon="arrowR" onClick={() => go("effect")}>
          See what changed
        </Button>
      </div>
    );
  }
  if (!eligible.length) {
    return (
      <div className="pt-actas">
        <span>Nobody in the registry may decide this event now.</span>
      </div>
    );
  }
  const others = eligible.filter((d) => !d.me);
  return (
    <div className="pt-actas" data-testid="acting-for-event">
      <span className="pt-avatar" aria-hidden="true">
        {initials(nameIn(registry, me?.subject))}
      </span>
      <div className="pt-actas__who">
        <span className="pt-actas__line">
          The tour acts as <b>{nameIn(registry, me?.subject)}</b>
          {switched === me?.subject ? ", who may decide now" : ""}.
        </span>
        <span className="pt-actas__roles">
          {rolesOf(registry, me?.subject)} · <Mono>{me?.subject}</Mono>
        </span>
      </div>
      {others.length ? (
        <div className="pt-actas__others">
          <span>Also eligible</span>
          {others.map((d) => (
            <button key={d.subject} type="button" className="pt-person" onClick={() => void actAs(d.subject)} title={d.note}>
              <span className="pt-avatar pt-avatar--sm" aria-hidden="true">
                {d.initials}
              </span>
              {d.name}
            </button>
          ))}
        </div>
      ) : null}
    </div>
  );
}
