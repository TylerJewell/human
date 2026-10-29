package io.akka.human.engine;

/** The states of the contract. The first seven are pending; the rest are not. */
public enum State {
  DETECTED,
  OPEN,
  CLAIMED,
  IN_REVIEW,
  AWAITING_SECOND,
  ESCALATED,
  CHANGES_REQUESTED,
  DECIDED,
  APPLIED,
  TIMED_OUT,
  ABANDONED,
  KILLED,
  WITHDRAWN,
  CLOSED,
  REOPENED;

  public boolean pending() {
    return ordinal() <= CHANGES_REQUESTED.ordinal();
  }
}
