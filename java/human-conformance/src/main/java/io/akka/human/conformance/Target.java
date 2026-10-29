package io.akka.human.conformance;

import java.util.List;

/** What a pattern scenario runs against: the control discovered for the pattern, the registry, every control. */
record Target(Control control, Registry registry, List<Control> controls) {}
