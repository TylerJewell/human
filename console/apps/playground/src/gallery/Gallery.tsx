import {
  EventPage,
  HaltsPanel,
  OversightProvider,
  OverseersList,
  patternName,
  QualityView,
  Queue,
  StandingList,
  subjectKindLabel,
  SummaryTiles,
  UnreadableEvent,
  useReviewer,
  useSetReviewer,
  type Halt,
  type HumanEvent,
  type OverseerRegistry,
  type Quality,
  type Reviewer,
  type StandingDecision,
  type Summary
} from "@akka-human/kit";
import { useMemo, useState } from "react";
import { EVENT_EXAMPLES, example, exampleEvent, shiftTimes } from "../examples";
import { createMemoryClient, type MemoryData } from "./memory";

// Every spec example drawn offline, each event on its own page with a
// decision panel that works against an in-memory service, so each subject view
// and decision panel can be reviewed, and each write and refusal tried,
// without a server. The examples' times are moved so they read as raised a
// moment ago; the clock control moves them on to show at-risk and overdue.

/** The examples were raised at this time; every timestamp moves with the clock control. */
const RAISED = Date.parse("2026-10-29T14:00:00Z");

const CLOCKS = [
  { id: "fresh", label: "Raised 8 minutes ago", minutes: 8 },
  { id: "at-risk", label: "Raised 25 minutes ago (at risk)", minutes: 25 },
  { id: "overdue", label: "Raised 40 minutes ago (overdue)", minutes: 40 }
] as const;

type Clock = (typeof CLOCKS)[number]["id"];

/** Every role an example event is routed to. */
const EXAMPLE_ROLES = [...new Set(EVENT_EXAMPLES.flatMap((n) => exampleEvent(n).routing.roles))];

/**
 * The registry example, with Tyler holding every role the examples are routed
 * to and a second qualified overseer who does too, so each example can be
 * decided and two-person votes tried.
 */
function galleryRegistry(): OverseerRegistry {
  const registry = example<OverseerRegistry>("OverseerRegistry.basic");
  registry.overseers[0].roles = [...new Set([...registry.overseers[0].roles, ...EXAMPLE_ROLES])];
  registry.overseers.push({
    subject: "s.patel@example.com",
    name: "S. Patel",
    roles: EXAMPLE_ROLES,
    groups: [],
    reportsTo: null,
    authority: [],
    attestations: [{ id: "eu-ai-act-oversight", completedAt: "2026-04-20", expiresAt: "2027-04-20", expired: false }],
    qualified: true,
    qualifiedReason: null
  });
  return registry;
}

/** Events beyond the examples that show how the kit falls back: an unknown kind and an unknown pattern. */
function edgeEvents(): HumanEvent[] {
  const base = exampleEvent("HumanEvent.action");
  return [
    {
      ...base,
      id: "he-edge-unknown-kind",
      subject: { ...base.subject, kind: "contract" as never, summary: "Clause 7 of the supplier contract changed", detail: { clause: 7, before: "30 days", after: "60 days" } },
      ask: { question: "Should the new payment terms be accepted?", checks: [] }
    },
    {
      ...base,
      id: "he-edge-unknown-pattern",
      pattern: "peer-review" as never,
      patternName: "Peer review",
      allowedActions: ["APPROVE", "PARK" as never],
      actionLabels: { APPROVE: "Accept", PARK: "Park for later" },
      ask: { question: "A pattern this console has not met: does the panel still offer its answers?", checks: [] }
    }
  ];
}

function dataAt(clock: Clock): MemoryData {
  const minutes = CLOCKS.find((c) => c.id === clock)!.minutes;
  const shift = Date.now() - minutes * 60_000 - RAISED;
  const events = [...EVENT_EXAMPLES.map(exampleEvent), ...edgeEvents()].map((e) => shiftTimes(e, shift));
  return {
    events,
    registry: galleryRegistry(),
    halts: [shiftTimes(example<Halt>("Halt.halted"), shift)],
    standing: [shiftTimes(example<StandingDecision>("StandingDecision.pattern"), shift)],
    quality: example<Quality>("Quality.basic"),
    summary: shiftTimes(example<Summary>("Summary.basic"), shift)
  };
}

const VIEWS = [
  { id: "queue", label: "Queue" },
  { id: "summary", label: "Summary tiles" },
  { id: "halts", label: "Halts" },
  { id: "standing", label: "Standing decisions" },
  { id: "quality", label: "Quality" },
  { id: "overseers", label: "Overseers" }
] as const;

const EDGES = [
  { id: "he-edge-unknown-kind", label: "Unknown subject kind" },
  { id: "he-edge-unknown-pattern", label: "Unknown pattern" },
  { id: "unreadable", label: "Unreadable event" }
] as const;

