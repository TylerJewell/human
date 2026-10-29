import { useState } from "react";
import type { EventWrite } from "../client";
import { ContractError } from "../parse";
import { useEvent, useNow, useRegistry, useReviewer } from "../hooks";
import type { HumanEvent, OverseerRegistry, Reviewer } from "../types";
import {
  actionLabel,
  actorText,
  detectorPhrase,
  flavorLabel,
  formatDuration,
  formatMoment,
  modeLabel,
  patternName,
  readable,
  serviceNow,
  timeoutSentence,
  valueText,
  type NameOf
} from "../words";
import { SubjectView, subjectTitle } from "../subjects/SubjectView";
import { DecisionPanel, votesText } from "./DecisionPanel";
import { History, JourneyLine } from "./History";
import { DeadlineRing, StateChip, OutcomeChip } from "./status";
import { Chip, ErrorLine, Facts, Prose, RawJson, Section, Tag, type Fact } from "./ui";

// One event on its own page, the same for every pattern. The header says what
// it is (pattern, control, where it ran, how it stands) with the deadline
// ring, then the question in one sentence. Below, the subject drawn by its
// kind and why it was raised, beside the decision panel chosen by the
// pattern's actions; across the foot, the history and the proof.

/** Why the event was raised: its reason, the rule and what detected it, the values that fired it. */
export function TriggerFacts({ event }: { event: HumanEvent }) {
  const { trigger } = event;
  const facts: Fact[] = [];
  if (trigger.reason) facts.push({ label: "Reason", value: trigger.reason });
  if (trigger.rule) facts.push({ label: "Rule", value: <code className="hk-code">{trigger.rule}</code> });
  facts.push({ label: "Detected by", value: detectorPhrase(trigger.detector) });
  if (trigger.detectedAt) facts.push({ label: "Detected", value: formatMoment(trigger.detectedAt) });
  if (trigger.occurrences && trigger.occurrences > 1) facts.push({ label: "Seen", value: `${trigger.occurrences} times` });
  for (const value of trigger.values ?? [])
    facts.push({
      label: value.name,
      value:
        value.limit != null
          ? `${valueText(value.value, value.name)} against a limit of ${valueText(value.limit, value.name)}`
          : valueText(value.value, value.name)
    });
  for (const c of trigger.conditions ?? [])
    facts.push({ label: c.name, value: <Chip tone={c.met ? "warning" : "default"} icon={false}>{c.met ? "Met" : "Not met"}</Chip> });
  return <Facts facts={facts} testId="trigger-facts" />;
}

/** The control the event is under: who it is routed to, its deadline, and how it is reviewed. */
function ControlFacts({ event, nameOf }: { event: HumanEvent; nameOf: NameOf }) {
  const { control, routing, sla } = event;
  const facts: Fact[] = [{ label: "Control", value: control.name || control.rowId }];
  if (control.corpusRef) facts.push({ label: "Implements", value: <code className="hk-code">{control.corpusRef}</code> });
  facts.push({
    label: "Routed to",
    value: `${readable(routing.queue)}${routing.roles.length ? ` (${routing.roles.map(readable).join(", ")})` : ""}`
  });
  facts.push({
    label: "Review",
    value: routing.required <= 1 ? "One reviewer" : `${routing.required} reviewers${routing.of ? ` of ${routing.of}` : ""}, independent of each other`
  });
  if (sla.target)
    facts.push({ label: "Deadline", value: `${formatDuration(sla.target)}, then ${timeoutSentence(sla.timeoutBehaviour, sla.default)}` });
  if (event.escalation?.chain.length) facts.push({ label: "Escalation", value: event.escalation.chain.map(readable).join(" → ") });
  if (event.accountableOwner) facts.push({ label: "Accountable owner", value: actorText(event.accountableOwner, nameOf) });
  if (event.minimumReviewSeconds)
    facts.push({
      label: "Minimum review",
      value: `${formatDuration(`PT${event.minimumReviewSeconds}S`)}${event.minimumReview === "WARN" ? ", warned" : ", enforced"}`
    });
  if (routing.independentOf?.length)
    facts.push({ label: "May not decide", value: routing.independentOf.map((s) => actorText(s, nameOf)).join(", ") });
  return <Facts facts={facts} testId="control-facts" />;
}

