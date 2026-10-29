import { useRead } from "@akka-human/kit";
import type { Case, LinkView } from "./model";

// The service's own routes, read same-origin through the oversight provider's
// cache, so they poll with the events and a screen can read them again after
// a decision.

async function getJson<T>(path: string): Promise<T> {
  const response = await fetch(path, { headers: { Accept: "application/json" } });
  if (!response.ok) throw new Error(`GET ${path} answered ${response.status} ${response.statusText}`.trim());
  return (await response.json()) as T;
}

export const useLinks = () => useRead<LinkView[]>("domain:links", () => getJson("/links"));

export const useCases = () => useRead<Case[]>("domain:cases", () => getJson("/cases"));
