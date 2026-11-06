package io.akka.human.samples.loan.application;

import akka.javasdk.client.ComponentClient;
import akka.stream.Materializer;
import io.akka.human.akka.HumanClient;
import io.akka.human.akka.HumanLibrary;
import io.akka.human.akka.HumanQueries;
import io.akka.human.api.Patterns;
import io.akka.human.api.RaiseRequest;
import io.akka.human.api.Resume;
import io.akka.human.api.Subject;
import io.akka.human.api.Trigger;
import io.akka.human.engine.Figures;
import io.akka.human.samples.loan.domain.EligibilityAssessor;
import io.akka.human.samples.loan.domain.NoticeWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The monitors of S2, S3 and S4. Each reads the service's own records, compares its figure with the
 * trigger its control declares, and raises one event per episode ({@link MonitorEntity}): S2 the
 * share of assessments referred to an underwriter, S4 the notice drafts the guardrail blocked, S3 the
 * share of each control's decisions made under its minimum review time.
 */
public final class Monitors {

  /** The apply handler every monitor's event resumes; its id is the monitor's key. */
  public static final String HANDLER = "monitor";
  public static final String DRIFT = "custom-91174bd91366";
  public static final String RUSHED = "custom-b5bb62199a0a";
  public static final String BLOCKS = "custom-347ea9d5a177";

  /** @param monitor the key of the monitor's {@link MonitorEntity}; S3 keeps one per watched control */
  public record Reading(String monitor, String control, double value, boolean fires, String eventId) {}

  private final ComponentClient client;
  private final HumanClient human;
  private final HumanLibrary library;
  private final Materializer materializer;
  private final int referralMinimum;

  public Monitors(ComponentClient client, HumanClient human, HumanLibrary library, Materializer materializer,
      int referralMinimum) {
    this.client = client;
    this.human = human;
    this.library = library;
    this.materializer = materializer;
    this.referralMinimum = referralMinimum;
  }

  public List<Reading> evaluate() {
    var readings = new ArrayList<Reading>();
    readings.add(referralShare());
    readings.add(blockBursts());
    readings.addAll(rushedReviews());
    return readings;
  }

  /** S2: the share of the window's assessments referred, against the previous 7 days or the baseline. */
  Reading referralShare() {
    var trigger = trigger(DRIFT);
    var window = Duration.parse(String.valueOf(trigger.get("window")));
    double threshold = number(trigger, "threshold");
    var now = Instant.now();
    var start = now.minus(window);
    var rows = client.forView().method(ApplicationsView::assessedSince)
        .invoke(new ApplicationsView.Since(start.minus(Duration.ofDays(7)))).entries();
    var current = rows.stream().filter(r -> !r.assessedAt().orElseThrow().isBefore(start)).toList();
    var previous = rows.stream().filter(r -> r.assessedAt().orElseThrow().isBefore(start)).toList();
    double share = percent(current);
    double baseline = previous.size() >= referralMinimum ? percent(previous) : number(trigger, "baseline");
    boolean fires = current.size() >= referralMinimum && share < threshold;
    return report(DRIFT, DRIFT, share, fires, () -> Patterns.triage("The referral share fell to " + share
            + "% over " + window.toHours() + " hours. Acknowledge it, report it, or halt the automated assessment?")
        .subject(Subject.behaviour("Referral share " + share + "% against a baseline of " + baseline + "%",
                "referral-share", share, threshold, "<")
            .with("unit", "%").with("window", trigger.get("window")).with("baseline", baseline)
            .with("contributors", List.of(Subject.fields("kind", "assessments", "ref", "last " + window.toHours() + "h",
                "weight", (double) current.size())))
            .withAgent(EligibilityAssessor.AGENT).withRef(EligibilityAssessor.CAPABILITY))
        .trigger(new Trigger("monitor", "referral-share < " + threshold, current.stream().filter(
                ApplicationsView.Row::referred).count() + " of " + current.size() + " assessments went to an underwriter.",
            List.of(new Trigger.Value("referral-share", share, threshold),
                new Trigger.Value("assessments", current.size(), referralMinimum)),
            List.of(new Trigger.Condition("referral-share < " + threshold, true)))));
  }

