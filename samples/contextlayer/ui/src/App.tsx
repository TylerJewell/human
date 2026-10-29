import {
  createServiceClient,
  ErrorLine,
  OversightProvider,
  readable,
  useEvents,
  useRead,
  useRegistry,
  useReviewer,
  useSetReviewer,
  type Reviewer,
  type ServiceClient,
  type ServiceInfo
} from "@akka-human/kit";
import { useEffect, useMemo, useState } from "react";
import { useCases, useLinks } from "./api";
import { sortLinks } from "./model";
import { Icon } from "./parts/ui";
import { Cases } from "./screens/Cases";
import { Decisions } from "./screens/Decisions";
import { Links } from "./screens/Links";

// contextlayer's console: the shell (top bar, navigation, status bar), the
// person the console acts as, and the three screens. Every call goes to the
// service that serves this page, so the oversight client talks to `/oversight`
// on the same origin and stamps the chosen reviewer on each write.

const ACTING_AS = "contextlayer-console:acting-as";

function storedReviewer(): Reviewer | null {
  try {
    const raw = localStorage.getItem(ACTING_AS);
    return raw ? (JSON.parse(raw) as Reviewer) : null;
  } catch {
    return null;
  }
}

function storeReviewer(reviewer: Reviewer) {
  try {
    localStorage.setItem(ACTING_AS, JSON.stringify(reviewer));
  } catch {
    // The choice then lasts until the page is reloaded.
  }
}

