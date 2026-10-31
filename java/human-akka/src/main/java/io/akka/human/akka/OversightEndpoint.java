package io.akka.human.akka;

import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.HttpCharsets;
import akka.http.javadsl.model.HttpEntity;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.MediaTypes;
import akka.http.javadsl.model.StatusCodes;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import akka.stream.Materializer;
import com.fasterxml.jackson.databind.JsonNode;
import io.akka.human.api.Pattern;
import io.akka.human.api.Reviewer;
import io.akka.human.api.spi.HumanPlugins;
import io.akka.human.engine.Blocks;
import io.akka.human.engine.Durations;
import io.akka.human.engine.Figures;
import io.akka.human.engine.HaltState;
import io.akka.human.engine.HumanEvent;
import io.akka.human.engine.Json;
import io.akka.human.engine.Refusal;
import io.akka.human.engine.Rules;
import io.akka.human.engine.StandingDecision;
import io.akka.human.engine.Verdicts;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/**
 * The oversight contract, version 2 (spec/CONTRACT.md): every {@code /oversight/*} route a console or
 * the companion reads and writes. The development routes answer 404 unless the service turns them on.
 *
 * <p>Every call passes the service's {@code AccessPolicy} first, with the reviewer the body carries
 * (none for reads) and the request's headers.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("/oversight")
public class OversightEndpoint extends AbstractHttpEndpoint {

  private static final int DEFAULT_LIMIT = 50;
  private static final int MAX_LIMIT = 200;
  private static final akka.http.javadsl.model.ContentType NDJSON =
      ContentTypes.create(MediaTypes.applicationWithFixedCharset("x-ndjson", HttpCharsets.UTF_8));

  private final ComponentClient client;
  private final HumanLibrary library;
  private final HumanClient human;
  private final Materializer materializer;

  public OversightEndpoint(ComponentClient client, HumanPlugins plugins, Materializer materializer) {
    this.client = client;
    this.library = HumanLibrary.of(plugins);
    this.human = new HumanClient(client, plugins);
    this.materializer = materializer;
  }

  // ---- reading

  @Get("/events")
  public HttpResponse events() {
    return read("GET /oversight/events", () -> {
      var q = requestContext().queryParams();
      var now = library.now();
      var state = q.getString("state").orElse(null);
      var filters = new ArrayList<Predicate<HumanEvent>>();
      if ("pending".equals(state)) filters.add(HumanEvent::pending);
      else if ("resolved".equals(state)) filters.add(e -> !e.pending());
      else if (state != null) filters.add(e -> e.state().name().equals(state));
      q.getString("pattern").ifPresent(p -> filters.add(e -> e.settings().pattern().id().equals(p)));
      q.getString("mode").ifPresent(m -> filters.add(e -> e.settings().mode().equals(m)));
      q.getString("flavor").ifPresent(f -> filters.add(e -> e.settings().flavor().equals(f)));
      q.getString("subject").ifPresent(k -> filters.add(e -> e.subject().kind().id().equals(k)));
      q.getString("control").ifPresent(c -> filters.add(e -> e.settings().control().rowId().equals(c)));
      q.getString("queue").ifPresent(name -> filters.add(e -> e.queue().equals(name)));
      q.getString("holder").ifPresent(h -> filters.add(e -> e.holderAt(now) != null && e.holderAt(now).subject().equals(h)));
      q.getString("sla").ifPresent(s -> filters.add(e -> e.pending() && e.slaState(now).equals(s)));
      if (!q.getString("shadow").map("true"::equals).orElse(false)) filters.add(e -> !e.shadow());
      q.getString("since").map(s -> Wire.instant(s, "since")).ifPresent(since -> filters.add(e -> !e.detectedAt().isBefore(since)));
      q.getString("q").map(String::toLowerCase).ifPresent(text -> filters.add(e ->
          e.ask().question().toLowerCase().contains(text) || e.subject().summary().toLowerCase().contains(text)));
      var reviewer = q.getString("reviewer").orElse(null);
      if (reviewer != null) {
        var roles = q.getString("roles").map(r -> List.of(r.split(",")))
            .orElseGet(() -> library.overseer(reviewer) == null ? List.of() : library.overseer(reviewer).roles());
        var actor = library.actor(new Reviewer(reviewer, null, roles.stream().map(String::trim).toList(), "", false));
        filters.add(e -> Rules.routable(e, actor, now));
      }
      var matching = ("pending".equals(state) ? pending() : all()).stream()
          .filter(e -> filters.stream().allMatch(f -> f.test(e)))
          .sorted(urgency(now)).toList();
      int limit = Math.max(1, Math.min(MAX_LIMIT, q.getInteger("limit").orElse(DEFAULT_LIMIT)));
      int offset = Math.max(0, q.getInteger("offset").orElse(0));
      var blocks = blocks();
      var page = matching.stream().skip(offset).limit(limit)
          .map(e -> (Object) Json.event(e, now, Blocks.blocking(e, blocks))).toList();
      return ok(map("events", page, "total", matching.size()));
    });
  }

  @Get("/events/{id}")
  public HttpResponse event(String id) {
    return read("GET /oversight/events/{id}", () -> answer(human.find(id)));
  }

  @Get("/summary")
  public HttpResponse summary() {
    return read("GET /oversight/summary", () -> {
      var now = library.now();
      var control = requestContext().queryParams().getString("control").orElse(null);
      var events = all().stream().filter(e -> control == null || e.settings().control().rowId().equals(control)).toList();
      return ok(Figures.summary(events, library.controls(), halts(), standing(), now));
    });
  }

  @Get("/quality")
  public HttpResponse quality() {
    return read("GET /oversight/quality", () -> {
      var window = requestContext().queryParams().getString("window").orElse("P30D");
      if (!Durations.valid(window)) return error(Refusal.invalid("window must be an ISO 8601 duration such as P30D"));
      return ok(Figures.quality(all(), library.controls(), subject -> {
        var overseer = library.overseer(subject);
        return overseer == null ? null : overseer.name();
      }, window, library.now()));
    });
  }

  @Get("/verdicts")
  public HttpResponse verdicts() {
    return read("GET /oversight/verdicts", () -> {
      var q = requestContext().queryParams();
      var since = q.getString("since").map(s -> Wire.instant(s, "since")).orElse(Instant.EPOCH);
      var eventId = q.getString("eventId");
      int limit = Math.max(1, Math.min(MAX_LIMIT, q.getInteger("limit").orElse(DEFAULT_LIMIT)));
      var verdicts = all().stream().map(Verdicts::of).filter(Objects::nonNull)
          .filter(v -> !v.at().isBefore(since))
          .filter(v -> eventId.isEmpty() || eventId.get().equals(v.eventId()))
          .sorted(Comparator.comparing(Verdicts.Verdict::at).reversed())
          .limit(limit).map(v -> (Object) Json.verdict(v)).toList();
      return ok(map("verdicts", verdicts));
    });
  }

  @Get("/standing")
  public HttpResponse standingDecisions() {
    return read("GET /oversight/standing", () -> {
      var now = library.now();
      return ok(map("standing", standing().stream().map(d -> (Object) Json.standing(d, now)).toList()));
    });
  }

  @Get("/halts")
  public HttpResponse haltList() {
    return read("GET /oversight/halts", () -> ok(map("halts", halts())));
  }

  @Get("/registry")
  public HttpResponse registry() {
    return read("GET /oversight/registry", () -> ok(Json.registry(library.plugins().overseers().overseers(),
        library.plugins().catalog().controls(), library.now())));
  }

  @Get("/controls")
  public HttpResponse controls() {
    return read("GET /oversight/controls", () -> ok(map("controls",
        library.controls().all().stream().map(c -> (Object) Json.controlSettings(c)).toList())));
  }

  @Get("/proof")
  public HttpResponse proof() {
    return read("GET /oversight/proof", () -> {
      var since = requestContext().queryParams().getString("since").map(s -> Wire.instant(s, "since")).orElse(Instant.EPOCH);
      var lines = new StringBuilder();
      var ledger = library.plugins().ledger();
      if (ledger.isPresent()) {
        var records = ledger.get().since(since).toCompletableFuture().join().stream()
            .sorted(Comparator.comparing(io.akka.human.api.spi.ProofRecord::recordedAt)).toList();
        for (var record : records) lines.append(new String(Wire.write(Json.proof(record)), StandardCharsets.UTF_8)).append('\n');
      }
      return HttpResponse.create().withEntity(NDJSON, lines.toString().getBytes(StandardCharsets.UTF_8));
    });
  }

  @Get("/info")
  public HttpResponse info() {
    return read("GET /oversight/info", () -> {
      var plugins = library.plugins();
      var info = new LinkedHashMap<String, Object>();
      info.put("service", plugins.service());
      info.put("contract", 2);
      info.put("library", HumanLibrary.NAME);
      info.put("patterns", library.controls().patterns().stream().map(Pattern::id).toList());
      info.put("controls", library.controls().all().size());
      info.put("devRoutes", plugins.devRoutes());
      info.put("environment", plugins.environment());
      return ok(info);
    });
  }

  // ---- writing

  @Post("/events/{id}/claim")
  public HttpResponse claim(String id, HttpEntity.Strict body) {
    return write("POST /oversight/events/{id}/claim", body, (json, reviewer) -> entity(id).method(HumanEventEntity::claim)
        .invoke(new HumanEventEntity.Act(library.actor(reviewer), library.now())));
  }

  @Post("/events/{id}/release")
  public HttpResponse release(String id, HttpEntity.Strict body) {
    return write("POST /oversight/events/{id}/release", body, (json, reviewer) -> entity(id)
        .method(HumanEventEntity::release).invoke(new HumanEventEntity.Release(reviewer, library.now())));
  }

  @Post("/events/{id}/decision")
  public HttpResponse decision(String id, HttpEntity.Strict body) {
    return write("POST /oversight/events/{id}/decision", body, (json, reviewer) -> {
      var request = Wire.decision(json, reviewer);
      var current = human.find(id);
      if (current.refused()) return current;
      return entity(id).method(HumanEventEntity::decide).invoke(new HumanEventEntity.Decide(library.actor(reviewer),
          request, safeState(current.event()), Blocks.blocking(current.event(), blocks()), library.now()));
    });
  }

  @Post("/events/{id}/escalate")
  public HttpResponse escalate(String id, HttpEntity.Strict body) {
    return write("POST /oversight/events/{id}/escalate", body, (json, reviewer) -> entity(id)
        .method(HumanEventEntity::escalate)
        .invoke(new HumanEventEntity.Move(library.actor(reviewer), Wire.text(json, "reason"), library.now())));
  }

  @Post("/events/{id}/send-back")
  public HttpResponse sendBack(String id, HttpEntity.Strict body) {
    return write("POST /oversight/events/{id}/send-back", body, (json, reviewer) -> entity(id)
        .method(HumanEventEntity::sendBack)
        .invoke(new HumanEventEntity.Move(library.actor(reviewer), Wire.text(json, "requested"), library.now())));
  }

  @Post("/events/{id}/resubmit")
  public HttpResponse resubmit(String id, HttpEntity.Strict body) {
    return write("POST /oversight/events/{id}/resubmit", body, (json, reviewer) -> entity(id)
        .method(HumanEventEntity::resubmit)
        .invoke(new HumanEventEntity.ByProposer(reviewer, Wire.text(json, "note"), library.now())));
  }

  @Post("/events/{id}/withdraw")
  public HttpResponse withdraw(String id, HttpEntity.Strict body) {
    return write("POST /oversight/events/{id}/withdraw", body, (json, reviewer) -> entity(id)
        .method(HumanEventEntity::withdraw)
        .invoke(new HumanEventEntity.ByProposer(reviewer, Wire.text(json, "reason"), library.now())));
  }

  @Post("/events/{id}/extend")
  public HttpResponse extend(String id, HttpEntity.Strict body) {
    return write("POST /oversight/events/{id}/extend", body, (json, reviewer) -> entity(id)
        .method(HumanEventEntity::extend).invoke(new HumanEventEntity.Extend(library.actor(reviewer),
            Wire.text(json, "extra"), Wire.text(json, "reason"), library.now())));
  }

  @Post("/events/{id}/reopen")
  public HttpResponse reopen(String id, HttpEntity.Strict body) {
    return write("POST /oversight/events/{id}/reopen", body,
        (json, reviewer) -> human.reopen(reviewer, id, Wire.text(json, "reason")));
  }

  @Post("/flags")
  public HttpResponse flag(HttpEntity.Strict body) {
    return write("POST /oversight/flags", body, (json, reviewer) -> human.flag(reviewer, Wire.text(json, "note"),
        Wire.text(json, "flowId"), Wire.text(json, "sessionId"), Wire.text(json, "output"), Wire.text(json, "control")));
  }

  @Post("/halts")
  public HttpResponse halt(HttpEntity.Strict body) {
    return write("POST /oversight/halts", body, (json, reviewer) -> human.halt(reviewer, Wire.text(json, "control"),
        Wire.text(json, "target"), json.path("lift").asBoolean(false), Wire.text(json, "reason")));
  }

  @Post("/contests")
  public HttpResponse contest(HttpEntity.Strict body) {
    return write("POST /oversight/contests", body, (json, reviewer) -> {
      var appellant = json.path("appellant");
      var who = appellant.isObject() ? Wire.text(appellant, "ref") : Wire.text(json, "appellant");
      return human.contest(reviewer, Wire.text(json, "control"), Wire.text(json, "ref"), Wire.text(json, "grounds"), who);
    });
  }

  @Post("/overrides")
  public HttpResponse override(HttpEntity.Strict body) {
    return write("POST /oversight/overrides", body, (json, reviewer) -> human.override(reviewer,
        Wire.text(json, "control"), Wire.text(json, "ref"), Wire.action(Wire.text(json, "action")),
        Wire.text(json, "rationale"), Wire.text(json, "replacement")));
  }

  @Post("/standing/{id}/revoke")
  public HttpResponse revoke(String id, HttpEntity.Strict body) {
    try {
      var json = Wire.parse(body.getData().toArray());
      var reviewer = Wire.reviewer(json);
      var denied = access("POST /oversight/standing/{id}/revoke", reviewer);
      if (denied != null) return denied;
      var decision = standing().stream().filter(d -> d.id().equals(id)).findFirst().orElse(null);
      if (decision == null) return error(Refusal.notFound("standing decision not found"));
      var settings = library.controls().find(decision.control().rowId()).orElse(null);
      boolean may = reviewer.subject().equals(decision.grantedBy())
          || (settings != null && (reviewer.roles().contains(settings.accountableOwner())
              || reviewer.holdsAnyOf(settings.rolesAt(0))));
      if (!may) return error(Refusal.forbidden("only the grantor, the accountable owner or the control's reviewers can revoke"));
      var revoked = client.forKeyValueEntity(StandingDecisionsEntity.ID).method(StandingDecisionsEntity::revoke)
          .invoke(new StandingDecisionsEntity.Revoke(id, reviewer.subject(), Wire.text(json, "reason"), library.now()));
      if (revoked.refusal() != null) return error(revoked.refusal());
      return ok(Json.standing(revoked.decision(), library.now()));
    } catch (IllegalArgumentException e) {
      return error(Refusal.invalid(e.getMessage()));
    }
  }

  // ---- development routes

  @Post("/dev/events")
  public HttpResponse devRaise(HttpEntity.Strict body) {
    return dev("POST /oversight/dev/events", body, json -> answer(human.open(Wire.raise(json))));
  }

  @Post("/dev/clock")
  public HttpResponse devClock(HttpEntity.Strict body) {
    return dev("POST /oversight/dev/clock", body, json -> {
      if (!(library.plugins().clock() instanceof MovableClock clock)) {
        return error(Refusal.conflict("the service's clock cannot be moved", null));
      }
      if (json.path("reset").asBoolean(false)) clock.reset();
      var advance = Wire.text(json, "advance");
      if (advance != null) {
        if (!Durations.valid(advance)) return error(Refusal.invalid("advance must be an ISO 8601 duration"));
        clock.advance(Durations.parse(advance));
      }
      return ok(map("now", clock.now().toString(), "offset", clock.offset().toString()));
    });
  }

  @Post("/dev/tick")
  public HttpResponse devTick(HttpEntity.Strict body) {
    return dev("POST /oversight/dev/tick", body, json -> {
      int expired = 0;
      int atRisk = 0;
      for (var e : pending()) {
        var ticked = entity(e.id()).method(HumanEventEntity::tick).invoke(new HumanEventEntity.Tick(library.now()));
        expired += ticked.expired();
        atRisk += ticked.atRisk();
      }
      return ok(map("expired", expired, "atRisk", atRisk));
    });
  }

  @Post("/dev/reset")
  public HttpResponse devReset(HttpEntity.Strict body) {
    return dev("POST /oversight/dev/reset", body, json -> {
      int removed = 0;
      // The queue view can lag an event raised a moment ago, so passes repeat until one finds nothing new.
      var seen = new java.util.HashSet<String>();
      for (int pass = 0; pass < RESET_PASSES; pass++) {
        var fresh = pending().stream().filter(e -> seen.add(e.id())).toList();
        if (fresh.isEmpty() && pass > 0) break;
        for (var e : fresh) {
          var killed = entity(e.id()).method(HumanEventEntity::kill)
              .invoke(new HumanEventEntity.Close("system:dev-reset", "development reset", library.now()));
          if (!killed.refused()) removed++;
        }
        settle();
      }
      for (var c : library.controls().all()) {
        if (c.haltRestart()) client.forKeyValueEntity(c.haltTarget()).method(HaltEntity::clear).invoke();
      }
      client.forKeyValueEntity(StandingDecisionsEntity.ID).method(StandingDecisionsEntity::clear).invoke();
      return ok(map("removed", removed));
    });
  }

  // ---- internals

  private static final int RESET_PASSES = 10;

  private static void settle() {
    try {
      Thread.sleep(300);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private akka.javasdk.client.EventSourcedEntityClient entity(String id) {
    return client.forEventSourcedEntity(id);
  }

  private List<HumanEvent> all() {
    return HumanQueries.all(client, materializer);
  }

  private List<HumanEvent> pending() {
    return HumanQueries.pending(client, materializer);
  }

  private List<StandingDecision> standing() {
    return client.forKeyValueEntity(StandingDecisionsEntity.ID).method(StandingDecisionsEntity::list).invoke().decisions();
  }

  /**
   * Each halt-restart control's own target, listed under that control, then every other target a halt
   * was exercised on (a target named per call), listed under the control that halted it.
   */
  private List<Map<String, Object>> halts() {
    var out = new ArrayList<Map<String, Object>>();
    var listed = new java.util.HashSet<String>();
    for (var c : library.controls().all()) {
      if (!c.haltRestart()) continue;
      var current = human.halt(c.haltTarget()).state();
      out.add(halt(current == null ? HaltState.notHalted(c) : current.listedUnder(c.control()), c));
      listed.add(c.haltTarget());
    }
    for (var e : all()) {
      if (!e.settings().haltRestart() || !listed.add(e.haltTarget())) continue;
      var current = human.halt(e.haltTarget()).state();
      if (current != null) out.add(halt(current, e.settings()));
    }
    return out;
  }

  private Map<String, Object> halt(HaltState state, io.akka.human.engine.ControlSettings settings) {
    var scope = library.haltScope(state.target(), settings);
    return Json.halt(state, scope.safeStateChecks(state.target()).toCompletableFuture().join(),
        scope.callsPerMinute(state.target()), library.plugins().service());
  }

  /**
   * Each blocked control with the oversight-quality event that blocks it, as the queue view shows them:
   * an event raised a moment ago may not block yet.
   */
  private Map<String, String> blocks() {
    return library.controls().patterns().contains(Pattern.OVERSIGHT_QUALITY) ? Blocks.of(all()) : Map.of();
  }

  /** A restart's safe-state checks as they stand now; nothing for any other event. */
  private Map<String, Boolean> safeState(HumanEvent e) {
    if (!e.settings().haltRestart() || !e.lift()) return Map.of();
    var target = e.haltTarget();
    return library.haltScope(target, e.settings()).safeStateChecks(target).toCompletableFuture().join();
  }

  /** Overdue first, then the least time left, then the oldest; closed events after, newest first. */
  private static Comparator<HumanEvent> urgency(Instant now) {
    return (a, b) -> {
      if (a.pending() != b.pending()) return a.pending() ? -1 : 1;
      if (!a.pending()) return Comparator.nullsLast(Comparator.<Instant>reverseOrder()).compare(a.closedAt(), b.closedAt());
      if (a.overdue(now) != b.overdue(now)) return a.overdue(now) ? -1 : 1;
      int left = Comparator.nullsLast(Comparator.<Instant>naturalOrder()).compare(a.dueAt(), b.dueAt());
      return left != 0 ? left : a.detectedAt().compareTo(b.detectedAt());
    };
  }

  private HttpResponse read(String route, java.util.function.Supplier<HttpResponse> body) {
    var denied = access(route, null);
    if (denied != null) return denied;
    try {
      return body.get();
    } catch (IllegalArgumentException e) {
      return error(Refusal.invalid(e.getMessage()));
    }
  }

  private HttpResponse write(String route, HttpEntity.Strict body,
      BiFunction<JsonNode, Reviewer, HumanEventEntity.Answer> act) {
    try {
      var json = Wire.parse(body.getData().toArray());
      var reviewer = Wire.reviewer(json);
      var denied = access(route, reviewer);
      if (denied != null) return denied;
      return answer(act.apply(json, reviewer));
    } catch (IllegalArgumentException e) {
      return error(Refusal.invalid(e.getMessage()));
    }
  }

  private HttpResponse dev(String route, HttpEntity.Strict body, java.util.function.Function<JsonNode, HttpResponse> act) {
    if (!library.plugins().devRoutes()) return error(Refusal.notFound("the development routes are off"));
    var denied = access(route, null);
    if (denied != null) return denied;
    try {
      return act.apply(Wire.parse(body.getData().toArray()));
    } catch (IllegalArgumentException e) {
      return error(Refusal.invalid(e.getMessage()));
    }
  }

  private HttpResponse access(String route, Reviewer reviewer) {
    var headers = new LinkedHashMap<String, String>();
    for (var h : requestContext().allRequestHeaders()) headers.put(h.lowercaseName(), h.value());
    var refusal = library.plugins().access().refuse(route, reviewer, headers);
    return refusal.map(reason -> error(Refusal.forbidden(reason))).orElse(null);
  }

  private HttpResponse answer(HumanEventEntity.Answer answer) {
    if (answer.refused()) return error(answer.refusal());
    return ok(Json.event(answer.event(), library.now(), Blocks.blocking(answer.event(), blocks())));
  }

  private static HttpResponse ok(Object body) {
    return HttpResponse.create().withStatus(StatusCodes.OK).withEntity(ContentTypes.APPLICATION_JSON, Wire.write(body));
  }

  private static HttpResponse error(Refusal refusal) {
    return HttpResponse.create().withStatus(refusal.status())
        .withEntity(ContentTypes.APPLICATION_JSON, Wire.write(Json.error(refusal)));
  }

  private static Map<String, Object> map(Object... namesAndValues) {
    var m = new LinkedHashMap<String, Object>();
    for (int i = 0; i < namesAndValues.length; i += 2) m.put((String) namesAndValues[i], namesAndValues[i + 1]);
    return m;
  }
}