/** An event that cannot be read: its summary, what does not match, and the raw JSON. */
export function UnreadableEvent({ summary, issues, raw }: { summary: string; issues: string[]; raw: unknown }) {
  return (
    <article className="hk hk-event" data-testid="event-unreadable">
      <Section title="An event this console cannot read">
        <div className="hk-stack">
          <Prose heading="Summary">{summary}</Prose>
          {issues.length ? (
            <ul className="hk-issues">
              {issues.slice(0, 8).map((i) => (
                <li key={i}>{i}</li>
              ))}
            </ul>
          ) : null}
          <details className="hk-fold" open>
            <summary>Raw event</summary>
            <RawJson value={raw} label="Raw event" />
          </details>
        </div>
      </Section>
    </article>
  );
}

function Loading() {
  return (
    <div className="hk hk-event" aria-busy="true" data-testid="event-loading">
      <div className="hk-skeleton hk-skeleton--header" />
      <div className="hk-event__body">
        <div className="hk-skeleton hk-skeleton--block" />
        <div className="hk-skeleton hk-skeleton--block" />
      </div>
    </div>
  );
}

export interface EventPageProps {
  /** The event to read and keep reading. */
  eventId?: string;
  /** An event already in hand: shown at once, then kept current when a client is given. */
  event?: HumanEvent;
  reviewer?: Reviewer | null;
  registry?: OverseerRegistry;
  /** A fixed time, for tests and pictures. */
  now?: number;
  /** Called with the event as it stands after each write from its decision panel. */
  onWrite?: (event: HumanEvent, write: EventWrite) => void;
}

