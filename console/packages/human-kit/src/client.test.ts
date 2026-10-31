import { describe, expect, it } from "vitest";
import { createCompanionClient, createServiceClient, OversightError } from "./client";
import { ContractError } from "./parse";
import { example, fakeFetch, tyler } from "./test/fixtures";

const event = example("HumanEvent.action");

describe("companion client", () => {
  const serve = () =>
    fakeFetch((method, url) => {
      if (url.includes("/events?") || url.endsWith("/events")) return { json: example("CompanionEventsPage.one") };
      if (url.endsWith("/reviewer")) return { json: { subject: tyler.subject, name: tyler.name, roles: tyler.roles } };
      if (url.endsWith("/halts")) return { json: { halts: [example("Halt.halted")] } };
      if (url.endsWith("/standing")) return { json: { standing: [example("StandingDecision.pattern")] } };
      if (url.includes("/verdicts")) return { json: { verdicts: [example("Verdict.upheld")] } };
      if (url.endsWith("/registry")) return { json: example("OverseerRegistry.basic") };
      if (url.includes("/summary")) return { json: example("Summary.basic") };
      if (url.includes("/quality")) return { json: example("Quality.basic") };
      if (method === "POST" || url.includes("/events/")) return { json: event };
      return { status: 404, json: { error: "unknown" } };
    });

  it("lists events with the query and routable=me", async () => {
    const { fetch, calls } = serve();
    const client = createCompanionClient({ baseUrl: "http://localhost:9167/", fetch });
    const list = await client.events({ state: "pending", pattern: "action-approval", sla: "at-risk", routable: true, cursor: "c2", limit: 20 });
    expect(calls[0].url).toBe(
      "http://localhost:9167/api/companion/human/events?state=pending&pattern=action-approval&sla=at-risk&cursor=c2&limit=20&routable=me"
    );
    expect(list.rows).toHaveLength(1);
    expect(list.total).toBe(1);
    expect(list.next).toBeNull();
  });

  it("sends writes without a reviewer, for the companion to stamp", async () => {
    const { fetch, calls } = serve();
    const client = createCompanionClient({ fetch });
    await client.claim("he-1");
    await client.decide("he 2", { action: "APPROVE", rationale: "Matches the PO.", checks: ["The invoice matches the purchase order."] });
    await client.escalate("he-3", "Over my limit");
    await client.sendBack("he-4", "Add the eval");
    await client.resubmit("he-5");
    await client.withdraw("he-6", "Superseded");
    await client.extend("he-7", "PT1H", "Waiting on finance");
    await client.reopen("he-8", "New invoice");
    expect(calls.map((c) => `${c.method} ${c.url}`)).toEqual([
      "POST /api/companion/human/events/he-1/claim",
      "POST /api/companion/human/events/he%202/decision",
      "POST /api/companion/human/events/he-3/escalate",
      "POST /api/companion/human/events/he-4/send-back",
      "POST /api/companion/human/events/he-5/resubmit",
      "POST /api/companion/human/events/he-6/withdraw",
      "POST /api/companion/human/events/he-7/extend",
      "POST /api/companion/human/events/he-8/reopen"
    ]);
    expect(calls.map((c) => c.body)).toEqual([
      {},
      { action: "APPROVE", rationale: "Matches the PO.", checks: ["The invoice matches the purchase order."] },
      { reason: "Over my limit" },
      { requested: "Add the eval" },
      {},
      { reason: "Superseded" },
      { extra: "PT1H", reason: "Waiting on finance" },
      { reason: "New invoice" }
    ]);
  });

  it("reads the service that holds each standing decision", async () => {
    const { fetch } = serve();
    const client = createCompanionClient({ fetch });
    expect((await client.standing()).map((d) => d.service)).toEqual(["payments"]);
  });

  it("pages verdicts with since, until and offset", async () => {
    const { fetch, calls } = serve();
    const client = createCompanionClient({ fetch });
    const list = await client.verdicts({ since: "2026-10-01T00:00:00Z", until: "2026-10-29T00:00:00Z", offset: 50, limit: 50 });
    expect(list).toHaveLength(1);
    expect(calls[0].url).toBe(
      "/api/companion/human/verdicts?since=2026-10-01T00%3A00%3A00Z&until=2026-10-29T00%3A00%3A00Z&offset=50&limit=50"
    );
  });

  it("reads and sets the local reviewer", async () => {
    const { fetch, calls } = serve();
    const client = createCompanionClient({ fetch });
    expect(await client.reviewer()).toMatchObject({ subject: tyler.subject });
    await client.setReviewer({ ...tyler, identityProvider: "x" });
    expect(calls[1]).toEqual({
      method: "PUT",
      url: "/api/companion/human/reviewer",
      body: { subject: tyler.subject, name: tyler.name, roles: tyler.roles }
    });
  });

  it("unwraps the merged lists", async () => {
    const { fetch } = serve();
    const client = createCompanionClient({ fetch });
    expect(await client.halts()).toHaveLength(1);
    expect(await client.standing()).toHaveLength(1);
    expect((await client.registry()).overseers).toHaveLength(2);
    expect((await client.summary()).loop.in.pending).toBe(1);
    expect((await client.quality()).reviewers).toHaveLength(1);
  });
});

