import { fireEvent, waitFor } from "@testing-library/dom";
import { act } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import actionEvent from "../../../../spec/examples/HumanEvent.action.json";
import page from "../../../../spec/examples/CompanionEventsPage.one.json";
import registry from "../../../../spec/examples/OverseerRegistry.basic.json";
import "./index";

// The elements' events are React's: `act` lets each render finish before the shadow root is read.
(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const tyler = { subject: "tyler.jewell@akka.io", name: "Tyler Jewell", roles: ["ai-system-owner"] };

/** An event any reviewer may decide at once: no minimum review, no rationale, no checks. */
const quick = {
  ...actionEvent,
  minimumReviewSeconds: 0,
  rationaleRequired: false,
  ask: { ...actionEvent.ask, checks: [] }
};

const calls: { method: string; url: string; body: unknown }[] = [];

beforeEach(() => {
  calls.length = 0;
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string, init?: RequestInit) => {
      const method = init?.method ?? "GET";
      const body = init?.body ? JSON.parse(String(init.body)) : undefined;
      calls.push({ method, url, body });
      const json = url.endsWith("/registry")
        ? registry
        : url.endsWith("/reviewer")
          ? tyler
          : url.includes("/events?")
            ? page
            : url.endsWith("/decision")
              ? { ...quick, state: "APPLIED", pending: false }
              : quick;
      return new Response(JSON.stringify(json), { status: 200, headers: { "Content-Type": "application/json" } });
    })
  );
});

afterEach(() => {
  document.body.innerHTML = "";
  vi.unstubAllGlobals();
});

async function mount(html: string) {
  await act(async () => {
    document.body.innerHTML = html;
  });
  return document.body.firstElementChild as HTMLElement;
}

const inside = (el: HTMLElement, selector: string) => el.shadowRoot!.querySelector<HTMLElement>(selector);

describe("<human-decision-panel>", () => {
  it("renders the kit's panel in a shadow root with the kit's styles", async () => {
    const el = await mount(`<human-decision-panel event-id="he-20261029-00001" base-url="http://localhost:9167"></human-decision-panel>`);
    await waitFor(() => expect(inside(el, "[data-testid=decision-panel]")).not.toBeNull());
    expect(el.shadowRoot!.querySelector("style")!.textContent).toContain(":host { display: block; }");
    expect(calls[0].url).toBe("http://localhost:9167/api/companion/human/events/he-20261029-00001");
    expect(inside(el, "[data-testid=choice-APPROVE]")).toHaveTextContent("Approve");
  });

  it("decides through a service as the given reviewer and reports the decision", async () => {
    const el = await mount(
      `<human-decision-panel event-id="he-20261029-00001" base-url="http://localhost:9164" mode="service" reviewer='${JSON.stringify(tyler)}'></human-decision-panel>`
    );
    const decided = vi.fn();
    const wrote = vi.fn();
    document.addEventListener("human-decision", (e) => decided((e as CustomEvent).detail));
    document.addEventListener("human-write", (e) => wrote((e as CustomEvent).detail));
    await waitFor(() => expect(inside(el, "input[value=DENY]")).not.toBeNull());
    await waitFor(() => expect(inside(el, "input[value=DENY]")).toBeEnabled());
    await act(async () => fireEvent.click(inside(el, "input[value=DENY]")!));
    await act(async () => fireEvent.click(inside(el, "[data-testid=decision-submit]")!));
    await act(async () => fireEvent.click(inside(el, "[data-testid=hk-confirm-run]")!));
    await waitFor(() => expect(decided).toHaveBeenCalled());
    expect(decided.mock.calls[0][0]).toMatchObject({ action: "DENY", event: { state: "APPLIED" } });
    expect(wrote.mock.calls[0][0]).toMatchObject({ write: { kind: "decision" } });
    const post = calls.find((c) => c.method === "POST")!;
    expect(post.url).toBe("http://localhost:9164/oversight/events/he-20261029-00001/decision");
    expect(post.body).toMatchObject({ action: "DENY", reviewer: { subject: tyler.subject, roles: tyler.roles, rehearsal: true } });
  });

  it("takes its theme from the theme attribute", async () => {
    const el = await mount(`<human-decision-panel event-id="he-1" theme="dark"></human-decision-panel>`);
    expect(inside(el, ".hk")).toHaveAttribute("data-hk-theme", "dark");
  });
});

describe("<human-queue>", () => {
  it("lists events and reports the one opened", async () => {
    const el = await mount(`<human-queue base-url="http://localhost:9167" routable></human-queue>`);
    await waitFor(() => expect(inside(el, "[data-testid=queue-row] button")).not.toBeNull());
    expect(calls.find((c) => c.url.includes("/events?"))!.url).toContain("routable=me");
    const opened = vi.fn();
    el.addEventListener("human-open", (e) => opened((e as CustomEvent).detail.id));
    await act(async () => fireEvent.click(inside(el, "[data-testid=queue-row] button")!));
    expect(opened).toHaveBeenCalledWith("he-20261029-00001");
  });
});

describe("<human-event>", () => {
  it("renders the whole event page", async () => {
    const el = await mount(`<human-event event-id="he-20261029-00001"></human-event>`);
    await waitFor(() => expect(inside(el, "[data-testid=event-question]")).toHaveTextContent("Should the agent pay supplier S-77 4,200 EUR?"));
    expect(inside(el, "[data-testid=history]")).not.toBeNull();
  });
});
