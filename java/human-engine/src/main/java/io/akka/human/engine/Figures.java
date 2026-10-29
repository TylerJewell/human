package io.akka.human.engine;

import io.akka.human.api.Action;
import io.akka.human.api.Pattern;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * The Summary and Quality figures, computed once on the server from the events so every console shows
 * the same numbers. Shadow events count as triggers but are not waiting for anyone.
 */
public final class Figures {

  /** Decisions that did not take the system's output as presented. */
  private static final Set<Action> OVERRIDES = Set.of(Action.EDIT, Action.OVERRIDE, Action.REVERSE, Action.DISREGARD);

  /**
   * An oversight-quality finding: a monitored control's figure against the limit an oversight-quality
   * control sets.
   *
   * @param qualityControl the oversight-quality control that watches the figure
   * @param breached whether the figure crosses the limit, so the control should raise an event
   * @param eventId the oversight-quality event raised for it, if any
   */
  public record Signal(
      String qualityControl,
      String control,
      String signal,
      double value,
      String comparison,
      double limit,
      boolean breached,
      String eventId) {}

  private Figures() {}

  // ---- Summary

  public static Map<String, Object> summary(List<HumanEvent> events, Controls controls, List<Map<String, Object>> halts,
      List<StandingDecision> standing, Instant now) {
    var routed = events.stream().filter(e -> !e.shadow()).toList();
    var pending = routed.stream().filter(HumanEvent::pending).toList();
    var loop = new LinkedHashMap<String, Object>();
    for (var loopAndMode : List.of(List.of("in", "HITL"), List.of("on", "HOTL"), List.of("over", "HALT"))) {
      var mode = loopAndMode.get(1);
      var inMode = routed.stream().filter(e -> e.settings().mode().equals(mode)).toList();
      loop.put(loopAndMode.get(0), Json.map(
          "pending", inMode.stream().filter(HumanEvent::pending).count(),
          "overdue", inMode.stream().filter(e -> e.overdue(now)).count(),
          "raised7d", perDay(inMode, 7, now)));
    }
    var states = new TreeMap<String, Long>();
    for (var e : routed) states.merge(e.state().name(), 1L, Long::sum);
    var patterns = new LinkedHashMap<String, Object>();
    for (var p : Pattern.values()) {
      long n = pending.stream().filter(e -> e.settings().pattern() == p).count();
      if (n > 0) patterns.put(p.id(), n);
    }
    var m = new LinkedHashMap<String, Object>();
    m.put("loop", loop);
    m.put("states", states);
    m.put("patterns", patterns);
    m.put("atRisk", pending.stream().filter(e -> e.slaState(now).equals("at-risk")).count());
    m.put("overdue", pending.stream().filter(e -> e.overdue(now)).count());
    m.put("halts", halts);
    m.put("raised30d", perDay(routed, 30, now));
    m.put("standing", Json.map(
        "active", standing.stream().filter(d -> d.activeAt(now)).count(),
        "expiring", standing.stream().filter(d -> d.stateAt(now).equals("EXPIRING")).count()));
    m.put("unownedControls", controls.all().stream().filter(c -> c.accountableOwner() == null).count());
    return m;
  }

  /** Events raised per day, oldest first, ending today. */
  private static List<Long> perDay(List<HumanEvent> events, int days, Instant now) {
    var today = LocalDate.ofInstant(now, ZoneOffset.UTC);
    var counts = new ArrayList<Long>();
    for (int i = days - 1; i >= 0; i--) {
      var day = today.minusDays(i);
      counts.add(events.stream().filter(e -> LocalDate.ofInstant(e.detectedAt(), ZoneOffset.UTC).equals(day)).count());
    }
    return counts;
  }

  // ---- Quality

