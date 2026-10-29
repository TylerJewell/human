import { HaltsPanel, OversightProvider, OverseersList, QualityView, ReviewerPicker, StandingList } from "@akka-human/kit";
import { useCallback, useEffect, useState } from "react";
import { client } from "./api";
import { routeOf, VIEWS, type Route } from "./logic";
import { Overview } from "./pages/Overview";
import { ControlsPage, ServicesPage, VerdictsPage } from "./pages/Catalog";
import { EventRoute, QueuePage } from "./pages/Work";

// The Akka console's Human section, standalone: every situation a service's
// human control says a person must judge, across every service the companion
// has found. The header says who the companion acts as; the views below are
// the overview, the queue and each event, the halts, the standing decisions,
// how well people oversee, who may, the controls, the verdicts and the
// services.

type Theme = "light" | "dark" | "auto";

function readTheme(): Theme {
  try {
    const t = window.localStorage.getItem("hk-console-theme");
    return t === "dark" || t === "auto" ? t : "light";
  } catch {
    return "light";
  }
}

function useRoute(): [Route, (path: string) => void] {
  const [route, setRoute] = useState(() => routeOf(window.location.hash));
  useEffect(() => {
    const changed = () => setRoute(routeOf(window.location.hash));
    window.addEventListener("hashchange", changed);
    return () => window.removeEventListener("hashchange", changed);
  }, []);
  const go = useCallback((path: string) => {
    window.location.hash = path;
  }, []);
  return [route, go];
}

const INTRO: Record<string, string> = {
  overview: "What waits for a person across every service, and what needs attention.",
  queue: "Every event waiting for a person, most urgent first, or answered, newest first.",
  halts: "Each scope a halt control covers. Halting takes one authorised person; restarting takes the control's review once every safe-state check passes.",
  standing: "Decisions that settle later events on their own until they expire or are revoked.",
  quality: "How well people oversee: review times against each control's minimum, each reviewer's rates, and the signals they raise.",
  overseers: "Who may decide: each person's roles, training and whether they are qualified today.",
  controls: "Each service's human controls, with their settings resolved from the control, its pattern and the shared defaults.",
  verdicts: "The verdict each closed event gave, and the proof behind it.",
  services: "Each service the companion has found, whether it answers, and whether it conforms to the oversight contract."
};

export function App() {
  const [route, go] = useRoute();
  const [theme, setTheme] = useState<Theme>(readTheme);
  useEffect(() => {
    document.documentElement.setAttribute("data-hk-theme", theme);
    try {
      window.localStorage.setItem("hk-console-theme", theme);
    } catch {
      // Not kept: the next visit starts light.
    }
  }, [theme]);
  const current = route.view === "event" ? "queue" : route.view;
  const title = route.view === "event" ? "Event" : VIEWS.find((v) => v.id === route.view)?.label;

  return (
    <OversightProvider client={client} pollMs={4000}>
      <div className="cs">
        <header className="cs-header">
          <a className="cs-brand" href="#/">
            <span className="cs-brand__mark" aria-hidden="true" />
            Akka
            <span className="cs-brand__section">Human</span>
          </a>
          <ReviewerPicker className="cs-reviewer" />
          <label className="cs-theme">
            <span className="hk-sr-only">Theme</span>
            <select className="hk-input hk-input--sm" value={theme} onChange={(e) => setTheme(e.target.value as Theme)}>
              <option value="light">Light</option>
              <option value="dark">Dark</option>
              <option value="auto">System</option>
            </select>
          </label>
        </header>
        <main className="cs-main">
          <div className="cs-page-head">
            <h1 className="cs-title">Human</h1>
            <p className="cs-intro">{INTRO[current]}</p>
          </div>
          <nav className="cs-tabs" aria-label="Views">
            {VIEWS.map((v) => (
              <a key={v.id} href={`#/${v.id === "overview" ? "" : v.id}`} aria-current={current === v.id ? "page" : undefined}>
                {v.label}
              </a>
            ))}
          </nav>
          <section className="cs-view" aria-label={title}>
            {route.view === "event" ? (
              <EventRoute key={route.id} id={route.id} />
            ) : route.view === "queue" ? (
              <QueuePage go={go} />
            ) : route.view === "halts" ? (
              <HaltsPanel onOpenEvent={(id) => go(`/events/${encodeURIComponent(id)}`)} />
            ) : route.view === "standing" ? (
              <StandingList />
            ) : route.view === "quality" ? (
              <QualityView />
            ) : route.view === "overseers" ? (
              <OverseersList />
            ) : route.view === "controls" ? (
              <ControlsPage />
            ) : route.view === "verdicts" ? (
              <VerdictsPage />
            ) : route.view === "services" ? (
              <ServicesPage />
            ) : (
              <Overview />
            )}
          </section>
        </main>
      </div>
    </OversightProvider>
  );
}