export function Gallery({ route, go }: { route: string[]; go: (path: string) => void }) {
  const [clock, setClock] = useState<Clock>("fresh");
  const [round, setRound] = useState(0);
  const data = useMemo(() => dataAt(clock), [clock, round]);
  const tyler = data.registry.overseers[0];
  const client = useMemo(
    () => createMemoryClient(data, { subject: tyler.subject, name: tyler.name, roles: tyler.roles }),
    [data, tyler]
  );
  const page = route[0] ?? EVENT_EXAMPLES[0];
  const selected = data.events.find((e) => e.id === page || `HumanEvent.${e.id}` === page);
  const byName = (name: string) => data.events[EVENT_EXAMPLES.indexOf(name)];

  return (
    <OversightProvider client={client} pollMs={0}>
      <div className="pg-gallery">
        <nav className="pg-side hk" aria-label="Examples">
          <h2 className="hk-band">Spec examples</h2>
          <ul>
            {EVENT_EXAMPLES.map((name) => {
              const e = byName(name);
              return (
                <li key={name}>
                  <a href={`#/gallery/${name}`} aria-current={page === name ? "page" : undefined}>
                    <span>{name.replace("HumanEvent.", "")}</span>
                    <span className="hk-muted">
                      {patternName(e.pattern, e.patternName)} · {subjectKindLabel(e.subject.kind)}
                    </span>
                  </a>
                </li>
              );
            })}
          </ul>
          <h2 className="hk-band">Fallbacks</h2>
          <ul>
            {EDGES.map((v) => (
              <li key={v.id}>
                <a href={`#/gallery/${v.id}`} aria-current={page === v.id ? "page" : undefined}>
                  {v.label}
                </a>
              </li>
            ))}
          </ul>
          <h2 className="hk-band">Views</h2>
          <ul>
            {VIEWS.map((v) => (
              <li key={v.id}>
                <a href={`#/gallery/${v.id}`} aria-current={page === v.id ? "page" : undefined}>
                  {v.label}
                </a>
              </li>
            ))}
          </ul>
        </nav>

        <div className="pg-stage">
          <div className="pg-toolbar hk">
            <GalleryReviewer />
            <label className="pg-inline">
              <span className="hk-band">Clock</span>
              <select className="hk-input hk-input--sm" value={clock} onChange={(e) => setClock(e.target.value as Clock)}>
                {CLOCKS.map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.label}
                  </option>
                ))}
              </select>
            </label>
            <button type="button" className="hk-btn hk-btn--ghost hk-btn--sm" onClick={() => setRound(round + 1)}>
              Reset the examples
            </button>
          </div>
          {page === "unreadable" ? (
            <UnreadableEvent
              summary="Payment to S-77, from a service on a newer contract"
              issues={["ask: Invalid key: Expected \"ask\" but received undefined", "subject.kind: Invalid type: Expected string but received 7"]}
              raw={{ id: "he-future-1", subject: { kind: 7, summary: "Payment to S-77, from a service on a newer contract" }, question: "Pay?" }}
            />
          ) : page === "queue" ? (
            <Queue onOpen={(row) => go(`/gallery/${EVENT_EXAMPLES.find((n) => byName(n)?.id === row.id) ?? row.id}`)} />
          ) : page === "summary" ? (
            <SummaryTiles summary={data.summary} />
          ) : page === "halts" ? (
            <HaltsPanel />
          ) : page === "standing" ? (
            <StandingList />
          ) : page === "quality" ? (
            <QualityView quality={data.quality} />
          ) : page === "overseers" ? (
            <OverseersList registry={data.registry} />
          ) : (
            <EventPage key={`${page}-${round}-${clock}`} eventId={(EVENT_EXAMPLES.includes(page) ? byName(page) : selected)?.id ?? page} />
          )}
        </div>
      </div>
    </OversightProvider>
  );
}

/** Who acts in the gallery: any overseer of the gallery's registry, with every role they hold. */
function GalleryReviewer() {
  const current = useReviewer();
  const set = useSetReviewer();
  const people = galleryRegistry().overseers;
  return (
    <label className="pg-inline">
      <span className="hk-band">Acting as</span>
      <select
        className="hk-input hk-input--sm"
        value={current.data?.subject ?? ""}
        onChange={(e) => {
          const p = people.find((x) => x.subject === e.target.value);
          if (p) void set.run({ subject: p.subject, name: p.name, roles: p.roles } satisfies Reviewer);
        }}
      >
        {people.map((p) => (
          <option key={p.subject} value={p.subject}>
            {p.name ?? p.subject}
            {p.qualified ? "" : " (not qualified)"}
          </option>
        ))}
      </select>
    </label>
  );
}
