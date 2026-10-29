import { ErrorLine, SummaryTiles, useHalts, useNow, useRegistry, useStanding, useSummary } from "@akka-human/kit";
import { useControls, useOverdue, useServices } from "../api";
import { attentionOf, overviewSentence } from "../logic";

// The overview: one sentence on what waits, the figures of each loop across
// every service, what needs attention, and which services answer.

export function Overview() {
  const now = useNow(30_000);
  const summary = useSummary();
  const services = useServices();
  const overdue = useOverdue();
  const halts = useHalts();
  const registry = useRegistry();
  const standing = useStanding();
  const controls = useControls();
  const nameOf = (s: string) => registry.data?.overseers.find((o) => o.subject === s)?.name ?? s;
  const items = attentionOf({
    overdue: overdue.data ?? [],
    halts: halts.data ?? summary.data?.halts ?? [],
    services: services.data ?? [],
    registry: registry.data,
    controls: controls.data ?? [],
    standing: standing.data ?? [],
    unowned: summary.data?.unownedControls ?? 0,
    now,
    nameOf
  });
  const reading = [summary, services, overdue, halts, registry, standing, controls].some((r) => r.loading);
  const list = services.data ?? [];

  return (
    <div className="hk-stack cs-overview" data-testid="overview">
      <p className="cs-lead" data-testid="overview-sentence">
        {overviewSentence(summary.data, list)}
      </p>
      <SummaryTiles />

      <section className="hk hk-section" aria-labelledby="cs-attention">
        <div className="hk-section__head">
          <h2 id="cs-attention" className="hk-section__title">
            Needs attention
          </h2>
          {items.length ? <span className="hk-chip hk-chip--warning">{items.length}</span> : null}
        </div>
        <div className="hk-panel hk-panel--flush">
          {items.length === 0 ? (
            <p className="cs-empty" data-testid="attention-empty">
              {reading ? "Reading what needs attention…" : "Nothing needs attention."}
            </p>
          ) : (
            <ul className="cs-attention" data-testid="attention">
              {items.map((item) => (
                <li key={item.id} className="cs-attention__item" data-severity={item.severity} data-testid="attention-item">
                  <span className={`cs-attention__mark cs-attention__mark--${item.severity}`} aria-hidden="true" />
                  <span className="cs-attention__text">{item.text}</span>
                  <a className="hk-link" href={`#${item.to}`}>
                    {item.action}
                  </a>
                </li>
              ))}
            </ul>
          )}
        </div>
        <ErrorLine error={summary.error ?? overdue.error ?? controls.error} />
      </section>

      <section className="hk hk-section" aria-labelledby="cs-services">
        <div className="hk-section__head">
          <h2 id="cs-services" className="hk-section__title">
            Services
          </h2>
          <a className="hk-link hk-section__controls" href="#/services">
            All services
          </a>
        </div>
        <div className="hk-panel hk-panel--flush">
          {list.length === 0 ? (
            <p className="cs-empty">{services.error ? "The companion could not list its services." : "No service is registered."}</p>
          ) : (
            <ul className="cs-services-mini" data-testid="services-mini">
              {list.map((s) => (
                <li key={s.service} data-up={s.up}>
                  <span className={`cs-dot cs-dot--${s.up ? "up" : "down"}`} aria-hidden="true" />
                  <span className="cs-services-mini__name">{s.service}</span>
                  <span className="hk-muted">{s.up ? `${s.info?.patterns.length ?? 0} patterns · ${s.info?.controls ?? 0} controls` : "does not answer"}</span>
                  <span className="hk-muted cs-mono">{s.url}</span>
                </li>
              ))}
            </ul>
          )}
        </div>
      </section>
    </div>
  );
}
