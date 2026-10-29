import { render } from "@testing-library/react";
import type { ReactNode } from "react";
import { vi } from "vitest";
import type { OversightClient } from "../client";
import { OversightProvider } from "../hooks";
import { parseEvent } from "../parse";
import type { HumanEvent, OverseerRegistry, Reviewer } from "../types";

// The spec's examples, by file name without `.json`: every type the contract
// defines, with one HumanEvent per subject kind.
const modules = import.meta.glob("../../../../../spec/examples/*.json", { eager: true, import: "default" });

export const examples: Record<string, unknown> = Object.fromEntries(
  Object.entries(modules).map(([path, json]) => [path.split("/").pop()!.replace(/\.json$/, ""), json])
);

export const example = <T,>(name: string): T => {
  if (!(name in examples)) throw new Error(`No spec example ${name}`);
  return structuredClone(examples[name]) as T;
};

/** An example read as if it arrived the moment its service answered: no clock offset. */
export const asServed = (raw: unknown) => {
  const now = (raw as { now?: unknown })?.now;
  return parseEvent(raw, typeof now === "string" ? Date.parse(now) : Date.now());
};

/** Every HumanEvent example, parsed, by name. */
export const eventExamples: [string, HumanEvent][] = Object.keys(examples)
  .filter((name) => name.startsWith("HumanEvent."))
  .sort()
  .map((name) => {
    const parsed = asServed(examples[name]);
    if (!parsed.ok) throw new Error(`${name} does not parse: ${parsed.issues.join("; ")}`);
    return [name, parsed.value];
  });

export const event = (name: string): HumanEvent => {
  const parsed = asServed(example(name));
  if (!parsed.ok) throw new Error(parsed.issues.join("; "));
  return parsed.value;
};

/** Ten minutes after the examples' events were raised. */
export const NOW = Date.parse("2026-10-29T14:10:00Z");

export const registry = example<OverseerRegistry>("OverseerRegistry.basic");

export const tyler: Reviewer = { subject: "tyler.jewell@akka.io", name: "Tyler Jewell", roles: ["ai-system-owner"] };

/** A client whose every call is a mock: writes answer the event given by `answer`, reads reject unless stubbed. */
export function stubClient(overrides: Partial<OversightClient> = {}) {
  const notStubbed = (name: string) => vi.fn(() => Promise.reject(new Error(`${name} is not stubbed`)));
  const client = {
    mode: "service",
    baseUrl: "",
    events: notStubbed("events"),
    event: notStubbed("event"),
    write: vi.fn(),
    claim: notStubbed("claim"),
    release: notStubbed("release"),
    decide: notStubbed("decide"),
    escalate: notStubbed("escalate"),
    sendBack: notStubbed("sendBack"),
    resubmit: notStubbed("resubmit"),
    withdraw: notStubbed("withdraw"),
    extend: notStubbed("extend"),
    reopen: notStubbed("reopen"),
    summary: notStubbed("summary"),
    quality: notStubbed("quality"),
    verdicts: notStubbed("verdicts"),
    halts: notStubbed("halts"),
    standing: notStubbed("standing"),
    revokeStanding: notStubbed("revokeStanding"),
    registry: vi.fn(() => Promise.resolve(registry)),
    controls: notStubbed("controls"),
    halt: notStubbed("halt"),
    flag: notStubbed("flag"),
    contest: notStubbed("contest"),
    override: notStubbed("override"),
    reviewer: vi.fn(() => Promise.resolve(tyler)),
    setReviewer: notStubbed("setReviewer"),
    ...overrides
  } as unknown as OversightClient & { write: ReturnType<typeof vi.fn> };
  return client;
}

/** Renders `ui` under a provider for `client` that reads once and never polls. */
export function renderWith(client: OversightClient, ui: ReactNode) {
  const wrap = (node: ReactNode) => (
    <OversightProvider client={client} pollMs={0}>
      {node}
    </OversightProvider>
  );
  const view = render(wrap(ui));
  return { ...view, rerender: (node: ReactNode) => view.rerender(wrap(node)) };
}

/** A fetch that answers from `routes` by method and path, recording every call. */
export function fakeFetch(answer: (method: string, url: string, body: unknown) => { status?: number; json?: unknown }) {
  const calls: { method: string; url: string; body: unknown }[] = [];
  const fetch = vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    const body = init?.body ? JSON.parse(String(init.body)) : undefined;
    calls.push({ method, url, body });
    const { status = 200, json } = answer(method, url, body);
    return new Response(json === undefined ? "" : JSON.stringify(json), {
      status,
      headers: { "Content-Type": "application/json" }
    });
  });
  return { fetch: fetch as unknown as typeof globalThis.fetch, calls };
}
