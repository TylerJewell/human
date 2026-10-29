import kitCss from "@akka-human/kit/kit.css?inline";
import {
  createCompanionClient,
  createServiceClient,
  DecisionPanel,
  ErrorLine,
  EventPage,
  OversightProvider,
  Queue,
  useEvent,
  type EventWrite,
  type HumanEvent,
  type HumanEventRow,
  type OversightClient,
  type QueueFilters,
  type Reviewer
} from "@akka-human/kit";
import type { ReactNode } from "react";
import { createRoot, type Root } from "react-dom/client";

// Custom elements over @akka-human/kit, for consoles that are not React. Each
// renders a kit component into its own shadow root with the kit's stylesheet,
// reads and writes through the companion (`mode="companion"`, the default) or a
// service directly (`mode="service"`, with a `reviewer`), and reports what
// happens as DOM events that bubble out of the shadow root:
//
//   human-write     after any write on an event      detail: { event, write }
//   human-decision  after a decision is recorded     detail: { event, action }
//   human-open      when a queue row is opened       detail: { id, row }

const SHARED = ["base-url", "mode", "reviewer", "theme", "poll-ms"];

/** The parts every element shares: the shadow root, the client, the provider, the events. */
abstract class HumanElement extends HTMLElement {
  private root?: Root;
  private container?: HTMLDivElement;
  private client?: { key: string; value: OversightClient };
  private reviewerValue?: Reviewer;

  /** Who a service client writes as; set as a property or as the `reviewer` attribute's JSON. */
  get reviewer(): Reviewer | undefined {
    if (this.reviewerValue) return this.reviewerValue;
    const raw = this.getAttribute("reviewer");
    if (!raw) return undefined;
    try {
      const parsed = JSON.parse(raw) as Reviewer;
      return typeof parsed?.subject === "string" ? { ...parsed, roles: parsed.roles ?? [] } : undefined;
    } catch {
      return undefined;
    }
  }

  set reviewer(value: Reviewer | undefined) {
    this.reviewerValue = value;
    this.update();
  }

  connectedCallback() {
    if (!this.shadowRoot) {
      const shadow = this.attachShadow({ mode: "open" });
      const style = document.createElement("style");
      style.textContent = `${kitCss}\n:host { display: block; }`;
      this.container = document.createElement("div");
      this.container.className = "hk";
      shadow.append(style, this.container);
    }
    this.update();
  }

  disconnectedCallback() {
    // Unmounted after the current task, so moving the element in the page keeps it.
    queueMicrotask(() => {
      if (!this.isConnected) {
        this.root?.unmount();
        this.root = undefined;
      }
    });
  }

  attributeChangedCallback() {
    this.update();
  }

  /** The client for the current attributes, made again only when they change. */
  protected clientFor(): OversightClient {
    const baseUrl = this.getAttribute("base-url") ?? "";
    const mode = this.getAttribute("mode") === "service" ? "service" : "companion";
    const reviewer = this.reviewer;
    const key = JSON.stringify([baseUrl, mode, reviewer]);
    if (this.client?.key !== key) {
      const value = mode === "service" ? createServiceClient({ baseUrl, reviewer: reviewer ?? null }) : createCompanionClient({ baseUrl });
      this.client = { key, value };
    }
    return this.client.value;
  }

  protected emit(name: string, detail: unknown) {
    this.dispatchEvent(new CustomEvent(name, { detail, bubbles: true, composed: true }));
  }

  /** Reports a write, and a decision when it was one. */
  protected wrote = (event: HumanEvent, write: EventWrite) => {
    this.emit("human-write", { event, write });
    if (write.kind === "decision") this.emit("human-decision", { event, action: write.body.action });
  };

  protected abstract view(): ReactNode;

  private update() {
    if (!this.isConnected || !this.container) return;
    const theme = this.getAttribute("theme");
    if (theme) this.container.setAttribute("data-hk-theme", theme);
    else this.container.removeAttribute("data-hk-theme");
    const poll = Number(this.getAttribute("poll-ms") ?? 5000);
    this.root ??= createRoot(this.container);
    this.root.render(
      <OversightProvider client={this.clientFor()} pollMs={Number.isFinite(poll) ? poll : 5000}>
        {this.view()}
      </OversightProvider>
    );
  }
}

function PanelById({ id, onWrite }: { id: string; onWrite: (event: HumanEvent, write: EventWrite) => void }) {
  const read = useEvent(id);
  if (!read.data) {
    return read.error ? <ErrorLine error={read.error} /> : <div className="hk-skeleton hk-skeleton--block" aria-busy="true" />;
  }
  return <DecisionPanel event={read.data} onWrite={onWrite} />;
}

/** `<human-decision-panel event-id base-url mode reviewer>`: one event's decision panel. */
export class HumanDecisionPanelElement extends HumanElement {
  static observedAttributes = [...SHARED, "event-id"];

  protected view() {
    const id = this.getAttribute("event-id");
    return id ? <PanelById key={id} id={id} onWrite={this.wrote} /> : null;
  }
}

/** `<human-event event-id base-url mode reviewer>`: one event's whole page. */
export class HumanEventElement extends HumanElement {
  static observedAttributes = [...SHARED, "event-id"];

  protected view() {
    const id = this.getAttribute("event-id");
    return id ? <EventPage key={id} eventId={id} onWrite={this.wrote} /> : null;
  }
}

/** `<human-queue base-url mode state pattern subject routable selected-id>`: the queue. */
export class HumanQueueElement extends HumanElement {
  static observedAttributes = [...SHARED, "state", "pattern", "subject", "routable", "selected-id"];

  protected view() {
    const initial: Partial<QueueFilters> = {
      state: this.getAttribute("state") === "resolved" ? "resolved" : "pending",
      pattern: this.getAttribute("pattern") ?? undefined,
      subject: this.getAttribute("subject") ?? undefined,
      routable: this.hasAttribute("routable")
    };
    return (
      <Queue
        key={JSON.stringify(initial)}
        initial={initial}
        selectedId={this.getAttribute("selected-id") ?? undefined}
        onOpen={(row: HumanEventRow) => this.emit("human-open", { id: row.id, row })}
      />
    );
  }
}

export const ELEMENTS = {
  "human-decision-panel": HumanDecisionPanelElement,
  "human-event": HumanEventElement,
  "human-queue": HumanQueueElement
} as const;

/** Registers the elements under their names, skipping any already registered. */
export function defineHumanElements(registry: CustomElementRegistry = customElements) {
  for (const [name, element] of Object.entries(ELEMENTS)) {
    if (!registry.get(name)) registry.define(name, element);
  }
}

if (typeof customElements !== "undefined") defineHumanElements();

declare global {
  interface HTMLElementTagNameMap {
    "human-decision-panel": HumanDecisionPanelElement;
    "human-event": HumanEventElement;
    "human-queue": HumanQueueElement;
  }
}
