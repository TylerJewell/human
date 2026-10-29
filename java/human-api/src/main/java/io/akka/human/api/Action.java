package io.akka.human.api;

import java.util.Set;

/** The decision vocabulary of Verify's decision registry (human-decisions.yaml). */
public enum Action {
  APPROVE,
  DENY,
  EDIT,
  REDIRECT,
  REPLAN,
  ESCALATE,
  HALT,
  RECUSE,
  REQUEST_MORE_INFO,
  SEND_BACK,
  DECLINE_SYSTEM,
  OVERRIDE,
  DISREGARD,
  REVERSE;

  private static final Set<Action> ROUTING = Set.of(ESCALATE, SEND_BACK, REQUEST_MORE_INFO, RECUSE);

  /** Whether the action moves the event without settling it. */
  public boolean routing() {
    return ROUTING.contains(this);
  }

  /** Whether the subject goes ahead, as presented or as edited. */
  public boolean proceeds() {
    return this == APPROVE || this == EDIT;
  }
}
