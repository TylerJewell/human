package io.akka.human.testkit;

import static org.awaitility.Awaitility.await;

import akka.javasdk.client.ComponentClient;
import io.akka.human.akka.HumanClient;
import io.akka.human.akka.HumanEventEntity;
import io.akka.human.akka.HumanLibrary;
import io.akka.human.akka.MovableClock;
import io.akka.human.api.Action;
import io.akka.human.api.RaiseRequest;
import io.akka.human.api.Reviewer;
import io.akka.human.api.spi.HumanPlugins;
import io.akka.human.engine.HumanEvent;
import io.akka.human.engine.Rules;
import io.akka.human.engine.State;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Raises, claims and decides oversight events in a service's TestKit tests, through the same entity
 * calls the {@code /oversight} endpoint makes. Calls that the rules refuse answer the refusal, so a
 * test can assert on it; the {@code ...Ok} helpers fail the test instead.
 *
 * <pre>{@code
 * var kit = new HumanTestKit(componentClient, plugins);
 * var event = kit.raise(Patterns.actionApproval("Pay S-77?").control("pt-00001")...build());
 * kit.claimAndDecide(event.id(), HumanTestKit.reviewer("tyler.jewell@akka.io", "ai-system-owner"), Action.APPROVE, "ok");
 * kit.awaitApplied(event.id());
 * }</pre>
 */
public final class HumanTestKit {

  private static final Duration WAIT = Duration.ofSeconds(15);

  private final ComponentClient client;
  private final HumanClient human;
  private final HumanLibrary library;

  public HumanTestKit(ComponentClient client, HumanPlugins plugins) {
    this.client = client;
    this.human = new HumanClient(client, plugins);
    this.library = HumanLibrary.of(plugins);
  }

  public static Reviewer reviewer(String subject, String... roles) {
    return new Reviewer(subject, subject, Arrays.asList(roles), "human-testkit", false);
  }

  public HumanClient human() {
    return human;
  }

  public Instant now() {
    return library.now();
  }

  /** Moves the service's clock; the plug-ins' clock must be a {@link MovableClock}, such as {@link TestClock}. */
  public void advance(Duration by) {
    if (!(library.plugins().clock() instanceof MovableClock clock)) {
      throw new IllegalStateException("the service's HumanPlugins clock cannot be moved; use TestClock");
    }
    clock.advance(by);
  }

  // ---- raising and reading

  public HumanEvent raise(RaiseRequest request) {
    return ok(human.open(request), "raise");
  }

  public HumanEvent event(String id) {
    return human.find(id).event();
  }

  // ---- acting

  public HumanEventEntity.Answer claim(String id, Reviewer reviewer) {
    return entity(id).method(HumanEventEntity::claim).invoke(new HumanEventEntity.Act(library.actor(reviewer), now()));
  }

  public HumanEventEntity.Answer release(String id, Reviewer reviewer) {
    return entity(id).method(HumanEventEntity::release).invoke(new HumanEventEntity.Release(reviewer, now()));
  }

  public HumanEventEntity.Answer decide(String id, Reviewer reviewer, Action action, String rationale) {
    return decide(id, new Rules.DecisionRequest(reviewer, action, rationale, null, null, null, List.of(), null));
  }

  public HumanEventEntity.Answer decide(String id, Rules.DecisionRequest request) {
    return decide(id, request, Map.of());
  }

  /** A decision on a restart, with the target's safe-state checks as the endpoint would pass them. */
  public HumanEventEntity.Answer decide(String id, Rules.DecisionRequest request, Map<String, Boolean> safeState) {
    return entity(id).method(HumanEventEntity::decide)
        .invoke(new HumanEventEntity.Decide(library.actor(request.reviewer()), request, safeState, now()));
  }

  /**
   * Claims the event, moves the clock past the control's minimum review time and decides; fails the
   * test if either is refused. Moving the clock moves every event's deadline with it.
   */
  public HumanEvent claimAndDecide(String id, Reviewer reviewer, Action action, String rationale) {
    var claimed = ok(claim(id, reviewer), "claim");
    var minimum = claimed.settings().minimumReviewSeconds();
    if (minimum > 0) advance(Duration.ofSeconds(minimum));
    return ok(decide(id, reviewer, action, rationale), "decide");
  }

  public HumanEventEntity.Answer escalate(String id, Reviewer reviewer, String reason) {
    return entity(id).method(HumanEventEntity::escalate)
        .invoke(new HumanEventEntity.Move(library.actor(reviewer), reason, now()));
  }

  public HumanEventEntity.Answer sendBack(String id, Reviewer reviewer, String requested) {
    return entity(id).method(HumanEventEntity::sendBack)
        .invoke(new HumanEventEntity.Move(library.actor(reviewer), requested, now()));
  }

  public HumanEventEntity.Answer resubmit(String id, Reviewer proposer, String note) {
    return entity(id).method(HumanEventEntity::resubmit).invoke(new HumanEventEntity.ByProposer(proposer, note, now()));
  }

  /** Runs what is due on the event at the service's clock, as its deadline timer would. */
  public HumanEvent tick(String id) {
    return entity(id).method(HumanEventEntity::tick).invoke(new HumanEventEntity.Tick(now())).event();
  }

  // ---- waiting

  public HumanEvent awaitState(String id, State... states) {
    var wanted = List.of(states);
    return await().atMost(WAIT).until(() -> event(id), e -> e != null && wanted.contains(e.state()));
  }

  /** Waits until whoever waited on the outcome has been told: the event is marked applied or failed. */
  public HumanEvent awaitApplied(String id) {
    return await().atMost(WAIT).until(() -> event(id), e -> e != null && e.outcome() != null && e.outcome().delivered());
  }

  /** The event, or a failure naming the refusal. */
  public static HumanEvent ok(HumanEventEntity.Answer answer, String what) {
    if (answer.refused()) {
      throw new AssertionError(what + " was refused (" + answer.refusal().status() + "): " + answer.refusal().error());
    }
    return answer.event();
  }

  private akka.javasdk.client.EventSourcedEntityClient entity(String id) {
    return client.forEventSourcedEntity(id);
  }
}
