import {
  actionLabel,
  ConclusionChip,
  formatMoment,
  HaltsPanel,
  History,
  readable,
  SummaryTiles,
  useEvent,
  useRegistry,
  type HumanEvent,
  type Verdict
} from "@akka-human/kit";
import { nameIn } from "../parts/people";
import { Eyebrow, Icon, Mono, Rows } from "../parts/ui";
import { useProof, useVerdict, type ProofRecord } from "../tour/api";
import type { PatternTour } from "../tour/model";
import { Waiting } from "./common";

// What a manager sees once a scenario has run: the verdict on each event, the
// proof records the ledger kept, the event's history, and the control's
// figures. The proof records' full JSON is in the code panel beside it.

export function RecordStep({ tour, events }: { tour: PatternTour; events: string[] }) {
  const proof = useProof(events).data ?? [];
  return (
    <div className="pt-record" data-testid="record">
      <div className="pt-record__verdicts">
        {events.map((id) => (
          <VerdictCard key={id} id={id} />
        ))}
      </div>
      <div className="pt-card">
        <div className="pt-card__head">
          <Eyebrow>Proof ledger</Eyebrow>
          <span className="pt-muted">{proof.length === 1 ? "1 record" : `${proof.length} records`}</span>
        </div>
        {proof.length ? <ProofList records={proof} /> : <Waiting>The ledger has no record for this run yet.</Waiting>}
      </div>
      {tour.hook === "halt" ? (
        <div className="pt-card hk">
          <Eyebrow>Halts</Eyebrow>
          <HaltsPanel />
        </div>
      ) : null}
      <div className="pt-card hk">
        <Eyebrow>Figures for {tour.control}</Eyebrow>
        <SummaryTiles control={tour.control} />
      </div>
      {events.map((id) => (
        <EventHistory key={id} id={id} />
      ))}
    </div>
  );
}

function VerdictCard({ id }: { id: string }) {
  const event = useEvent(id, { pollMs: 2000 }).data;
  const read = useVerdict(event && !event.pending ? id : undefined).data;
  const verdict: Verdict | null | undefined = event?.verdict ?? read;
  const registry = useRegistry().data;
  if (!event) return <Waiting>Reading {id}.</Waiting>;
  if (event.pending) {
    return (
      <div className="pt-card pt-verdict">
        <Eyebrow>Verdict</Eyebrow>
        <p className="pt-muted">The verdict is reached when the event closes. {event.id} is still waiting for a decision.</p>
      </div>
    );
  }
  if (!verdict) return <Waiting>The verdict on {id} is being reached.</Waiting>;
  return (
    <div className="pt-card pt-verdict" data-testid="verdict">
      <div className="pt-card__head">
        <Eyebrow>Verdict on {event.control.rowId}</Eyebrow>
        <ConclusionChip conclusion={verdict.conclusion} />
      </div>
      <p className="pt-verdict__line">{verdictSentence(event, verdict, (s) => nameIn(registry, s))}</p>
      <Rows
        rows={[
          ["Event", <Mono>{event.id}</Mono>],
          ["Action", verdict.action ? actionLabel(verdict.action, event.actionLabels) : "None"],
          ["Deciders", verdict.deciders?.length ? verdict.deciders.map((s) => nameIn(registry, s)).join(", ") : "Nobody"],
          ["Source", <SourceWords kind={verdict.source.kind} />],
          ["Applied", verdict.appliedAt ? formatMoment(verdict.appliedAt) : "Not applied"],
          ...(verdict.reasons?.length ? [["Reasons", verdict.reasons.map(readable).join(", ")] as [string, string]] : [])
        ]}
      />
    </div>
  );
}

function verdictSentence(event: HumanEvent, verdict: Verdict, nameOf: (s: string) => string) {
  const who = verdict.deciders?.length ? verdict.deciders.map(nameOf).join(" and ") : "Nobody";
  const action = verdict.action ? actionLabel(verdict.action, event.actionLabels).toLowerCase() : "no action";
  switch (verdict.conclusion) {
    case "upheld":
      return `The control held: ${who} decided "${action}" as the control requires.`;
    case "breached":
      return `The control was breached: ${verdict.reasons?.map(readable).join(", ").toLowerCase() || "see the reasons below"}.`;
    case "inconclusive":
      return "The verdict is inconclusive: the record does not show whether the control held.";
    default:
      return "The decision could not be applied, so the verdict records an error.";
  }
}

function SourceWords({ kind }: { kind: string }) {
  if (kind === "rehearsal") return <span>Rehearsal: the decision took effect, and it does not count as evidence</span>;
  if (kind === "simulated") return <span>Simulated</span>;
  return <span>Runtime</span>;
}

const RECORD_WORDS: Record<string, string> = {
  HumanOversightDecisionRecordedV2: "A person's decision",
  HaltExercised: "A halt was exercised",
  HaltLifted: "A halt was lifted"
};

function ProofList({ records }: { records: ProofRecord[] }) {
  return (
    <ol className="pt-proof">
      {records.map((r) => (
        <li key={r.recordId}>
          <span className="pt-proof__icon">
            <Icon name="ledger" size={15} />
          </span>
          <span className="pt-proof__what">
            <span className="pt-mono">{r.type}</span>
            <span className="pt-muted">{RECORD_WORDS[r.type] ?? "A record"}</span>
          </span>
          <span className="pt-proof__fields">
            {["action", "reviewer_id", "verdict", "review_duration_ms"]
              .filter((k) => r.payload[k] !== undefined && r.payload[k] !== null)
              .map((k) => (
                <span key={k} className="pt-proof__field">
                  <span className="pt-muted">{k}</span> <span className="pt-mono">{String(r.payload[k])}</span>
                </span>
              ))}
          </span>
          <span className="pt-muted pt-proof__at">{formatMoment(r.recordedAt)}</span>
        </li>
      ))}
    </ol>
  );
}

function EventHistory({ id }: { id: string }) {
  const event = useEvent(id).data;
  const registry = useRegistry().data;
  if (!event) return null;
  return (
    <div className="pt-card hk">
      <History event={event} nameOf={(s) => nameIn(registry, s)} />
    </div>
  );
}
