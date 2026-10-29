package io.akka.human.engine;

import io.akka.human.api.Action;
import io.akka.human.api.Sticky;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A decision that extends beyond its event: APPROVE_FOR_PATTERN and DENY_FOR_PATTERN on the event's
 * match key, APPROVE_FOR_SESSION on its session. It settles a later raise of the same control that
 * matches, for 30 days unless revoked.
 *
 * @param kind pattern-approval, pattern-denial or session-approval
 * @param action what a matching raise is settled with: APPROVE or DENY
 * @param usedBy the events it settled
 * @param conditions the conditions the approval that created it carried
 */
public record StandingDecision(
    String id,
    String kind,
    String eventId,
    ControlSettings.ControlRef control,
    String matchKey,
    String sessionId,
    String allows,
    String grantedBy,
    Instant grantedAt,
    Instant expiresAt,
    Action action,
    List<String> usedBy,
    Revocation revoked,
    List<Map<String, Object>> conditions) {

  public record Revocation(String by, Instant at, String reason) {}

  public static final Duration TERM = Duration.ofDays(30);
  public static final Duration EXPIRING = Duration.ofDays(7);

  public StandingDecision {
    usedBy = usedBy == null ? List.of() : List.copyOf(usedBy);
    conditions = conditions == null ? List.of() : List.copyOf(conditions);
  }

  /** The standing decision a settled event creates, or null when its decision extends to nothing. */
  public static StandingDecision from(HumanEvent e) {
    var o = e.outcome();
    if (o == null || o.byStanding() || o.standingDecisionId() == null || o.sticky() == null) return null;
    var denial = o.sticky() == Sticky.DENY_FOR_PATTERN;
    var session = o.sticky() == Sticky.APPROVE_FOR_SESSION;
    var at = e.closedAt();
    return new StandingDecision(o.standingDecisionId(),
        denial ? "pattern-denial" : session ? "session-approval" : "pattern-approval", e.id(),
        e.settings().control(), e.matchKey(), session ? e.subject().sessionId() : null,
        (denial ? "deny " : "approve ") + e.subject().summary(), e.deciders().isEmpty() ? "" : e.deciders().getLast(),
        at, at.plus(TERM), denial ? Action.DENY : Action.APPROVE, List.of(), null, o.conditions());
  }

  /** ACTIVE, EXPIRING (within a week of its end), EXPIRED or REVOKED. */
  public String stateAt(Instant now) {
    if (revoked != null) return "REVOKED";
    if (!now.isBefore(expiresAt)) return "EXPIRED";
    return now.isBefore(expiresAt.minus(EXPIRING)) ? "ACTIVE" : "EXPIRING";
  }

  public boolean activeAt(Instant now) {
    return revoked == null && now.isBefore(expiresAt);
  }

  /** Whether it settles a raise of {@code control} with this match key, in this session. */
  public boolean matches(String control, String key, String session, Instant now) {
    if (!activeAt(now) || !this.control.rowId().equals(control)) return false;
    if (sessionId != null) return sessionId.equals(session) && (matchKey == null || matchKey.equals(key));
    return matchKey != null && matchKey.equals(key);
  }

  public StandingDecision usedFor(String event) {
    if (usedBy.contains(event)) return this;
    var next = new ArrayList<>(usedBy);
    next.add(event);
    return new StandingDecision(id, kind, eventId, control, matchKey, sessionId, allows, grantedBy, grantedAt, expiresAt,
        action, next, revoked, conditions);
  }

  public StandingDecision revokedBy(String by, Instant at, String reason) {
    return new StandingDecision(id, kind, eventId, control, matchKey, sessionId, allows, grantedBy, grantedAt, expiresAt,
        action, usedBy, new Revocation(by, at, reason), conditions);
  }
}
