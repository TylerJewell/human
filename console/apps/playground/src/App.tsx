import { useEffect, useState } from "react";
import { Gallery } from "./gallery/Gallery";
import { Live } from "./live/Live";
import { store, stored, useRoute } from "./route";

type Theme = "light" | "dark" | "auto";

/** The playground: Live drives a running companion or service; Gallery draws every spec example offline. */
export function App() {
  const [route, go] = useRoute();
  const [theme, setTheme] = useState<Theme>(() => stored("hk-playground-theme", "light"));
  useEffect(() => {
    document.documentElement.setAttribute("data-hk-theme", theme);
    store("hk-playground-theme", theme);
  }, [theme]);
  const page = route[0] === "gallery" ? "gallery" : "live";
  return (
    <div className="pg">
      <header className="pg-header">
        <span className="pg-brand">
          <span className="pg-brand__mark" aria-hidden="true" />
          Human oversight
          <span className="pg-brand__sub">playground</span>
        </span>
        <nav className="pg-nav" aria-label="Pages">
          <a href="#/live" aria-current={page === "live" ? "page" : undefined}>
            Live
          </a>
          <a href="#/gallery" aria-current={page === "gallery" ? "page" : undefined}>
            Gallery
          </a>
        </nav>
        <label className="pg-theme">
          <span className="hk-sr-only">Theme</span>
          <select value={theme} onChange={(e) => setTheme(e.target.value as Theme)} className="hk-input hk-input--sm">
            <option value="light">Light</option>
            <option value="dark">Dark</option>
            <option value="auto">System</option>
          </select>
        </label>
      </header>
      <main className="pg-main">{page === "gallery" ? <Gallery route={route.slice(1)} go={go} /> : <Live route={route.slice(1)} go={go} />}</main>
    </div>
  );
}
