package io.akka.human.api;

import java.util.Optional;

/**
 * The answer to raising an event.
 *
 * @param occurrences more than one when the raise added to a pending event with the same dedupe key
 * @param settled present when a standing decision settled the event at once; the resume target is
 *     still called, so a caller may ignore this and wait
 */
public record Raised(String eventId, String state, int occurrences, Optional<Decision> settled) {}
