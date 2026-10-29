import {
  createCompanionClient,
  createServiceClient,
  ErrorLine,
  EventPage,
  HaltsPanel,
  OversightProvider,
  OverseersList,
  patternName,
  QualityView,
  Queue,
  ReviewerPicker,
  StandingList,
  subjectKindLabel,
  SummaryTiles,
  useOversight,
  useRead,
  type CompanionClient,
  type CompanionService,
  type ControlSettings,
  type HumanEvent,
  type OversightClient,
  type Reviewer,
  type ServiceClient,
  type ServiceInfo
} from "@akka-human/kit";
import { useEffect, useMemo, useState } from "react";
import { store, stored } from "../route";
import { sampleFor } from "./samples";

// The live page: choose a companion (default) or a service, choose who to act
// as, raise a sample event for any control of a service with development
// routes, and work the queue and each event's page against what is running.

interface Target {
  mode: "companion" | "service";
  url: string;
}

const DEFAULT_TARGET: Target = { mode: "companion", url: "http://localhost:9167" };

/** The playground's dev server forwards localhost targets, so neither the companion nor a service needs CORS. */
export function proxied(url: string): string {
  try {
    const u = new URL(url);
    return /^(localhost|127\.0\.0\.1)$/.test(u.hostname) ? `/proxy/${u.host}` : url.replace(/\/+$/, "");
  } catch {
    return url;
  }
}

const TABS = [
  { id: "queue", label: "Queue" },
  { id: "halts", label: "Halts" },
  { id: "standing", label: "Standing decisions" },
  { id: "quality", label: "Quality" },
  { id: "overseers", label: "Overseers" }
] as const;

export function Live({ route, go }: { route: string[]; go: (path: string) => void }) {
  const [target, setTarget] = useState<Target>(() => stored("hk-playground-target", DEFAULT_TARGET));
  const [draft, setDraft] = useState(target);
  const saved = stored<Reviewer | null>("hk-playground-reviewer", null);
  const client = useMemo<OversightClient>(
    () =>
      target.mode === "companion"
        ? createCompanionClient({ baseUrl: proxied(target.url) })
        : createServiceClient({ baseUrl: proxied(target.url), reviewer: saved, identityProvider: "human-playground" }),
    // The saved reviewer is read once per target; a new choice reaches the client through setReviewer.
    [target]
  );
  const connect = () => {
    setTarget(draft);
    store("hk-playground-target", draft);
  };
  const [tab, id] = route[0] === "event" ? ["event", route[1]] : [route[0] ?? "queue", undefined];

  return (
    <OversightProvider client={client} pollMs={4000}>
      <div className="pg-live">
        <section className="pg-bar" aria-label="Target">
          <form
            className="pg-target"
            onSubmit={(e) => {
              e.preventDefault();
              connect();
            }}
          >
            <div className="hk-segmented hk-segmented--inline" role="radiogroup" aria-label="Talk to">
              {(["companion", "service"] as const).map((mode) => (
                <button
                  key={mode}
                  type="button"
                  role="radio"
                  aria-checked={draft.mode === mode}
                  className={`hk-segment${draft.mode === mode ? " hk-segment--on" : ""}`}
                  onClick={() =>
                    setDraft({ mode, url: draft.url === DEFAULT_TARGET.url || draft.url === "http://localhost:9164" ? (mode === "companion" ? "http://localhost:9167" : "http://localhost:9164") : draft.url })
                  }
                >
                  {mode === "companion" ? "Companion" : "Service"}
                </button>
              ))}
            </div>
            <label className="pg-target__url">
              <span className="hk-sr-only">Address</span>
              <input className="hk-input hk-input--sm" value={draft.url} onChange={(e) => setDraft({ ...draft, url: e.target.value })} />
            </label>
            <button type="submit" className="hk-btn hk-btn--secondary hk-btn--sm">
              Connect
            </button>
          </form>
          <Status />
          <ReviewerPicker
            className="pg-reviewer"
            onChosen={(reviewer) => {
              if (target.mode === "service") store("hk-playground-reviewer", reviewer);
            }}
          />
        </section>

        <Raise target={target} go={go} />

        <nav className="pg-tabs" aria-label="Views">
          {TABS.map((t) => (
            <a key={t.id} href={`#/live/${t.id}`} aria-current={tab === t.id ? "page" : undefined}>
              {t.label}
            </a>
          ))}
          {id ? (
            <a href={`#/live/event/${encodeURIComponent(id)}`} aria-current="page">
              {id}
            </a>
          ) : null}
        </nav>

        {tab === "event" && id ? (
          <div className="hk-stack">
            <a className="pg-back" href="#/live/queue">
              ← Back to the queue
            </a>
            <EventPage key={id} eventId={id} />
          </div>
        ) : tab === "halts" ? (
          <HaltsPanel onOpenEvent={(e) => go(`/live/event/${encodeURIComponent(e)}`)} />
        ) : tab === "standing" ? (
          <StandingList />
        ) : tab === "quality" ? (
          <QualityView />
        ) : tab === "overseers" ? (
          <OverseersList />
        ) : (
          <div className="hk-stack">
            <SummaryTiles />
            <Queue onOpen={(row) => go(`/live/event/${encodeURIComponent(row.id)}`)} />
          </div>
        )}
      </div>
    </OversightProvider>
  );
}

