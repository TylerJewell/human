package io.akka.human.api;

/** How far a decision extends beyond its own event (human-decisions.yaml sticky_modes). */
public enum Sticky {
  APPROVE_ONCE,
  APPROVE_FOR_SESSION,
  APPROVE_FOR_PATTERN,
  DENY_FOR_PATTERN
}