  /**
   * @param names a reviewer's name by subject, from the overseer registry
   * @param windowText the window as asked for, such as P30D
   */
  public static Map<String, Object> quality(List<HumanEvent> events, Controls controls, Function<String, String> names,
      String windowText, Instant now) {
    var since = now.minus(Durations.parse(windowText));
    var inWindow = events.stream().filter(e -> !e.detectedAt().isBefore(since)).toList();
    var votes = new ArrayList<Cast>();
    for (var e : events) {
      for (var v : e.decisions()) if (!v.submittedAt().isBefore(since)) votes.add(new Cast(e, v));
    }

    var reviewTimes = new ArrayList<Object>();
    for (var c : controls.all()) {
      var forControl = votes.stream().filter(x -> x.event.settings().control().rowId().equals(c.control().rowId())).toList();
      if (forControl.isEmpty()) continue;
      int min = c.minimumReviewSeconds();
      var bounds = min > 0 ? List.of(min / 2, min) : List.of(10, 60);
      var buckets = new ArrayList<Object>();
      int lower = -1;
      for (var bound : bounds) {
        int low = lower;
        buckets.add(Json.map("upTo", bound, "count",
            forControl.stream().filter(x -> x.vote.reviewSeconds() > low && x.vote.reviewSeconds() <= bound).count()));
        lower = bound;
      }
      int last = lower;
      buckets.add(Json.map("upTo", null, "count", forControl.stream().filter(x -> x.vote.reviewSeconds() > last).count()));
      var row = Json.map("control", c.control().rowId(), "name", c.control().name(), "minimumSeconds", min,
          "buckets", buckets, "belowMinimumShare", belowMinimumShare(forControl, min));
      reviewTimes.add(row);
    }

    var reviewers = new ArrayList<Object>();
    var bySubject = new TreeMap<String, List<Cast>>();
    for (var x : votes) bySubject.computeIfAbsent(x.vote.reviewer().subject(), k -> new ArrayList<>()).add(x);
    for (var entry : bySubject.entrySet()) {
      var subject = entry.getKey();
      var mine = entry.getValue();
      var withAi = mine.stream().filter(x -> x.event.aiRecommendation() != null
          && x.event.aiRecommendation().get("action") != null).toList();
      var held = events.stream().filter(e -> e.pending() && e.holderAt(now) != null
          && e.holderAt(now).subject().equals(subject)).toList();
      var roles = mine.getLast().vote.reviewer().roles();
      var oldest = held.stream().map(e -> Duration.between(e.holderAt(now).since(), now).toSeconds())
          .max(Long::compare).orElse(null);
      var r = new LinkedHashMap<String, Object>();
      r.put("subject", subject);
      var name = names.apply(subject);
      r.put("name", name == null ? mine.getLast().vote.reviewer().name() : name);
      r.put("decisions", mine.size());
      r.put("approvalRate", share(mine.stream().filter(x -> x.vote.action().proceeds()).count(), mine.size()));
      r.put("overrideRate", share(mine.stream().filter(x -> OVERRIDES.contains(x.vote.action())).count(), mine.size()));
      r.put("agreementWithAi", withAi.isEmpty() ? null : share(withAi.stream().filter(x -> x.vote.action().name()
          .equals(String.valueOf(x.event.aiRecommendation().get("action")))).count(), withAi.size()));
      r.put("medianReviewSeconds", median(mine.stream().map(x -> x.vote.reviewSeconds()).toList()));
      r.put("open", events.stream().filter(e -> e.pending() && !e.shadow() && e.state() != State.CHANGES_REQUESTED
          && e.currentRoles().stream().anyMatch(roles::contains)).count());
      r.put("claimed", held.size());
      r.put("oldestClaimSeconds", oldest);
      r.put("share", share(mine.size(), votes.size()));
      reviewers.add(r);
    }

    var closed = events.stream().filter(e -> !e.pending() && e.closedAt() != null && !e.closedAt().isBefore(since)).toList();
    var outcomes = new LinkedHashMap<String, Object>();
    outcomes.put("triggered", inWindow.size());
    outcomes.put("approved", closed.stream().filter(e -> HumanEvent.COMPLETED.equals(slaOutcome(e))
        && e.outcome().action() != null && e.outcome().action().proceeds()).count());
    outcomes.put("denied", closed.stream().filter(e -> HumanEvent.COMPLETED.equals(slaOutcome(e))
        && e.outcome().action() == Action.DENY).count());
    outcomes.put("timedOut", closed.stream().filter(e -> HumanEvent.TIMED_OUT.equals(slaOutcome(e))).count());
    outcomes.put("abandoned", closed.stream().filter(e -> HumanEvent.ABANDONED.equals(slaOutcome(e))).count());
    outcomes.put("killed", closed.stream().filter(e -> HumanEvent.KILLED.equals(slaOutcome(e))).count());

    var weekly = new TreeMap<String, long[]>();
    for (var e : inWindow) weekly.computeIfAbsent(week(e.detectedAt()), k -> new long[2])[0]++;
    for (var e : closed) weekly.computeIfAbsent(week(e.closedAt()), k -> new long[2])[1]++;
    var heat = new TreeMap<String, Long>();
    for (var x : votes) {
      var at = x.vote.submittedAt().atZone(ZoneOffset.UTC);
      heat.merge(at.getDayOfWeek().getValue() + ":" + at.getHour(), 1L, Long::sum);
    }

    var twoPerson = closed.stream().filter(e -> !"SINGLE".equals(e.settings().routing().reviewMode())
        && e.settlingVote() != null).toList();
    var m = new LinkedHashMap<String, Object>();
    m.put("window", windowText);
    m.put("reviewTimes", reviewTimes);
    m.put("reviewers", reviewers);
    m.put("outcomes", outcomes);
    m.put("weekly", weekly.entrySet().stream().map(w -> (Object) Json.map("week", w.getKey(), "raised", w.getValue()[0],
        "closed", w.getValue()[1])).toList());
    m.put("heatmap", heat.entrySet().stream().map(h -> {
      var parts = h.getKey().split(":");
      return (Object) Json.map("day", DayOfWeek.of(Integer.parseInt(parts[0])).name(), "hour", Integer.parseInt(parts[1]),
          "decisions", h.getValue());
    }).toList());
    m.put("coverage", Json.map("triggers", inWindow.size(),
        "reachedPerson", inWindow.stream().filter(e -> !e.decisions().isEmpty() || e.claimedAt() != null).count()));
    m.put("agreement", Json.map("twoPersonEvents", twoPerson.size(),
        "agreed", twoPerson.stream().filter(e -> e.roundVotes().stream().map(HumanEvent.Vote::action).distinct().count() == 1)
            .count()));
    m.put("signals", signals(events, controls, now).stream().map(s -> (Object) Json.map("control", s.control(),
        "signal", s.signal(), "value", s.value(), "limit", s.limit(), "eventId", s.eventId())).toList());
    return m;
  }

