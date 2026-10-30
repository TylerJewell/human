import { Eyebrow, Icon, Mono } from "../parts/ui";
import { FLAVORS, HOOKS, PATTERNS } from "../tour/patterns";
import type { Run } from "../tour/api";

// The tour's first page: the one lifecycle every pattern follows, the five
// ways a service brings a person in, and the fourteen patterns by flavor.

const LIFECYCLE = [
  { icon: "agent", title: "Something happens", text: "An agent, a workflow, a monitor or a person raises an event under a control.", who: "The agent, the applicant or the service" },
  { icon: "user", title: "A person decides", text: "The control routes it to eligible people, keeps the clock, and checks independence.", who: "An overseer" },
  { icon: "play", title: "It takes effect", text: "The library resumes the workflow, completes the task or runs the apply handler, once.", who: "Whoever was waiting" },
  { icon: "ledger", title: "It is on record", text: "The ledger keeps a proof record, and the verdict says whether the control held.", who: "The owner and the auditor" }
];

export function Overview({ go, runs }: { go: (path: string) => void; runs: Record<string, Run | null> }) {
  return (
    <div className="pt-overview" data-testid="overview">
      <header className="pt-intro">
        <Eyebrow>Akka human · pattern tour</Eyebrow>
        <h1>Each of the fourteen oversight patterns runs here, end to end.</h1>
        <p>
          Every pattern follows one lifecycle: something happens, a person decides, the decision takes effect, and the record keeps it. Each page runs one pattern
          against the patterns service and shows, beside every step, the code that drives it.
        </p>
      </header>

      <section className="pt-life" aria-label="The lifecycle">
        <div className="pt-life__rail" aria-hidden="true">
          <span className="pt-life__work" />
        </div>
        {LIFECYCLE.map((s) => (
          <div key={s.title} className="pt-life__step">
            <span className="pt-life__node">
              <Icon name={s.icon} size={20} />
            </span>
            <h3>{s.title}</h3>
            <p>{s.text}</p>
            <span className="pt-life__who">{s.who}</span>
          </div>
        ))}
      </section>

      <section className="pt-block">
        <div className="pt-block__head">
          <Eyebrow>How a service brings a person in</Eyebrow>
          <h2>The code takes one of five shapes.</h2>
        </div>
        <div className="pt-hooks">
          {HOOKS.map((h) => (
            <div key={h.id} className="pt-hook">
              <h3>{h.name}</h3>
              <p>{h.text}</p>
              <div className="pt-hook__list">
                {PATTERNS.filter((p) => p.hook === h.id).map((p) => (
                  <a key={p.id} href={`#/p/${p.id}`}>
                    {p.name}
                  </a>
                ))}
              </div>
            </div>
          ))}
        </div>
      </section>

      <section className="pt-block">
        <div className="pt-block__head">
          <Eyebrow>The patterns</Eyebrow>
          <h2>Five flavors of oversight hold the fourteen patterns.</h2>
        </div>
        <div className="pt-flavors">
          {FLAVORS.map((f) => (
            <div key={f.id} className="pt-flavor">
              <div className="pt-flavor__head">
                <h3>{f.name}</h3>
                <p>{f.text}</p>
              </div>
              {PATTERNS.filter((p) => p.flavor === f.id).map((p) => (
                <button key={p.id} type="button" className="pt-tile" onClick={() => go(`/p/${p.id}`)} data-testid={`tile-${p.id}`}>
                  <span className="pt-tile__top">
                    <span className="pt-tile__name">{p.name}</span>
                    {runs[p.id] ? <span className="pt-tile__ran" title="Run in this browser" /> : null}
                  </span>
                  <span className="pt-tile__text">{p.summary}</span>
                  <span className="pt-tile__foot">
                    <Mono>{p.control}</Mono>
                    <Icon name="arrowR" size={14} />
                  </span>
                </button>
              ))}
            </div>
          ))}
        </div>
      </section>
    </div>
  );
}
