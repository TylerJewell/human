import {
  createServiceClient,
  OversightProvider,
  readable,
  useEvent,
  useOversight,
  useRead,
  useRegistry,
  type ServiceClient,
  type ServiceInfo
} from "@akka-human/kit";
import { useEffect, useMemo, useState } from "react";
import { Overview } from "./pages/Overview";
import { PatternPage } from "./pages/PatternPage";
import { storedReviewer, useActAs, useMe } from "./parts/people";
import { Icon, useAction } from "./parts/ui";
import { call, clearRuns, useRuns, type Run } from "./tour/api";
import { FLAVORS, patternById, PATTERNS } from "./tour/patterns";

// The tour's shell: the top bar with the person it acts as, the patterns by
// flavor down the side, and the page. Every call goes to the patterns service
// that serves this page; writes carry the chosen person and are rehearsals.

function useRoute(): [string[], (path: string) => void] {
  const read = () =>
    window.location.hash
      .replace(/^#\/?/, "")
      .split("/")
      .filter(Boolean)
      .map(decodeURIComponent);
  const [route, setRoute] = useState(read);
  useEffect(() => {
    const changed = () => {
      setRoute(read());
      document.querySelector(".pt-main")?.scrollTo({ top: 0 });
    };
    window.addEventListener("hashchange", changed);
    return () => window.removeEventListener("hashchange", changed);
  }, []);
  return [route, (path) => void (window.location.hash = path)];
}

export function App() {
  const client = useMemo(() => createServiceClient({ baseUrl: "", reviewer: storedReviewer(), identityProvider: "pattern-tour" }), []);
  return (
    <OversightProvider client={client} pollMs={4000}>
      <Shell />
    </OversightProvider>
  );
}

const IDS = PATTERNS.map((p) => p.id);

function Shell() {
  const [route, go] = useRoute();
  const runs = useRuns(IDS);
  const tour = route[0] === "p" ? patternById(route[1]) : undefined;
  useEffect(() => {
    document.title = tour ? `${tour.name} · Pattern tour` : "Pattern tour · Akka";
  }, [tour]);

  return (
    <div className="pt">
      <header className="pt-top">
        <a className="pt-brand" href="#/" aria-label="Pattern tour">
          <svg viewBox="0 0 780 178" aria-hidden="true">
            <polygon points="324.67856 .12152 259.48772 72.62656 259.48772 0 216.25386 0 216.25386 177.95226 259.48772 177.95226 259.48772 105.32551 324.67856 177.83055 381.64294 177.83055 301.75157 88.97603 381.64294 .12152 324.67856 .12152" fill="currentColor" />
            <polygon points="516.96368 .12152 451.77284 72.62656 451.77284 0 408.53899 0 408.53899 177.95226 451.77284 177.95226 451.77284 105.32551 516.96368 177.83055 573.92807 177.83055 494.0367 88.97603 573.92807 .12152 516.96368 .12152" fill="currentColor" />
            <path d="M114.05281.12191h-32.65812L0,177.83094h45.97063l11.10147-25.74347h82.39016l11.10147,25.74347h45.97063L115.13967.12191h-1.08685ZM72.87776,115.43539l25.38942-58.87581,25.38942,58.87581h-50.77884Z" fill="currentColor" />
            <path d="M697.67508.12191h-33.74498l-81.39469,177.70903h45.97063l11.10147-25.74347h82.39016l11.10147,25.74347h45.97063L697.67508.12191ZM655.41317,115.43539l25.38942-58.87581,25.38942,58.87581h-50.77884Z" fill="currentColor" />
          </svg>
          <span className="pt-brand__prod">Human</span>
          <span className="pt-brand__sub">Pattern tour</span>
        </a>
        <div className="pt-top__right">
          <ActingAs />
          <ResetTour />
        </div>
      </header>

      <nav className="pt-side" aria-label="Patterns">
        <a href="#/" className={`pt-side__home${tour ? "" : " on"}`}>
          <Icon name="eye" />
          <span>Overview</span>
        </a>
        {FLAVORS.map((f) => (
          <div key={f.id} className="pt-side__group">
            <div className="pt-side__flavor">{f.name}</div>
            {PATTERNS.filter((p) => p.flavor === f.id).map((p) => (
              <NavItem key={p.id} id={p.id} name={p.name} run={runs[p.id]} on={tour?.id === p.id} />
            ))}
          </div>
        ))}
      </nav>

      <main className="pt-main">{tour ? <PatternPage key={tour.id} tour={tour} step={route[2]} go={go} /> : <Overview go={go} runs={runs} />}</main>

      <StatusBar />
    </div>
  );
}

/** A pattern in the side navigation, marked by how far its run has got. */
function NavItem({ id, name, run, on }: { id: string; name: string; run: Run | null; on: boolean }) {
  const event = useEvent(run?.eventId, { pollMs: 5000 }).data;
  const state = !run ? "" : event && !event.pending ? "done" : "wait";
  return (
    <a href={`#/p/${id}`} className={on ? "on" : undefined} data-testid={`nav-${id}`}>
      <span className={`pt-side__dot${state ? ` pt-side__dot--${state}` : ""}`} aria-hidden="true" />
      <span>{name}</span>
    </a>
  );
}

/** Who the tour acts as, from the service's overseer registry. */
function ActingAs() {
  const registry = useRegistry();
  const me = useMe();
  const actAs = useActAs();
  const people = registry.data?.overseers ?? [];
  return (
    <label className="pt-who" data-testid="acting-as">
      <Icon name="user" />
      <span className="pt-who__label">Acting as</span>
      <select value={me?.subject ?? ""} onChange={(e) => void actAs(e.target.value)} aria-label="Acting as">
        <option value="" disabled>
          {registry.data ? "Choose a person" : "Reading the registry"}
        </option>
        {people.map((p) => (
          <option key={p.subject} value={p.subject}>
            {p.name ?? p.subject} · {p.roles.map(readable).join(", ").toLowerCase()}
          </option>
        ))}
      </select>
    </label>
  );
}

/** Closes every open event, lifts halts and forgets every run, so the tour starts clean. */
function ResetTour() {
  const oversight = useOversight();
  const [start, busy, error] = useAction();
  const [asking, setAsking] = useState(false);
  if (asking) {
    return (
      <div className="pt-confirm" role="dialog" aria-label="Reset the tour">
        <span>Close every open event in the patterns service and start the tour clean?</span>
        <button
          type="button"
          className="pt-btn pt-btn--primary"
          disabled={busy}
          onClick={() =>
            start(async () => {
              await call("POST", "/oversight/dev/reset", {});
              await call("PUT", "/eligibility/safe-state", { backlogDrained: false });
              clearRuns(IDS);
              oversight?.cache.invalidate("events:", "event:", "halts", "summary:", "domain:", "proof:", "verdict:", "standing", "quality:");
              setAsking(false);
              window.location.hash = "/";
            })
          }
        >
          Reset
        </button>
        <button type="button" className="pt-btn pt-btn--quiet" onClick={() => setAsking(false)}>
          Keep it
        </button>
        {error ? <span className="pt-error">{error}</span> : null}
      </div>
    );
  }
  return (
    <button type="button" className="pt-btn pt-btn--quiet" onClick={() => setAsking(true)} data-testid="reset-tour">
      <Icon name="reset" size={15} />
      Reset the tour
    </button>
  );
}

function StatusBar() {
  const info = useRead<ServiceInfo>("service-info", (client) => (client as ServiceClient).info(), { pollMs: 15_000 });
  return (
    <footer className="pt-status">
      {info.error ? (
        <span className="pt-status__bad">The patterns service does not answer on this origin.</span>
      ) : (
        <span>
          {info.data?.service ?? "patterns"} · {info.data ? `${info.data.controls} controls · oversight contract ${info.data.contract} · ${readable(info.data.environment ?? "local")}` : "connecting"}
        </span>
      )}
      <span className="pt-status__sp">Decisions made in the tour are rehearsals: they take effect and never count as evidence.</span>
    </footer>
  );
}
