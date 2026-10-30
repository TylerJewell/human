import type { DeciderWords, HumanEvent } from "@akka-human/kit";
import type { FC } from "react";
import type { Run } from "../tour/api";
import type { PatternTour, StepId } from "../tour/model";

// A scenario's screens. Each pattern supplies what the people around the
// decision see before it (Trigger) and after it (Effect); the overseer's view
// and the record are the same for every pattern unless a scene replaces them.

export interface SceneProps {
  tour: PatternTour;
  run: Run | null;
  setRun: (run: Run | null) => void;
  /** The event the decide step is about, as the service answers it now. */
  event?: HumanEvent;
  go: (step: StepId) => void;
}

export interface Scene {
  Trigger: FC<SceneProps>;
  Effect: FC<SceneProps>;
  /** Replaces the overseer's decision page, for patterns whose decision is made differently. */
  Decide?: FC<SceneProps>;
  /** How the decision page names whoever the control keeps from deciding. */
  words?: DeciderWords;
  /**
   * The furthest step the scenario has reached: 0 before it runs, 1 once an event waits for a
   * person, 3 once it has closed. Defaults to reading the run's event.
   */
  reached?: (run: Run | null, event?: HumanEvent) => number;
}
