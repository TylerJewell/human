import { useControls, useEvent, type HumanEvent } from "@akka-human/kit";
import { useMemo } from "react";
import { CodePanel, type LiveTab } from "../code/CodePanel";
import { Boundary } from "../parts/Boundary";
import { Stepper, stepEyebrow } from "../parts/Stepper";
import { Button, Eyebrow, Mono } from "../parts/ui";
import { SCENES } from "../scenes";
import { DecideStep } from "../steps/Decide";
import { NotYet } from "../steps/common";
import { RecordStep } from "../steps/Record";
import { useProof, useRun, type Run } from "../tour/api";
import { STEP_IDS, type PatternTour, type StepId } from "../tour/model";
import { FLAVORS, HOOKS } from "../tour/patterns";

// One pattern: what it is, the scenario it runs, and its four steps beside the
// code that drives each one. The page keeps the scenario's run, so a reload
// returns to the same events.

const defaultReached = (run: Run | null, event?: HumanEvent) => (!run?.eventId ? 0 : !event || event.pending ? 1 : 3);

export function PatternPage({ tour, step, go }: { tour: PatternTour; step?: string; go: (path: string) => void }) {
  const scene = SCENES[tour.id];
  const [run, setRun] = useRun(tour.id);
  const event = useEvent(run?.eventId, { pollMs: 1500 }).data;
  const shown = event && event.id === run?.eventId ? event : undefined;
  const reached = (scene.reached ?? defaultReached)(run, shown);
  const asked = STEP_IDS.includes(step as StepId) ? (step as StepId) : "trigger";
  const current = STEP_IDS.indexOf(asked) <= reached ? asked : STEP_IDS[reached];
  const goStep = (s: StepId) => go(`/p/${tour.id}/${s}`);
  const text = tour.steps[current];
  const done = new Set<StepId>(STEP_IDS.filter((s, i) => i < reached || (s === "record" && !!shown?.verdict)));

  const control = useControls().data?.find((c) => c.control.rowId === tour.control);
  const flavor = FLAVORS.find((f) => f.id === tour.flavor)!;
  const hook = HOOKS.find((h) => h.id === tour.hook)!;
  const proof = useProof(current === "record" ? (run?.events ?? []) : []).data;
  const live: LiveTab[] = useMemo(() => {
    if (current !== "record") return [];
    const tabs: LiveTab[] = (proof ?? []).map((r) => ({
      label: r.type === "HumanOversightDecisionRecordedV2" ? `Decision record · ${String(r.payload.action ?? "").toLowerCase()}` : r.type,
      caption: `GET /oversight/proof · ${r.recordId}`,
      code: JSON.stringify(r, null, 2),
      lang: "json" as const
    }));
    if (shown?.verdict) tabs.push({ label: "Verdict", caption: `GET /oversight/verdicts?eventId=${shown.id}`, code: JSON.stringify(shown.verdict, null, 2), lang: "json" });
    return tabs;
  }, [current, proof, shown?.verdict, shown?.id]);

  const body = (() => {
    switch (current) {
      case "trigger":
        return <scene.Trigger tour={tour} run={run} setRun={setRun} event={shown} go={goStep} />;
      case "decide":
        if (scene.Decide) return <scene.Decide tour={tour} run={run} setRun={setRun} event={shown} go={goStep} />;
        return shown ? <DecideStep event={shown} go={goStep} words={scene.words} /> : <NotYet go={goStep}>Run the scenario first; the decision appears here once the service raises it.</NotYet>;
      case "effect":
        return shown || run ? (
          <scene.Effect tour={tour} run={run} setRun={setRun} event={shown} go={goStep} />
        ) : (
          <NotYet go={goStep}>Run the scenario and decide it first.</NotYet>
        );
      default:
        return run?.events.length ? <RecordStep tour={tour} events={run.events} /> : <NotYet go={goStep}>Run the scenario and decide it first.</NotYet>;
    }
  })();

  return (
    <article className="pt-pattern" data-testid={`pattern-${tour.id}`}>
      <header className={`pt-hero${current === "trigger" ? "" : " pt-hero--compact"}`}>
        <div className="pt-hero__main">
          <Eyebrow>
            {flavor.name} · {hook.name}
          </Eyebrow>
          <h1>{tour.name}</h1>
          <p className="pt-hero__summary">{tour.summary}</p>
        </div>
        <div className="pt-hero__story">
          <Eyebrow>The scenario</Eyebrow>
          <p>{tour.story}</p>
          <div className="pt-hero__control">
            <Mono>{tour.control}</Mono>
            <span>{control?.control.name ?? ""}</span>
            {control?.control.corpusRef ? (
              <span className="pt-hero__ref" title="The Verify corpus control this sample control stands for">
                <Mono>{control.control.corpusRef}</Mono>
              </span>
            ) : null}
          </div>
          {run ? (
            <div className="pt-hero__reset">
              <Button
                variant="quiet"
                icon="reset"
                onClick={() => {
                  setRun(null);
                  goStep("trigger");
                }}
              >
                Start the scenario over
              </Button>
            </div>
          ) : null}
        </div>
      </header>

      <Stepper steps={tour.steps} current={current} reached={reached} done={done} go={goStep} />

      <div className={`pt-work${current === "decide" ? " pt-work--decide" : ""}`}>
        <section className="pt-stage" aria-labelledby="pt-step-title">
          <div className="pt-stage__head">
            <Eyebrow>{stepEyebrow(current)}</Eyebrow>
            <h2 id="pt-step-title">{text.title}</h2>
            <p>{text.text}</p>
          </div>
          <div className="pt-stage__body" key={`${tour.id}:${current}`}>
            <Boundary>{body}</Boundary>
          </div>
        </section>
        <CodePanel tabs={text.code} live={live} title={current === "record" ? "What the service wrote" : "The code behind this step"} />
      </div>
    </article>
  );
}