/** The hash route as its segments: `#/links/abc` is ["links", "abc"]. */
function useRoute(): [string[], (path: string) => void] {
  const read = () =>
    window.location.hash
      .replace(/^#\/?/, "")
      .split("/")
      .filter(Boolean)
      .map(decodeURIComponent);
  const [route, setRoute] = useState(read);
  useEffect(() => {
    const changed = () => setRoute(read());
    window.addEventListener("hashchange", changed);
    return () => window.removeEventListener("hashchange", changed);
  }, []);
  return [route, (path) => void (window.location.hash = path)];
}

export function App() {
  const client = useMemo(
    () => createServiceClient({ baseUrl: "", reviewer: storedReviewer(), identityProvider: "contextlayer-console" }),
    []
  );
  return (
    <OversightProvider client={client} pollMs={4000}>
      <Shell />
    </OversightProvider>
  );
}

const SCREENS = ["decisions", "links", "cases"] as const;

function Shell() {
  const [route, go] = useRoute();
  const screen = (SCREENS as readonly string[]).includes(route[0]) ? route[0] : "decisions";
  const id = route[1];
  const waiting = useEvents({ state: "pending", limit: 1 }).data?.total;
  const links = useLinks().data;
  const cases = useCases().data;
  const toReview = links ? sortLinks(links).toReview.length : undefined;
  const open = cases?.filter((c) => c.status === "OPEN").length;

  return (
    <div className="cl">
      <header className="cl-top">
        <a className="cl-brand" href="#/decisions" aria-label="Akka Context">
          <svg viewBox="0 0 780 178" aria-hidden="true">
            <polygon points="324.67856 .12152 259.48772 72.62656 259.48772 0 216.25386 0 216.25386 177.95226 259.48772 177.95226 259.48772 105.32551 324.67856 177.83055 381.64294 177.83055 301.75157 88.97603 381.64294 .12152 324.67856 .12152" fill="currentColor" />
            <polygon points="516.96368 .12152 451.77284 72.62656 451.77284 0 408.53899 0 408.53899 177.95226 451.77284 177.95226 451.77284 105.32551 516.96368 177.83055 573.92807 177.83055 494.0367 88.97603 573.92807 .12152 516.96368 .12152" fill="currentColor" />
            <path d="M114.05281.12191h-32.65812L0,177.83094h45.97063l11.10147-25.74347h82.39016l11.10147,25.74347h45.97063L115.13967.12191h-1.08685ZM72.87776,115.43539l25.38942-58.87581,25.38942,58.87581h-50.77884Z" fill="currentColor" />
            <path d="M697.67508.12191h-33.74498l-81.39469,177.70903h45.97063l11.10147-25.74347h82.39016l11.10147,25.74347h45.97063L697.67508.12191ZM655.41317,115.43539l25.38942-58.87581,25.38942,58.87581h-50.77884Z" fill="currentColor" />
          </svg>
          <span className="cl-brand__prod">Context</span>
        </a>
        <div className="cl-picker">
          Service
          <b>
            <Icon name="folder" size={14} />
            contextlayer
          </b>
        </div>
        <div className="cl-picker">
          Environment
          <b>
            <Icon name="pin" size={14} />
            Local
          </b>
        </div>
        <div className="cl-top__right">
          <ActingAs />
        </div>
      </header>

      <nav className="cl-side" aria-label="Screens">
        <div className="cl-nav">
          <div className="cl-nav__grp">Connect</div>
          <a href="#/links" className={screen === "links" ? "on" : undefined}>
            <Icon name="links" />
            <span>Links</span>
            {toReview ? <span className="cl-count cl-count--blue">{toReview}</span> : null}
          </a>
          <div className="cl-nav__grp">Watch</div>
          <a href="#/cases" className={screen === "cases" ? "on" : undefined}>
            <Icon name="cases" />
            <span>Cases</span>
            {open ? <span className="cl-count">{open}</span> : null}
          </a>
          <div className="cl-nav__grp">Act</div>
          <a href="#/decisions" className={screen === "decisions" ? "on" : undefined}>
            <Icon name="decisions" />
            <span>Decisions</span>
            {waiting ? <span className="cl-count">{waiting}</span> : null}
          </a>
        </div>
      </nav>

      <main className="cl-main">
        {screen === "links" ? <Links id={id} /> : screen === "cases" ? <Cases id={id} go={go} /> : <Decisions id={id} go={go} />}
      </main>

      <StatusBar waiting={waiting} toReview={toReview} />
    </div>
  );
}

/** Who the console acts as: a person from the service's overseer registry, with all their roles. */
function ActingAs() {
  const registry = useRegistry();
  const current = useReviewer();
  const set = useSetReviewer();
  const people = registry.data?.overseers ?? [];
  const chosen = current.data;
  const choose = (subject: string) => {
    const person = people.find((p) => p.subject === subject);
    if (!person) return;
    const reviewer: Reviewer = { subject: person.subject, name: person.name, roles: person.roles };
    set.run(reviewer).then(() => storeReviewer(reviewer), () => undefined);
  };
  return (
    <div className="cl-who" data-testid="acting-as">
      <Icon name="user" />
      <label>
        <span className="cl-sr">Acting as</span>
        <select value={chosen?.subject ?? ""} onChange={(e) => choose(e.target.value)} aria-label="Acting as">
          <option value="" disabled>
            {registry.data ? "Choose who you act as" : "Reading the registry…"}
          </option>
          {people.map((p) => (
            <option key={p.subject} value={p.subject}>
              {p.name ?? p.subject}
              {p.qualified ? "" : " (not qualified)"}
            </option>
          ))}
        </select>
      </label>
      {chosen ? <span className="cl-who__roles">{chosen.roles.map(readable).join(" · ").toLowerCase()}</span> : null}
      {set.error ?? registry.error ? (
        <div className="hk">
          <ErrorLine error={set.error ?? registry.error} />
        </div>
      ) : null}
    </div>
  );
}

function StatusBar({ waiting, toReview }: { waiting?: number; toReview?: number }) {
  const info = useRead<ServiceInfo>("service-info", (client) => (client as ServiceClient).info(), { pollMs: 15_000 });
  return (
    <footer className="cl-status">
      {info.error ? (
        <span className="cl-bad">The service does not answer</span>
      ) : (
        <span>
          {info.data?.environment ? readable(info.data.environment) : "Local"} · {info.data?.service ?? "contextlayer"}
          {info.data ? ` · oversight contract ${info.data.contract}` : ""}
        </span>
      )}
      {waiting !== undefined ? <span>{waiting === 1 ? "1 needs a decision" : `${waiting} need a decision`}</span> : null}
      {toReview !== undefined ? <span>{toReview === 1 ? "1 link to review" : `${toReview} links to review`}</span> : null}
      <span className="cl-status__sp cl-gold">Development sign-in: decisions are recorded as rehearsals</span>
    </footer>
  );
}
