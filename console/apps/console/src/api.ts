import {
  createCompanionClient,
  OversightError,
  useRead,
  type CompanionClient,
  type CompanionService,
  type HumanEventRow,
  type Verdict
} from "@akka-human/kit";
import type { ConformanceReport, ServiceControl } from "./logic";

// The companion is the console's only server, on the origin the console is
// served from. Everything the kit's companion client reads goes through it;
// the conformance run is the one route the console calls on its own.

export const client: CompanionClient = createCompanionClient({ baseUrl: "" });

export const useServices = () =>
  useRead<CompanionService[]>("services", (c) => (c as CompanionClient).services(), { pollMs: 10_000 });

export const useControls = () => useRead<ServiceControl[]>("controls", (c) => c.controls(), { pollMs: 0 });

export const useVerdicts = () => useRead<Verdict[]>("verdicts", (c) => c.verdicts({ limit: 200 }));

/** The pending events past their deadline across every service, read for the overview's attention list. */
export const useOverdue = () =>
  useRead<HumanEventRow[]>("events:overdue", async (c) => (await c.events({ state: "pending", sla: "overdue", limit: 50 })).rows);

/**
 * Runs the conformance suite against one service's development routes and
 * answers its report; it takes as long as the suite does, minutes for a
 * service with every pattern.
 */
export async function runConformance(service: string, fetchImpl: typeof fetch = fetch): Promise<ConformanceReport> {
  let response: Response;
  try {
    response = await fetchImpl("/api/companion/human/conformance", {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "application/json" },
      body: JSON.stringify({ service })
    });
  } catch {
    throw new OversightError(0, "The companion could not be reached.");
  }
  const text = await response.text();
  let json: unknown;
  try {
    json = text ? JSON.parse(text) : undefined;
  } catch {
    json = undefined;
  }
  if (!response.ok) {
    const said = (json as { error?: unknown } | undefined)?.error;
    throw new OversightError(response.status, typeof said === "string" ? said : `The conformance run answered ${response.status}.`, json);
  }
  const report = json as Partial<ConformanceReport> | undefined;
  if (!report || !Array.isArray(report.scenarios)) throw new OversightError(response.status, "The conformance run answered no report.", json);
  return report as ConformanceReport;
}
