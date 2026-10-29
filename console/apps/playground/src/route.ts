import { useCallback, useEffect, useState } from "react";

/** The page's hash as path parts ("#/live/event/he-1" is ["live", "event", "he-1"]) and a way to move. */
export function useRoute(): [string[], (path: string) => void] {
  const read = () =>
    window.location.hash
      .replace(/^#\/?/, "")
      .split("/")
      .filter(Boolean)
      .map(decodeURIComponent);
  const [parts, setParts] = useState(read);
  useEffect(() => {
    const changed = () => setParts(read());
    window.addEventListener("hashchange", changed);
    return () => window.removeEventListener("hashchange", changed);
  }, []);
  const go = useCallback((path: string) => {
    window.location.hash = path.startsWith("/") ? path : `/${path}`;
  }, []);
  return [parts, go];
}

/** A value kept in this browser between visits; a convenience, so a missing store is ignored. */
export function stored<T>(key: string, fallback: T): T {
  try {
    const raw = window.localStorage.getItem(key);
    return raw ? (JSON.parse(raw) as T) : fallback;
  } catch {
    return fallback;
  }
}

export function store(key: string, value: unknown) {
  try {
    window.localStorage.setItem(key, JSON.stringify(value));
  } catch {
    // Not kept: the next visit starts from the defaults.
  }
}
