package io.akka.human.conformance;

import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * One scenario run for each pattern (or only for {@code onlyPattern}). {@code skipReason} answers why the
 * control's settings or the registry make it inapplicable, or null when it applies.
 */
record Scenario(String name, String onlyPattern, Function<Target, String> skipReason, BiConsumer<Run, Target> body) {}
