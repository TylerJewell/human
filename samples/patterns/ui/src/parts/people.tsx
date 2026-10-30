import { readable, useRegistry, useReviewer, useSetReviewer, type OverseerRegistry, type Reviewer } from "@akka-human/kit";
import { useCallback } from "react";

// Who the tour acts as. Every write to the service carries the chosen person;
// the tour picks someone eligible for each step and says so on the page.

const ACTING_AS = "pattern-tour:acting-as";

export function storedReviewer(): Reviewer | null {
  try {
    const raw = localStorage.getItem(ACTING_AS);
    return raw ? (JSON.parse(raw) as Reviewer) : null;
  } catch {
    return null;
  }
}

function storeReviewer(reviewer: Reviewer) {
  try {
    localStorage.setItem(ACTING_AS, JSON.stringify(reviewer));
  } catch {
    // The choice then lasts until the page is reloaded.
  }
}

/** Sets who the tour acts as, by subject, with every role the registry gives them. */
export function useActAs(): (subject: string) => Promise<Reviewer | undefined> {
  const registry = useRegistry().data;
  const set = useSetReviewer();
  return useCallback(
    async (subject: string) => {
      const person = registry?.overseers.find((p) => p.subject === subject);
      if (!person) return undefined;
      const reviewer: Reviewer = { subject: person.subject, name: person.name, roles: person.roles };
      const chosen = await set.run(reviewer);
      storeReviewer(reviewer);
      return chosen;
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [registry]
  );
}

export const nameIn = (registry: OverseerRegistry | undefined, subject: string | null | undefined) =>
  subject ? (registry?.overseers.find((o) => o.subject === subject)?.name ?? readable(subject.replace(/^agent:/, "agent "))) : "";

export function rolesOf(registry: OverseerRegistry | undefined, subject: string | undefined) {
  const person = registry?.overseers.find((o) => o.subject === subject);
  return person ? person.roles.map(readable).join(" · ").toLowerCase() : "";
}

export const initials = (name: string) =>
  name
    .replace(/[^A-Za-z .]/g, "")
    .split(/[ .]+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((p) => p[0].toUpperCase())
    .join("");

/** The person the tour acts as now. */
export const useMe = () => useReviewer().data ?? null;