/** One event's page: header and question, subject and decision, history and proof. */
export function EventPage({ eventId, event: given, reviewer, registry, now: fixedNow, onWrite }: EventPageProps) {
  const read = useEvent(eventId ?? given?.id, { initialData: given });
  const readRegistry = useRegistry({ enabled: registry === undefined });
  const readReviewer = useReviewer({ enabled: reviewer === undefined });
  const browserNow = useNow(1000, fixedNow);
  const [requested, setRequested] = useState<{ action: string; at: number }>();
  const people = registry ?? readRegistry.data;
  const me = reviewer !== undefined ? reviewer : readReviewer.data;
  const nameOf: NameOf = (subject) => people?.overseers.find((o) => o.subject === subject)?.name ?? subject;

  const event = read.data;
  if (!event) {
    if (read.error instanceof ContractError)
      return <UnreadableEvent summary={read.error.summary} issues={read.error.issues} raw={read.error.raw} />;
    if (read.error)
      return (
        <div className="hk hk-event" data-testid="event-failed">
          <ErrorLine error={read.error} />
        </div>
      );
    return <Loading />;
  }

  // The event's times are the service's, so the page reads them against its clock.
  const now = serviceNow(event, browserNow);
  const { subject } = event;
  const where = [subject.service, subject.agent].filter(Boolean).join(" › ");
  const raisedBy = event.history[0]?.actor;
  const facts: Fact[] = [
    { label: "Loop", value: `${modeLabel(event.mode)} · ${flavorLabel(event.flavor)}` },
    { label: "Queue", value: readable(event.routing.queue) },
    { label: "Reviewers", value: votesText(event) },
    {
      label: "Raised",
      value: `${event.trigger.detectedAt ? formatMoment(event.trigger.detectedAt) : "—"}, by ${
        raisedBy && raisedBy !== "system" ? actorText(raisedBy, nameOf) : detectorPhrase(event.trigger.detector)
      }`
    },
    { label: "Blocking", value: event.blocking ? "Yes: the work waits for the decision" : "No: the work carries on" }
  ];
  if (event.holder) facts.push({ label: "Held by", value: `${nameOf(event.holder.subject)} since ${formatMoment(event.holder.since)}` });

  return (
    <article className="hk hk-event" aria-labelledby={`hk-q-${event.id}`} data-testid="event-page" data-pattern={event.pattern}>
      <header className="hk-event__header" data-testid="event-header">
        <div className="hk-event__heading">
          <p className="hk-eyebrow" data-testid="event-eyebrow">
            <Tag title={event.pattern}>{patternName(event.pattern, event.patternName)}</Tag>
            <span className="hk-eyebrow__sep" aria-hidden="true">·</span>
            <span title={event.control.rowId}>{event.control.name || event.control.rowId}</span>
            {where ? (
              <>
                <span className="hk-eyebrow__sep" aria-hidden="true">·</span>
                <span data-testid="event-where">{where}</span>
              </>
            ) : null}
            <span className="hk-eyebrow__sep" aria-hidden="true">·</span>
            {event.pending ? <StateChip state={event.state} /> : <OutcomeChip row={event} />}
            {event.shadow ? <Chip tone="quiet" icon={false}>Shadow</Chip> : null}
            {event.simulated ? <Chip tone="quiet" icon={false}>Simulated</Chip> : null}
          </p>
          <h1 id={`hk-q-${event.id}`} className="hk-question" data-testid="event-question">
            {event.ask.question}
          </h1>
          {subject.summary && subject.summary !== event.ask.question ? <p className="hk-event__summary">{subject.summary}</p> : null}
        </div>
        <div className="hk-event__deadline">
          <DeadlineRing sla={event.sla} detectedAt={event.trigger.detectedAt} now={now} kind={subject.kind} size="lg" />
        </div>
        <dl className="hk-event__facts">
          {facts.map((f) => (
            <div key={String(f.label)} className="hk-event__fact">
              <dt>{f.label}</dt>
              <dd>{f.value}</dd>
            </div>
          ))}
        </dl>
        <JourneyLine event={event} nameOf={nameOf} />
      </header>

      <div className="hk-event__body">
        <div className="hk-event__case">
          <Section title={subjectTitle(event)} testId="event-subject">
            <SubjectView event={event} now={now} reviewer={me} onAction={(action) => setRequested({ action, at: Date.now() })} />
          </Section>
          <Section title="Why this was raised" testId="event-trigger">
            <TriggerFacts event={event} />
          </Section>
          {event.aiRecommendation?.action ? (
            <Section title="The AI's recommendation" testId="event-ai">
              <div className="hk-stack">
                <Facts
                  facts={[
                    ...(event.aiRecommendation.agent ? [{ label: "From", value: readable(event.aiRecommendation.agent) }] : []),
                    { label: "Recommends", value: actionLabel(event.aiRecommendation.action, event.actionLabels) },
                    ...(event.aiRecommendation.score != null
                      ? [{ label: "Confidence", value: `${Math.round(event.aiRecommendation.score * 100)}%` }]
                      : [])
                  ]}
                />
                {event.aiRecommendation.rationale ? <Prose heading="Its reasoning">{event.aiRecommendation.rationale}</Prose> : null}
              </div>
            </Section>
          ) : null}
          {event.context?.intendedUse || event.context?.limitations?.length ? (
            <Section title="The system" testId="event-context">
              <div className="hk-stack">
                {event.context.intendedUse ? <Prose heading="Intended use">{event.context.intendedUse}</Prose> : null}
                {event.context.limitations?.length ? (
                  <Prose heading="Limitations">
                    <ul className="hk-bullets">
                      {event.context.limitations.map((l) => (
                        <li key={l}>{l}</li>
                      ))}
                    </ul>
                  </Prose>
                ) : null}
              </div>
            </Section>
          ) : null}
          <Section title="The control" testId="event-control">
            <ControlFacts event={event} nameOf={nameOf} />
          </Section>
        </div>
        <div className="hk-event__aside">
          <DecisionPanel
            event={event}
            reviewer={me}
            registry={people}
            now={fixedNow}
            requested={requested}
            onWrite={onWrite}
          />
        </div>
      </div>

      <History event={event} nameOf={nameOf} />
    </article>
  );
}