  /**
   * The figures every oversight-quality control of the catalog watches, one per monitored control,
   * over the window its trigger names (P7D when it names none). Supported metrics:
   * {@code below-minimum-share} (controls with a minimum review time), {@code approval-rate} and
   * {@code override-rate}.
   */
  public static List<Signal> signals(List<HumanEvent> events, Controls controls, Instant now) {
    var out = new ArrayList<Signal>();
    for (var quality : controls.all()) {
      if (quality.pattern() != Pattern.OVERSIGHT_QUALITY) continue;
      var t = quality.trigger();
      var metric = t.get("metric") == null ? null : String.valueOf(t.get("metric"));
      if (metric == null || !(t.get("threshold") instanceof Number threshold)) continue;
      var comparison = t.get("comparison") == null ? ">" : String.valueOf(t.get("comparison"));
      var window = t.get("window") == null ? "P7D" : String.valueOf(t.get("window"));
      var since = now.minus(Durations.parse(window));
      for (var watched : controls.all()) {
        if (watched.pattern() == Pattern.OVERSIGHT_QUALITY) continue;
        if (metric.equals("below-minimum-share") && watched.minimumReviewSeconds() == 0) continue;
        var casts = new ArrayList<Cast>();
        for (var e : events) {
          if (!e.settings().control().rowId().equals(watched.control().rowId())) continue;
          for (var v : e.decisions()) if (!v.submittedAt().isBefore(since)) casts.add(new Cast(e, v));
        }
        if (casts.isEmpty() && !metric.equals("below-minimum-share")) continue;
        double value = switch (metric) {
          case "below-minimum-share" -> belowMinimumShare(casts, watched.minimumReviewSeconds());
          case "approval-rate" -> share(casts.stream().filter(x -> x.vote.action().proceeds()).count(), casts.size());
          case "override-rate" -> share(casts.stream().filter(x -> OVERRIDES.contains(x.vote.action())).count(), casts.size());
          default -> Double.NaN;
        };
        if (Double.isNaN(value)) continue;
        var raised = events.stream().filter(e -> e.settings().control().rowId().equals(quality.control().rowId())
                && watched.control().rowId().equals(e.subject().ref()))
            .max(Comparator.comparing(HumanEvent::detectedAt)).map(HumanEvent::id).orElse(null);
        out.add(new Signal(quality.control().rowId(), watched.control().rowId(), metric, value, comparison,
            threshold.doubleValue(), crosses(value, comparison, threshold.doubleValue()), raised));
      }
    }
    return out;
  }

  static boolean crosses(double value, String comparison, double limit) {
    return switch (comparison) {
      case "<" -> value < limit;
      case "<=" -> value <= limit;
      case ">=" -> value >= limit;
      case "==" -> value == limit;
      case "!=" -> value != limit;
      default -> value > limit;
    };
  }

  private record Cast(HumanEvent event, HumanEvent.Vote vote) {}

  private static double belowMinimumShare(List<Cast> casts, int minimum) {
    if (minimum == 0 || casts.isEmpty()) return 0.0;
    return share(casts.stream().filter(x -> x.vote.reviewSeconds() < minimum).count(), casts.size());
  }

  private static double share(long part, long whole) {
    return whole == 0 ? 0.0 : Math.round(1000.0 * part / whole) / 1000.0;
  }

  private static Double median(List<Long> values) {
    if (values.isEmpty()) return null;
    var sorted = values.stream().sorted().toList();
    int n = sorted.size();
    return n % 2 == 1 ? (double) sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
  }

  private static String slaOutcome(HumanEvent e) {
    return e.outcome() == null ? null : e.outcome().slaOutcome();
  }

  private static String week(Instant at) {
    var day = LocalDate.ofInstant(at, ZoneOffset.UTC);
    return day.get(IsoFields.WEEK_BASED_YEAR) + "-W" + String.format("%02d", day.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
  }
}
