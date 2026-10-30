import { STEP_IDS, type StepId, type StepText } from "../tour/model";
import { Icon } from "./ui";

// The four steps of a pattern as one rail: done steps are solid, the current
// one glows, and a step the scenario has not reached yet cannot be opened.

const EYEBROW: Record<StepId, string> = {
  trigger: "What happens",
  decide: "Who decides",
  effect: "What changes",
  record: "What is kept"
};

export function Stepper({
  steps,
  current,
  reached,
  done,
  go
}: {
  steps: Record<StepId, StepText>;
  current: StepId;
  /** The furthest step the scenario has reached. */
  reached: number;
  /** Steps whose part of the scenario is complete. */
  done: Set<StepId>;
  go: (step: StepId) => void;
}) {
  return (
    <nav className="pt-steps" aria-label="Steps" style={{ ["--reached" as string]: reached }}>
      <div className="pt-steps__rail" aria-hidden="true">
        <span className="pt-steps__fill" />
      </div>
      {STEP_IDS.map((id, i) => {
        const open = i <= reached;
        const state = id === current ? "current" : done.has(id) ? "done" : open ? "open" : "locked";
        return (
          <button
            key={id}
            type="button"
            className={`pt-step pt-step--${state}`}
            onClick={() => open && go(id)}
            disabled={!open}
            aria-current={id === current ? "step" : undefined}
            data-testid={`step-${id}`}
          >
            <span className="pt-step__mark" aria-hidden="true">
              {done.has(id) && id !== current ? <Icon name="check" size={13} /> : null}
            </span>
            <span className="pt-step__eyebrow">{EYEBROW[id]}</span>
            <span className="pt-step__label">{steps[id].label}</span>
          </button>
        );
      })}
    </nav>
  );
}

export const stepEyebrow = (id: StepId) => EYEBROW[id];