describe("service client", () => {
  it("stamps the chosen reviewer on every write, as a rehearsal", async () => {
    const { fetch, calls } = fakeFetch(() => ({ json: event }));
    const client = createServiceClient({ baseUrl: "http://localhost:9164", reviewer: tyler, fetch });
    await client.decide("he-1", { action: "DENY", rationale: "No PO." });
    await client.halt("pt-0012", false, "Drift");
    await client.revokeStanding("sd-1", "Supplier changed").catch(() => undefined);
    expect(calls[0].url).toBe("http://localhost:9164/oversight/events/he-1/decision");
    expect(calls[0].body).toEqual({
      action: "DENY",
      rationale: "No PO.",
      reviewer: { subject: tyler.subject, name: tyler.name, roles: tyler.roles, identityProvider: "human-kit", rehearsal: true }
    });
    expect(calls[1]).toMatchObject({
      url: "http://localhost:9164/oversight/halts",
      body: { control: "pt-0012", lift: false, reason: "Drift", reviewer: { subject: tyler.subject } }
    });
    expect(calls[2]).toMatchObject({ url: "http://localhost:9164/oversight/standing/sd-1/revoke", body: { reason: "Supplier changed" } });
  });

  it("refuses to write before a reviewer is chosen, and writes as the next one chosen", async () => {
    const { fetch, calls } = fakeFetch(() => ({ json: event }));
    const client = createServiceClient({ baseUrl: "", fetch });
    await expect(client.claim("he-1")).rejects.toThrow(/Choose who you act as/);
    expect(calls).toHaveLength(0);
    await client.setReviewer({ subject: "s.patel@example.com", roles: ["safety-officer"] });
    await client.release("he-1");
    expect(calls[0].body).toMatchObject({ reviewer: { subject: "s.patel@example.com", roles: ["safety-officer"] } });
  });

  it("lists full events as rows, and asks for routable ones by roles and reviewer", async () => {
    const { fetch, calls } = fakeFetch(() => ({ json: example("EventsPage.one") }));
    const client = createServiceClient({ baseUrl: "http://localhost:9164", reviewer: tyler, fetch });
    const list = await client.events({ state: "pending", routable: true, offset: 50, cursor: "ignored" });
    expect(calls[0].url).toBe(
      "http://localhost:9164/oversight/events?state=pending&offset=50&roles=ai-system-owner&reviewer=tyler.jewell%40akka.io"
    );
    expect(list.rows[0]).toMatchObject({ id: "he-20261029-00001", queue: "system-owners", reviewers: { required: 1, voted: 0 } });
  });

  it("gives each row the record its subject is about, so a service can match it to its own", async () => {
    const page = example<{ events: { subject: Record<string, unknown> }[] }>("EventsPage.one");
    page.events[0].subject.ref = "AP-7730";
    const service = createServiceClient({ baseUrl: "", reviewer: tyler, fetch: fakeFetch(() => ({ json: page })).fetch });
    expect((await service.events({ state: "pending" })).rows[0].subject.ref).toBe("AP-7730");
    const rows = example<{ rows: { subject: Record<string, unknown> }[] }>("CompanionEventsPage.one");
    rows.rows[0].subject.ref = "AP-7731";
    const companion = createCompanionClient({ fetch: fakeFetch(() => ({ json: rows })).fetch });
    expect((await companion.events()).rows[0].subject.ref).toBe("AP-7731");
  });

  it("posts dev raises and reads info", async () => {
    const { fetch, calls } = fakeFetch((_, url) => ({ json: url.endsWith("/info") ? example("ServiceInfo.patterns") : event }));
    const client = createServiceClient({ baseUrl: "http://localhost:9164", reviewer: tyler, fetch });
    expect((await client.info()).devRoutes).toBe(true);
    const raise = example<Record<string, unknown>>("RaiseRequest.action");
    await client.dev.raise(raise as never);
    expect(calls[1]).toEqual({ method: "POST", url: "http://localhost:9164/oversight/dev/events", body: raise });
  });
});

describe("errors", () => {
  it("carries the status, the event's state and what was wrong", async () => {
    const { fetch } = fakeFetch((_, url) =>
      url.includes("/claim")
        ? { status: 409, json: example("Error.conflict") }
        : { status: 422, json: { error: "invalid decision", errors: ["rationale is required"] } }
    );
    const client = createServiceClient({ baseUrl: "", reviewer: tyler, fetch });
    const conflict = await client.claim("he-1").catch((e: unknown) => e);
    expect(conflict).toBeInstanceOf(OversightError);
    expect(conflict).toMatchObject({ status: 409, state: "CLAIMED", message: "claimed by another reviewer" });
    const invalid = await client.decide("he-1", { action: "APPROVE" }).catch((e: unknown) => e);
    expect(invalid).toMatchObject({ status: 422, errors: ["rationale is required"] });
  });

  it("says when the server cannot be reached", async () => {
    const fetch = (() => Promise.reject(new TypeError("Failed to fetch"))) as unknown as typeof globalThis.fetch;
    const client = createCompanionClient({ baseUrl: "http://localhost:9167", fetch });
    await expect(client.event("he-1")).rejects.toMatchObject({ status: 0, message: "http://localhost:9167 could not be reached." });
  });

  it("answers a ContractError, with the summary and raw JSON, for an event it cannot read", async () => {
    const { fetch } = fakeFetch(() => ({ json: { id: "he-1", subject: { summary: "Odd" } } }));
    const client = createCompanionClient({ fetch });
    const error = await client.event("he-1").catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ContractError);
    expect(error).toMatchObject({ summary: "Odd" });
  });
});
