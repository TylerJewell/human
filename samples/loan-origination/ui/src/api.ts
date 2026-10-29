import { parseEvent, useRead, type HumanEvent } from "@akka-human/kit";
import type { Application, ApplicationRow } from "./model";

// The service's own routes and its oversight events, read same-origin through
// the oversight provider's cache so they poll with everything else. Events are
// read whole, not as queue rows, since the desk finds each review's application
// by `subject.ref`, which a row does not carry.

async function getJson<T>(path: string): Promise<T> {
  const response = await fetch(path, { headers: { Accept: "application/json" } });
  if (!response.ok) throw new Error(`GET ${path} answered ${response.status} ${response.statusText}`.trim());
  return (await response.json()) as T;
}

export const useApplications = () =>
  useRead<ApplicationRow[]>("domain:applications", async () => (await getJson<{ entries: ApplicationRow[] }>("/applications")).entries);

export const useUnderwritingQueue = () =>
  useRead<ApplicationRow[]>("domain:underwriting-queue", async () => (await getJson<{ entries: ApplicationRow[] }>("/underwriting/queue")).entries);

export const useApplication = (id: string | undefined) =>
  useRead<Application>(`domain:application:${id ?? ""}`, () => getJson(`/applications/${encodeURIComponent(id!)}`), {
    enabled: Boolean(id)
  });

/** Every event of one control, pending and answered, whole. */
export const useControlEvents = (control: string) =>
  useRead<HumanEvent[]>(`domain:events:${control}`, async () => {
    const read = async (state: string) =>
      (await getJson<{ events: unknown[] }>(`/oversight/events?control=${encodeURIComponent(control)}&state=${state}&limit=200`)).events;
    const raw = [...(await read("pending")), ...(await read("resolved"))];
    const events: HumanEvent[] = [];
    for (const item of raw) {
      const parsed = parseEvent(item);
      if (parsed.ok) events.push(parsed.value);
    }
    return events;
  });
