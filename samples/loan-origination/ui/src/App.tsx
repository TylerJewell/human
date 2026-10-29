import { createServiceClient, OversightProvider } from "@akka-human/kit";
import { useEffect, useMemo, useState } from "react";
import { useApplications, useControlRows } from "./api";
import { AKKA_CONSOLE, CONTEST, HELD_NOTICE } from "./model";
import { ActingAs, storedReviewer } from "./parts";
import { Applications } from "./screens/Applications";
import { Contests } from "./screens/Contests";
import { Notices } from "./screens/Notices";
import { Underwriting } from "./screens/Underwriting";

// The loan service's underwriting desk: the applications, the underwriting
// queue, held adverse-action notices and contests, each decided next to the
// application through the oversight client of this same service. Governance
// (halts, model changes, monitors, flags) is decided in the Akka console.

function useRoute(): string[] {
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
  return route;
}

export function App() {
  const client = useMemo(
    () => createServiceClient({ baseUrl: "", reviewer: storedReviewer(), identityProvider: "loan-underwriting-desk" }),
    []
  );
  return (
    <OversightProvider client={client} pollMs={4000}>
      <Desk />
    </OversightProvider>
  );
}

const TABS = [
  { id: "underwriting", label: "Underwriting queue" },
  { id: "notices", label: "Held notices" },
  { id: "contests", label: "Contests" },
  { id: "applications", label: "Applications" }
] as const;

function Desk() {
  const route = useRoute();
  const tab = TABS.some((t) => t.id === route[0]) ? route[0] : "underwriting";
  const id = route[1];
  const applications = useApplications().data;
  const notices = useControlRows(HELD_NOTICE).data;
  const contests = useControlRows(CONTEST).data;
  const counts: Record<string, number | undefined> = {
    underwriting: applications?.filter((a) => a.status === "AWAITING_HUMAN_REVIEW").length,
    notices: notices?.filter((r) => r.pending).length,
    contests: contests?.filter((r) => r.pending).length
  };

  return (
    <div className="d">
      <header className="d-bar">
        <div className="d-bar__brand">
          <span className="d-bar__mark" aria-hidden="true">
            LO
          </span>
          <div>
            <div className="d-bar__name">Loan Operations</div>
            <div className="d-bar__sub">Underwriting desk</div>
          </div>
        </div>
        <ActingAs />
      </header>
      <nav className="d-tabs" aria-label="Desk">
        {TABS.map((t) => (
          <a key={t.id} href={`#/${t.id}`} aria-current={tab === t.id ? "page" : undefined}>
            {t.label}
            {counts[t.id] ? <span className="d-tabs__n">{counts[t.id]}</span> : null}
          </a>
        ))}
        <a className="d-tabs__out" href={AKKA_CONSOLE} target="_blank" rel="noreferrer" title="Halts, model changes, monitors and flags">
          Governance: Akka console ↗
        </a>
      </nav>
      <main className="d-main">
        {tab === "applications" ? (
          <Applications id={id} />
        ) : tab === "notices" ? (
          <Notices id={id} />
        ) : tab === "contests" ? (
          <Contests id={id} />
        ) : (
          <Underwriting id={id} />
        )}
      </main>
      <footer className="d-foot">
        Decisions on this desk are the loan's own: underwriting, held notices and contests. Halts, model changes, monitors and flagged
        replies are governance and are decided in the <a href={AKKA_CONSOLE}>Akka console</a>. Signed in for development: every
        decision is recorded as a rehearsal.
      </footer>
    </div>
  );
}
