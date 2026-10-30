import type { SourceKey } from "../code/sources";

// What the tour knows about each pattern: the story it runs, the four steps it
// walks through, and the code beside each step. The words live in patterns.ts;
// the screens for each scenario live in scenes/.

export type FlavorId =
  | "human-system-authorization"
  | "human-domain-decision"
  | "human-feedback"
  | "human-monitoring"
  | "human-halt";

/** How the service brings a person in: the shape of the code that uses the pattern. */
export type HookId = "workflow" | "handler" | "person" | "monitor" | "halt";

export type StepId = "trigger" | "decide" | "effect" | "record";

export const STEP_IDS: readonly StepId[] = ["trigger", "decide", "effect", "record"];

export interface CodeTab {
  label: string;
  key: SourceKey;
  /** Lines containing any of these are the ones the step is about. */
  focus?: string[];
}

export interface StepText {
  /** A few words for the stepper. */
  label: string;
  /** The step's sentence. */
  title: string;
  text: string;
  code: CodeTab[];
}

export interface PatternTour {
  id: string;
  name: string;
  control: string;
  flavor: FlavorId;
  hook: HookId;
  /** What the pattern is, in one sentence. */
  summary: string;
  /** The scenario the tour runs. */
  story: string;
  steps: Record<StepId, StepText>;
}

export interface Flavor {
  id: FlavorId;
  name: string;
  text: string;
}

export interface Hook {
  id: HookId;
  name: string;
  text: string;
}