  /** S4: the notice drafts the protected-characteristics guardrail blocked in the window. */
  Reading blockBursts() {
    var trigger = trigger(BLOCKS);
    var window = Duration.parse(String.valueOf(trigger.get("window")));
    double threshold = number(trigger, "threshold");
    var blocked = client.forView().method(ApplicationsView::blockedSince)
        .invoke(new ApplicationsView.Since(Instant.now().minus(window))).entries();
    double count = blocked.size();
    return report(BLOCKS, BLOCKS, count, count > threshold, () -> Patterns.triage((int) count
            + " notice drafts were blocked for protected characteristics in the last " + window.toMinutes()
            + " minutes. Acknowledge it, report it, or ask for more?")
        .subject(Subject.behaviour((int) count + " protected-characteristics blocks in " + trigger.get("window"),
                "protected-characteristics-blocks", count, threshold, ">")
            .with("unit", "blocks").with("window", trigger.get("window"))
            .with("contributors", blocked.stream().map(r -> (Object) Subject.fields("kind", "application", "ref", r.id(),
                "weight", 1.0)).toList())
            .withAgent(NoticeWriter.AGENT).withRef(NoticeWriter.AGENT))
        .trigger(new Trigger("monitor", "protected-characteristics-blocks > " + threshold,
            "The guardrail blocked " + (int) count + " notice drafts.",
            List.of(new Trigger.Value("protected-characteristics-blocks", count, threshold)),
            List.of(new Trigger.Condition("protected-characteristics-blocks > " + threshold, true)))));
  }

  /** S3: the library's oversight-quality figures for each control the rushed-review control watches. */
  List<Reading> rushedReviews() {
    var signals = Figures.signals(HumanQueries.all(client, materializer), library.controls(), library.now());
    return signals.stream().filter(s -> s.qualityControl().equals(RUSHED)).map(s -> report(RUSHED + ":" + s.control(),
        RUSHED, s.value(), s.breached(), () -> Patterns.oversightQuality(Math.round(s.value() * 100) + "% of the decisions on "
                + s.control() + " were made under its minimum review time. Reset, or set a new minimum?")
            .subject(Subject.behaviour(s.signal() + " on " + s.control() + " is " + s.value(), s.signal(), s.value(),
                    s.limit(), s.comparison())
                .with("window", trigger(RUSHED).get("window")).withRef(s.control()))
            .trigger(new Trigger("monitor", s.signal() + " " + s.comparison() + " " + s.limit(),
                "Reviews of " + s.control() + " are faster than a person can read the case.",
                List.of(new Trigger.Value(s.signal(), s.value(), s.limit())),
                List.of(new Trigger.Condition(s.signal() + " " + s.comparison() + " " + s.limit(), true)))))).toList();
  }

  /** Raises the episode's event, adds an occurrence to it while pending, or does nothing once answered. */
  private Reading report(String key, String control, double value, boolean fires, Supplier<RaiseRequest.Builder> raise) {
    var monitor = client.forKeyValueEntity(key);
    var state = monitor.method(MonitorEntity::get).invoke();
    if (!fires) {
      if (state.firing()) monitor.method(MonitorEntity::stopped).invoke(value);
      return new Reading(key, control, value, false, null);
    }
    if (state.firing() && state.eventId() != null && !pending(state.eventId())) {
      return new Reading(key, control, value, true, state.eventId());
    }
    int episode = state.firing() ? state.episode() : state.episode() + 1;
    var raised = human.raise(raise.get().control(control).dedupeKey(key + "#" + episode)
        .resumes(Resume.handler(HANDLER, key)).build()).toCompletableFuture().join();
    monitor.method(MonitorEntity::fired).invoke(new MonitorEntity.Fired(episode, raised.eventId(), value));
    return new Reading(key, control, value, true, raised.eventId());
  }

  private boolean pending(String eventId) {
    var event = human.find(eventId).event();
    return event != null && event.pending();
  }

  private Map<String, Object> trigger(String control) {
    return library.controls().find(control).orElseThrow().trigger();
  }

  private static double number(Map<String, Object> trigger, String name) {
    return ((Number) trigger.get(name)).doubleValue();
  }

  private static double percent(List<ApplicationsView.Row> rows) {
    if (rows.isEmpty()) return 0.0;
    return Math.round(1000.0 * rows.stream().filter(ApplicationsView.Row::referred).count() / rows.size()) / 10.0;
  }
}
