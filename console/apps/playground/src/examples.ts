import { parseEvent, type HumanEvent, type Subject } from "@akka-human/kit";

// The spec's examples, by file name without `.json`. The gallery draws them
// offline and the raise panel takes its sample subjects from them.
const modules = import.meta.glob("../../../../spec/examples/*.json", { eager: true, import: "default" });

export const examples: Record<string, unknown> = Object.fromEntries(
  Object.entries(modules).map(([path, json]) => [path.split("/").pop()!.replace(/\.json$/, ""), json])
);

export const example = <T,>(name: string): T => structuredClone(examples[name]) as T;

/** Every HumanEvent example's name, in the order the gallery lists them. */
export const EVENT_EXAMPLES = Object.keys(examples)
  .filter((n) => n.startsWith("HumanEvent."))
  .sort();

/** An example event read as if its service had just answered, so its clock offset is nil. */
export function exampleEvent(name: string): HumanEvent {
  const raw = example<{ now?: string }>(name);
  const parsed = parseEvent(raw, raw.now ? Date.parse(raw.now) : Date.now());
  if (!parsed.ok) throw new Error(`${name} does not read as an event: ${parsed.issues.join("; ")}`);
  return parsed.value;
}

/** The example subject of one kind, with its summary replaced. */
export function exampleSubject(kind: string, summary: string, service: string): Subject {
  const name = `HumanEvent.${kind}`;
  const subject = exampleEvent(name).subject;
  return { ...subject, summary, service };
}

const ISO = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z$/;

/** `value` with every timestamp in it moved by `ms`, so an example reads as raised a moment ago. */
export function shiftTimes<T>(value: T, ms: number): T {
  if (typeof value === "string") return (ISO.test(value) ? new Date(Date.parse(value) + ms).toISOString() : value) as T;
  if (Array.isArray(value)) return value.map((v) => shiftTimes(v, ms)) as T;
  if (value && typeof value === "object")
    return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, shiftTimes(v, ms)])) as T;
  return value;
}
