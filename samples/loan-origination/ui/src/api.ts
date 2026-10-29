import { useRead, type HumanEventRow } from "@akka-human/kit";
import type { Application, ApplicationRow } from "./model";

// The service's own routes and its oversight events, read same-origin through
// the oversight provider's cache so they poll with everything else. The
// reviews are listed as queue rows, which carry the application each is about
// in `subject.ref`; the one review open on the desk is read whole.

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

/**
 * Every review under one control, waiting and answered, as queue rows. Kept
 * under the kit's `events:` reads, so a decision made here reads them again.
 */
export const useControlRows = (control: string) =>
  useRead<HumanEventRow[]>(`events:control:${control}`, async (client) => {
    const [pending, resolved] = await Promise.all([
      client.events({ control, state: "pending", limit: 200 }),
      client.events({ control, state: "resolved", limit: 200 })
    ]);
    return [...pending.rows, ...resolved.rows];
  });