/** Whether the target answers, and what it says it is. */
function Status() {
  const oversight = useOversight();
  const read = useRead<ServiceInfo | CompanionService[]>(
    "status",
    (client) => (client.mode === "companion" ? (client as CompanionClient).services() : (client as ServiceClient).info()),
    { pollMs: 10_000 }
  );
  if (read.error) {
    return (
      <div className="pg-status pg-status--down" data-testid="target-status">
        <span className="pg-dot" aria-hidden="true" />
        <ErrorLine error={read.error} />
      </div>
    );
  }
  if (!read.data) return <div className="pg-status">Connecting…</div>;
  if (Array.isArray(read.data)) {
    const up = read.data.filter((s) => s.up);
    return (
      <div className="pg-status pg-status--up" data-testid="target-status">
        <span className="pg-dot" aria-hidden="true" />
        Companion: {up.length} of {read.data.length} services up
        {read.data.length ? <span className="hk-muted"> ({read.data.map((s) => `${s.service}${s.up ? "" : " down"}`).join(", ")})</span> : null}
      </div>
    );
  }
  return (
    <div className="pg-status pg-status--up" data-testid="target-status">
      <span className="pg-dot" aria-hidden="true" />
      {read.data.service} · contract {read.data.contract}
      {read.data.library ? ` · ${read.data.library}` : ""} · {read.data.patterns.length} patterns · development routes{" "}
      {read.data.devRoutes ? "on" : "off"}
      <span className="hk-sr-only">{oversight?.client.baseUrl}</span>
    </div>
  );
}

/** The service a raise goes to: the target itself, or a service the companion lists with development routes. */
function useRaiseServices(target: Target) {
  return useRead<{ name: string; url: string }[]>(
    `raise-services:${target.mode}:${target.url}`,
    async (client) => {
      if (client.mode === "service") {
        const info = await (client as ServiceClient).info();
        return info.devRoutes ? [{ name: info.service, url: target.url }] : [];
      }
      const services = await (client as CompanionClient).services();
      return services.filter((s) => s.up && s.info?.devRoutes).map((s) => ({ name: s.service, url: s.url }));
    },
    { pollMs: 0 }
  );
}

/** Raise a sample event for any control of a service with development routes. */
function Raise({ target, go }: { target: Target; go: (path: string) => void }) {
  const oversight = useOversight();
  const services = useRaiseServices(target);
  const [chosen, setChosen] = useState<string>();
  const [open, setOpen] = useState(true);
  const list = services.data ?? [];
  const service = list.find((s) => s.name === chosen) ?? list[0];
  const raiser = useMemo(() => (service ? createServiceClient({ baseUrl: proxied(service.url) }) : undefined), [service?.url]);
  const [controls, setControls] = useState<ControlSettings[]>();
  const [failed, setFailed] = useState<Error>();
  const [busy, setBusy] = useState<string>();
  const [raised, setRaised] = useState<HumanEvent>();
  useEffect(() => {
    setControls(undefined);
    setFailed(undefined);
    if (!raiser) return;
    raiser.controls().then(setControls, (e: Error) => setFailed(e));
  }, [raiser]);
  const raise = async (control: ControlSettings) => {
    if (!raiser || !service) return;
    setBusy(control.control.rowId);
    setFailed(undefined);
    try {
      const event = await raiser.dev.raise(sampleFor(control, service.name));
      setRaised(event);
      oversight?.cache.invalidate("events:", "summary:", "halts", "standing");
    } catch (e) {
      setFailed(e instanceof Error ? e : new Error(String(e)));
    } finally {
      setBusy(undefined);
    }
  };
  return (
    <details className="pg-raise hk" open={open} onToggle={(e) => setOpen((e.target as HTMLDetailsElement).open)} data-testid="raise-panel">
      <summary>
        <span className="hk-section__title">Raise</span>
        <span className="hk-muted">
          {service ? ` a sample event on ${service.name}, through POST /oversight/dev/events` : " needs a service with development routes"}
        </span>
      </summary>
      <div className="hk-panel hk-stack hk-stack--tight">
        {list.length > 1 ? (
          <label className="pg-inline">
            <span className="hk-band">Service</span>
            <select className="hk-input hk-input--sm" value={service?.name} onChange={(e) => setChosen(e.target.value)}>
              {list.map((s) => (
                <option key={s.name} value={s.name}>
                  {s.name}
                </option>
              ))}
            </select>
          </label>
        ) : null}
        <ErrorLine error={services.error ?? failed} />
        {raised ? (
          <p className="hk-said" role="status">
            Raised {raised.id}.{" "}
            <button type="button" className="hk-link" onClick={() => go(`/live/event/${encodeURIComponent(raised.id)}`)}>
              Open it
            </button>
          </p>
        ) : null}
        {service && !controls && !failed ? <p className="hk-muted">Reading the service's controls…</p> : null}
        {controls?.length === 0 ? <p className="hk-muted">The service declares no human control.</p> : null}
        {controls?.length ? (
          <ul className="pg-controls">
            {controls.map((c) => (
              <li key={c.control.rowId}>
                <span className="pg-controls__name">
                  {c.control.name}
                  <span className="hk-muted"> · {c.control.rowId}</span>
                </span>
                <span className="hk-tag">{patternName(c.pattern)}</span>
                <span className="hk-muted">{subjectKindLabel(c.subjectKind)}</span>
                <button
                  type="button"
                  className="hk-btn hk-btn--secondary hk-btn--sm"
                  disabled={busy !== undefined}
                  onClick={() => void raise(c)}
                  data-testid={`raise-${c.control.rowId}`}
                >
                  {busy === c.control.rowId ? "Raising…" : "Raise"}
                </button>
              </li>
            ))}
          </ul>
        ) : null}
      </div>
    </details>
  );
}
